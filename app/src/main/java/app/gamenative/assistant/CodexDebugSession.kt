package app.gamenative.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.MainActivity
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.DebugReportUtils
import com.winlator.container.Container
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Reuses the existing observation buffers. No input interception, new sampler, logcat, or cloud calls. */
object CodexDebugSession {
    data class Status(val game: String, val reportId: String, val recording: Boolean, val markers: Int, val problem: DebugProblem, val error: String? = null)
    private val mutableStatus = MutableStateFlow<Status?>(null)
    val status = mutableStatus.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private data class Pending(val context: Context, val game: String, val problem: DebugProblem, val at: Long, val reportId: String)
    private data class Launch(val context: Context, val game: String, val token: String, val startedAt: Long,
        val setup: JSONObject, val verbose: Boolean, val draftId: String? = null, var report: JSONObject? = null,
        var lastLogMs: Long = 0, val lastLogTexts: MutableSet<String> = mutableSetOf(), var lastSampleMs: Long = 0,
        @Volatile var finalView: LiveSessionBuffer.View? = null, @Volatile var finalTrace: String? = null)
    @Volatile private var pending: Pending? = null
    fun requested(game: String) = pending?.let { it.game == game && System.currentTimeMillis() - it.at in 0..300_000 } == true
    fun wantsVerbose(game: String) = requested(game) && pending?.problem == DebugProblem.CRASH
    @Volatile private var launch: Launch? = null
    @Volatile private var saveError: String? = null
    private var ticker: Job? = null
    private var inputTestClaim: String? = null
    @Synchronized fun claimInputTest(game: String, reportId: String): Boolean {
        val current = status.value ?: return false
        if (current.game != game || current.reportId != reportId || !current.recording || current.problem != DebugProblem.INPUT || inputTestClaim == reportId) return false
        inputTestClaim = reportId
        return true
    }

    fun store(context: Context) = CodexDebugReportStore(File(context.noBackupFilesDir, "assistant/debug-reports"))
    fun intent(context: Context, game: String, report: String? = null, setup: Boolean = false) =
        Intent(context, GameAssistantActivity::class.java).putExtra("app_id", game)
            .putExtra("game_title", ContainerUtils.resolveGameName(game)).putExtra("debug_report_id", report)
            .putExtra("debug_setup", setup)

    suspend fun requestLaunch(context: Context, game: String, problem: DebugProblem): Intent = withContext(Dispatchers.IO) {
        check(LiveGameSession.token() == null && !app.gamenative.service.SteamService.keepAlive) { "Stäng pågående spel först." }
        val source = ContainerUtils.extractGameSourceFromContainerId(game)
        val id = ContainerUtils.extractGameIdFromContainerId(game)
        val at = System.currentTimeMillis()
        val configuration = runCatching { if (ContainerUtils.hasContainer(context, game))
            CodexDebugReportStore.configuration(JSONObject(ContainerUtils.getContainer(context, game).containerJson)) else null }.getOrNull()
        mutex.withLock {
            store(context).recover(null)
            val draft = store(context).create(game, "pending-launch", at, problem,
                JSONObject().put("configuration", configuration ?: JSONObject.NULL)
                    .put("configurationOrigin", "Saved settings before launch; no effective launch or game process was observed yet"), "pending_launch")
            pending = Pending(context.applicationContext, game, problem, at, draft.getString("id"))
        }
        Intent(context, MainActivity::class.java).setAction("${context.packageName}.LAUNCH_GAME")
            .putExtra("app_id", id).putExtra("game_source", source.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    /** Called during environment setup, before guest processes start. Returns the requested logging preset. */
    fun begin(context: Context, game: String, container: Container?, debugRun: Boolean): Boolean {
        if (!BuildConfig.AI_ASSISTANT_ENABLED) return false
        val view = LiveGameSession.view(game) ?: return false
        val requested = pending?.takeIf { it.game == game && System.currentTimeMillis() - it.at in 0..300_000 }
        pending = null
        val problem = requested?.problem ?: if (debugRun) DebugProblem.CRASH else null
        val verbose = problem == DebugProblem.CRASH
        val config = runCatching { container?.let { CodexDebugReportStore.configuration(JSONObject(it.containerJson)) } }.getOrNull()
        val setup = JSONObject().put("configuration", config ?: JSONObject.NULL)
            .put("configurationOrigin", "Effective container at this launch; not current saved settings")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE)
            .put("appVersion", BuildConfig.VERSION_NAME).put("verboseLogging", verbose)
            .put("mods", "Mod metadata is not yet available")
        val current = Launch(context.applicationContext, game, view.token, view.startedAtMs, setup, verbose, requested?.reportId)
        launch = current
        mutableStatus.value = null
        ticker?.cancel()
        scope.launch {
            mutex.withLock {
                if (launch !== current) return@withLock
                runCatching {
                    store(context).recover(current.token)
                    if (problem != null) startLocked(current, problem)
                }.onFailure { recordError(it) }
            }
            // Only mod names/versions/status; never archive contents or credentials.
            val mods = runCatching { JSONObject(GameModTools(context, game, game).inventory()) }.getOrNull()
            mutex.withLock {
                if (launch === current && mods != null) {
                    current.setup.put("mods", CodexDebugReportStore.safe(mods)).put("modsCapturedAtMs", System.currentTimeMillis())
                    current.report?.put("setup", JSONObject(current.setup.toString()))
                }
            }
        }
        return verbose
    }

    fun effectiveConfiguration(game: String, container: Container?) {
        val current = launch?.takeIf { it.game == game } ?: return
        val config = runCatching { container?.let { CodexDebugReportStore.configuration(JSONObject(it.containerJson)) } }.getOrNull() ?: return
        scope.launch { mutex.withLock {
            if (launch === current) {
                current.setup.put("configuration", config)
                current.report?.put("setup", JSONObject(current.setup.toString()))
            }
        } }
    }

    private fun recordError(error: Throwable) {
        saveError = DiagnosticRedactor.text(error.message ?: "Rapporten kunde inte sparas.").take(300)
        mutableStatus.value = mutableStatus.value?.copy(error = saveError)
    }
    fun saveErrorFor(game: String): String? = saveError.takeIf { launch?.game == game }

    private fun startLocked(current: Launch, problem: DebugProblem): String {
        current.report?.takeIf { it.optString("state") == "recording" }?.let { return it.getString("id") }
        current.lastLogMs = 0; current.lastLogTexts.clear(); current.lastSampleMs = 0
        val source = if (current.verbose) "debug_launch" else "live_observation"
        val draft = current.draftId?.let { runCatching { store(current.context).read(current.game, it) }.getOrNull() }
            ?.takeIf { it.optString("source") == "pending_launch" }
        current.report = draft?.apply {
            put("launchId", current.token); put("startedAtMs", current.startedAt); put("captureStartedAtMs", current.startedAt)
            put("state", "recording"); put("source", source); put("setup", CodexDebugReportStore.safe(current.setup))
            remove("endReason"); remove("endedAtMs")
        } ?: store(current.context).create(current.game, current.token, current.startedAt, problem, current.setup, source)
        store(current.context).save(current.report!!)
        GameOptimizationSession.stop(current.game, "Felsökningsinsamling startades under jämförelsemätningen.")
        saveError = null
        mutableStatus.value = Status(current.game, current.report!!.getString("id"), true, 0, problem)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && launch === current && current.report?.optString("state") == "recording") {
                delay(3000)
                mutex.withLock { runCatching { checkpointLocked(current) }.onFailure(::recordError) }
            }
        }
        return current.report!!.getString("id")
    }

    suspend fun start(context: Context, game: String, problem: DebugProblem): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = requireNotNull(launch?.takeIf { it.game == game && LiveGameSession.view(game)?.token == it.token }) { "Starta spelet först." }
            val id = startLocked(current, problem)
            checkpointLocked(current)
            id
        }
    }

    private fun checkpointLocked(current: Launch, supplied: LiveSessionBuffer.View? = null, suppliedTrace: String? = null) {
        val report = current.report ?: return
        if (report.optString("state") != "recording") return
        val view = (supplied ?: LiveGameSession.view(current.game))?.takeIf { it.token == current.token }
        if (view != null) {
            val lines = report.getJSONArray("lines")
            view.lines.forEach { line ->
                if (line.timestampMs > current.lastLogMs || (line.timestampMs == current.lastLogMs && line.text !in current.lastLogTexts)) {
                    if (line.timestampMs > current.lastLogMs) { current.lastLogMs = line.timestampMs; current.lastLogTexts.clear() }
                    current.lastLogTexts.add(line.text)
                    lines.put(JSONObject().put("timestampMs", line.timestampMs).put("text", line.text))
                }
            }
            var count = 0; var chars = 0; val kept = mutableListOf<JSONObject>()
            for (i in lines.length() - 1 downTo 0) {
                val line = lines.getJSONObject(i); chars += line.optString("text").length
                if (kept.size < CodexDebugReportStore.MAX_LINES && chars <= CodexDebugReportStore.MAX_LOG_CHARS) kept.add(line) else count++
            }
            report.put("lines", JSONArray(kept.asReversed())).put("omittedLines", report.optInt("omittedLines") + count)
                .put("liveBufferOmittedLines", view.omittedLines)
                .put("logNote", "Bounded snapshots of the existing process-output ring every 3 seconds. Bursts can be lost between checkpoints. Identical lines received in one millisecond are collapsed. No Android logcat or typed text.")
            val json = JSONObject(view.json())
            val points = json.getJSONArray("recentSamples")
            val samples = report.getJSONArray("samples")
            for (i in 0 until points.length()) {
                val point = points.getJSONObject(i)
                if (point.getLong("timestampMs") > current.lastSampleMs) {
                    samples.put(point); current.lastSampleMs = point.getLong("timestampMs")
                }
            }
            val start = (samples.length() - CodexDebugReportStore.MAX_SAMPLES).coerceAtLeast(0)
            report.put("samples", JSONArray((start until samples.length()).map { samples.get(it) }))
                .put("omittedSamples", report.optInt("omittedSamples") + start)
                .put("measurementNote", json.getString("measurementNote"))
        }
        val trace = JSONObject(suppliedTrace ?: ControllerInputTrace.read(current.game))
        if (trace.optString("launchId") == current.token) {
            report.put("controller", CodexDebugReportStore.safe(trace)).put("controllerCapturedAtMs", System.currentTimeMillis())
        }
        report.put("updatedAtMs", System.currentTimeMillis())
        store(current.context).save(report)
        saveError = null
        if (launch === current) mutableStatus.value = Status(current.game, report.getString("id"), true, report.getJSONArray("markers").length(), DebugProblem.valueOf(report.getString("problem")))
    }

    suspend fun checkpoint(game: String) = withContext(Dispatchers.IO) { mutex.withLock {
        launch?.takeIf { it.game == game }?.let { checkpointLocked(it) }
    } }

    suspend fun mark(game: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val current = requireNotNull(launch?.takeIf { it.game == game && LiveGameSession.view(game)?.token == it.token })
        val report = requireNotNull(current.report)
        check(report.getString("state") == "recording") { "Insamlingen är avslutad." }
        val markers = report.getJSONArray("markers")
        check(markers.length() < 30) { "Högst 30 markeringar per rapport." }
        markers.put(JSONObject().put("timestampMs", System.currentTimeMillis()).put("label", "Problemet händer nu"))
        checkpointLocked(current)
    } }

    /** Capture before the existing live ring/input observations are torn down. No disk I/O on that path. */
    fun ending(view: LiveSessionBuffer.View?) {
        val current = launch?.takeIf { view != null && it.token == view.token } ?: return
        val trace = ControllerInputTrace.read(current.game)
        current.finalView = view
        current.finalTrace = trace
        scope.launch { mutex.withLock {
            runCatching { finishLocked(current, view, trace, "Spelomgången avslutades.") }.onFailure(::recordError)
        } }
    }

    private fun finishLocked(current: Launch, view: LiveSessionBuffer.View?, trace: String?, reason: String): String? {
        val report = current.report ?: return null
        if (report.optString("state") == "recording") {
            checkpointLocked(current, view ?: current.finalView, trace ?: current.finalTrace)
            report.put("state", "ready").put("endedAtMs", System.currentTimeMillis()).put("endReason", reason)
            try { store(current.context).save(report) } catch (error: Exception) { report.put("state", "recording"); throw error }
        }
        if (launch === current) mutableStatus.value = Status(current.game, report.getString("id"), false, report.getJSONArray("markers").length(), DebugProblem.valueOf(report.getString("problem")))
        if (launch === current) ticker?.cancel()
        return report.getString("id")
    }

    suspend fun finish(game: String): String? = withContext(Dispatchers.IO) { mutex.withLock {
        val current = launch?.takeIf { it.game == game } ?: return@withLock null
        finishLocked(current, LiveGameSession.view(game), null, "Insamlingen avslutades.")
    } }

    fun launchError(game: String, message: String) {
        if (!BuildConfig.AI_ASSISTANT_ENABLED) return
        val requested = pending?.takeIf { it.game == game }
        val current = launch?.takeIf { it.game == game && LiveGameSession.view(game)?.token == it.token }
        scope.launch { mutex.withLock {
            runCatching {
                if (current?.report != null) {
                    LiveGameSession.line(current.token, "GameNative launch error: $message")
                    checkpointLocked(current)
                } else if (requested != null) {
                    val report = store(requested.context).read(game, requested.reportId)
                    report.getJSONArray("lines").put(JSONObject().put("timestampMs", System.currentTimeMillis())
                        .put("text", DiagnosticRedactor.text("GameNative launch error: $message").take(4000)))
                    report.put("state", "ready").put("endedAtMs", System.currentTimeMillis()).put("endReason", "Starten misslyckades före observerad spelprocess.")
                    store(requested.context).save(report)
                    if (pending === requested) pending = null
                }
            }.onFailure(::recordError)
        } }
    }

    suspend fun cancelRequest(game: String) = withContext(NonCancellable + Dispatchers.IO) { mutex.withLock {
        val requested = pending?.takeIf { it.game == game } ?: return@withLock
        pending = null
        runCatching {
            val report = store(requested.context).read(game, requested.reportId)
            report.put("state", "interrupted").put("endedAtMs", System.currentTimeMillis()).put("endReason", "Spelstarten avbröts före insamling.")
            store(requested.context).save(report)
        }.onFailure(::recordError)
    } }

    suspend fun reports(context: Context, game: String): List<JSONObject> = withContext(Dispatchers.IO) { mutex.withLock {
        val store = store(context)
        store.recover(LiveGameSession.token())
        store.list(game)
    } }

    suspend fun frozen(context: Context, game: String, id: String): JSONObject = withContext(Dispatchers.IO) { mutex.withLock {
        launch?.takeIf { it.game == game && it.report?.optString("id") == id }?.let { checkpointLocked(it) }
        store(context).read(game, id)
    } }

    /** Explicit local import only. Old reports never inherit consent to send them to a new provider. */
    suspend fun importLegacy(context: Context, game: String): String = withContext(Dispatchers.IO) { mutex.withLock {
        val legacyRoot = DebugReportUtils.reportsDir(context).canonicalFile
        val legacy = legacyRoot.listFiles().orEmpty().filter {
            it.isDirectory && it.name.startsWith("${game}_") && it.canonicalFile == File(legacyRoot, it.name)
        }.sortedByDescending { it.lastModified() }.firstOrNull {
            runCatching {
                val header = DebugReportUtils.headerFile(it)
                require(header.canonicalFile == header.absoluteFile)
                JSONObject(header.inputStream().use { input -> input.readBytesBounded(2_000_000) }.toString(Charsets.UTF_8)).optString("appId") == game
            }.getOrDefault(false)
        } ?: error("Ingen äldre lokal debugrapport finns för detta spel.")
        val header = JSONObject(DebugReportUtils.headerFile(legacy).inputStream().use { it.readBytesBounded(2_000_000) }.toString(Charsets.UTF_8))
        val log = DebugReportUtils.logFile(legacy)
        require(log.canonicalFile == log.absoluteFile)
        val text = if (log.isFile) GZIPInputStream(log.inputStream()).use { it.readBytesBounded(8 * 1024 * 1024 + 1024).toString(Charsets.UTF_8) } else ""
        val report = store(context).create(game, "legacy-unknown", legacy.lastModified(), DebugProblem.OTHER,
            JSONObject().put("configuration", CodexDebugReportStore.configuration(header.optJSONObject("configs") ?: JSONObject()))
                .put("configurationOrigin", "Legacy snapshot collected after exit; launch identity/settings pairing cannot be verified"), "legacy_import")
        val safe = DiagnosticRedactor.text(text).takeLast(CodexDebugReportStore.MAX_LOG_CHARS)
        report.put("lines", JSONArray(safe.lines().takeLast(CodexDebugReportStore.MAX_LINES).map {
            JSONObject().put("timestampMs", legacy.lastModified()).put("text", it.take(4000))
        })).put("state", "ready").put("endedAtMs", legacy.lastModified())
            .put("logNote", "Explicit legacy import. Original line timestamps and complete output are unavailable; logs may be truncated. Performance pairing is unverified.")
        store(context).save(report)
        report.getString("id")
    } }
}
