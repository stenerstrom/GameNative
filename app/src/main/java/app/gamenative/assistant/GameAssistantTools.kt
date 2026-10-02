package app.gamenative.assistant

import android.content.Context
import android.os.Build
import android.view.InputDevice
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.DebugReportUtils
import app.gamenative.utils.DiagnosticsLog
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The game ID is fixed by the Android screen, never supplied by the model. */
class GameAssistantTools(private val context: Context, private val appId: String, private val gameTitle: String = appId) : GameAssistantAgent.Tools {
    init { require(appId.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "Invalid game ID" } }
    data class Snapshot(val hash: String, val text: String)
    var preparedHash: String? = null
        private set
    var preparedChanges: List<String> = emptyList()
        private set
    private var inspectedHash: String? = null
    override var fileAccess: Boolean = false
    private val textFiles = GameTextFiles({ GameFileRoots.discover(context, appId) },
        File(context.noBackupFilesDir, "assistant/file-undo/$appId.json"))

    fun beginTurn() { inspectedHash = null; preparedHash = null; preparedChanges = emptyList(); textFiles.beginTurn() }
    override suspend fun readFileTool(name: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        check(fileAccess) { "File access is disabled" }
        val key = when (name) { "list_game_files" -> "query"; "read_game_file" -> "file_id"; else -> error("Unsupported file tool") }
        require(arguments.keys().asSequence().toSet() == setOf(key) && arguments.get(key) is String) { "Invalid file tool arguments" }
        if (name == "list_game_files") textFiles.list(arguments.getString(key)) else textFiles.read(arguments.getString(key))
    }
    override suspend fun prepareFile(proposal: FileEditProposal): GameTextFiles.Preview = withContext(Dispatchers.IO) {
        check(fileAccess) { "File access is disabled" }
        check(!hasBackup()) { "Restore or keep the previous change first" }
        textFiles.prepare(proposal)
    }
    override suspend fun read(name: String): String {
        if (name == "inspect_controllers") return withContext(Dispatchers.Main) {
            val manager = com.winlator.inputcontrols.ControllerManager.getInstance()
            val devices = JSONArray()
            InputDevice.getDeviceIds().take(64).mapNotNull { InputDevice.getDevice(it) }.filter {
                !it.isVirtual && (it.supportsSource(InputDevice.SOURCE_GAMEPAD) || it.supportsSource(InputDevice.SOURCE_JOYSTICK))
            }.forEach { device ->
                val slot = manager.getSlotForDevice(device.id)
                devices.put(JSONObject().put("name", DiagnosticRedactor.text(device.name).take(120))
                    .put("player", if (slot in 0..3) slot + 1 else JSONObject.NULL))
            }
            JSONObject().put("detectedControllers", devices)
                .put("enabledPlayerSlots", JSONArray((0..3).filter { manager.isSlotEnabled(it) }.map { it + 1 }))
                .put("note", "Android detection and current GameNative slot state only. This does not test buttons inside the game or inspect Bluetooth pairing. No serials, descriptors or addresses included.").toString()
        }
        return withContext(Dispatchers.IO) {
            require(name in setOf("read_configuration", "read_game_log", "read_performance"))
            val snapshot = readDiagnostics()
            val data = JSONObject(snapshot.text)
            val result = JSONObject().put("game", appId).put("title", DiagnosticRedactor.text(gameTitle).take(200))
            val keys = when (name) {
                "read_configuration" -> {
                    inspectedHash = snapshot.hash
                    result.put("undoAvailable", hasBackup())
                    listOf("device", "android", "configuration", "editableSettings")
                }
                "read_game_log" -> listOf("logModifiedAtMs", "log")
                else -> listOf("lastSessionAverageFps", "lastSessionSeconds", "measurementNote", "performance")
            }
            keys.forEach { result.put(it, data.opt(it)) }
            DiagnosticRedactor.text(result.toString())
        }
    }
    override suspend fun prepare(proposal: ConfigProposal): List<String> = withContext(Dispatchers.IO) {
        check(!hasBackup()) { "A previous change still has an undo backup. The user must restore it or keep it before another change." }
        val hash = requireNotNull(inspectedHash) { "Read configuration first" }
        transaction().preview(hash, proposal).also { preparedHash = hash; preparedChanges = it }
    }

    fun readDiagnostics(): Snapshot {
        val container = ContainerUtils.getContainer(context, appId)
        val bytes = container.configFile.readBytes()
        val config = JSONObject(bytes.toString(Charsets.UTF_8))
        val safeConfig = JSONObject()
        listOf("screenSize", "graphicsDriver", "graphicsDriverVersion", "dxwrapper", "dxwrapperConfig", "displayRendererMode",
            "rendererPresentMode", "containerVariant", "wineVersion", "emulator", "box64Version", "box64Preset", "fexcoreVersion").forEach {
            if (config.has(it)) safeConfig.put(it, DiagnosticRedactor.text(config.get(it).toString()).take(1000))
        }
        val extras = config.optJSONObject("extraData")
        listOf("fpsLimiterEnabled", "fpsLimiterTarget", "lsfgEnabled").forEach {
            if (extras?.has(it) == true) safeConfig.put(it, extras.get(it).toString().take(30))
        }
        // Use GameNative's loader defaults for omitted legacy fields. Unknown enum values stay unknown.
        safeConfig.put("controller", JSONObject()
            .put("inputApi", ControllerInputApi.entries.firstOrNull { it.nativeApi.ordinal == container.inputType }?.name ?: JSONObject.NULL)
            .put("directInputMapper", DirectInputMapper.entries.firstOrNull { it.storedValue == container.dinputMapperType.toInt() }?.name ?: JSONObject.NULL)
            .put("sdlControllerAPI", container.isSdlControllerAPI)
            .put("useSteamInput", container.getExtra("useSteamInput", "false").toBoolean())
            .put("disableMouseInput", container.isDisableMouseInput)
            .put("note", "Saved settings for the next launch. AUTO is automatic API selection, not disabled. Connected devices, player-slot assignments, on-screen profiles and in-game controller behavior have not been inspected."))
        val report = DebugReportUtils.reportsDir(context).listFiles()?.filter { it.isDirectory && it.name.startsWith("${appId}_") }
            ?.sortedByDescending { it.lastModified() }?.firstOrNull {
                runCatching { JSONObject(readSmall(File(it, "header.json"), 2_000_000)).optString("appId") == appId }.getOrDefault(false)
            }
        val candidates = listOfNotNull(DebugReportUtils.wineLogFile(context, appId), DiagnosticsLog.file(context, appId),
            report?.let { DebugReportUtils.logFile(it) }).filter { it.isFile && it.length() > 0 }
        val log = candidates.maxByOrNull { it.lastModified() }
        val logText = if (log == null) "No game-specific log available. Chat and configuration analysis are still available. An optional AI debug run can collect a log if needed." else runCatching {
            val raw = if (log.extension == "gz") GZIPInputStream(log.inputStream()).use { readBounded(it, 8 * 1024 * 1024 + 1024) }
                else readSmall(log, 8 * 1024 * 1024 + 1024)
            // Sanitize complete bounded input before taking the tail; never cut a credential before filtering it.
            DiagnosticRedactor.text(raw).takeLast(24_000)
        }.getOrElse { "Log unavailable or exceeds the 8 MiB safety limit. Capture a shorter run." }
        val result = JSONObject().put("game", appId).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE).put("configuration", safeConfig)
            .put("editableSettings", GameSettingCatalog.describe(config))
            .put("logModifiedAtMs", log?.lastModified() ?: JSONObject.NULL).put("log", logText)
        val session = config.optJSONObject("sessionMetadata")
        result.put("lastSessionAverageFps", session?.optString("avg_fps")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        result.put("lastSessionSeconds", session?.optString("session_length_sec")?.toIntOrNull() ?: JSONObject.NULL)
        result.put("measurementNote", "Last-session metadata and older logs may come from different runs/settings. These are not a before/after benchmark.")
        result.put("performance", report?.let { readPerformance(it) } ?: "No matching saved debug performance report")
        return Snapshot(AssistantProtocol.sha256(bytes), result.toString(2))
    }

    private val backupFile: File get() = File(context.noBackupFilesDir, "assistant/undo/$appId.json")
    override fun hasBackup(): Boolean = backupFile.isFile || textFiles.hasBackup()
    fun hasFileBackup(): Boolean = textFiles.hasBackup()
    fun keepChanges() = synchronized(changeLock) {
        checkStopped(); checkSingleBackup()
        if (hasFileBackup()) textFiles.keep() else transaction().keep()
    }
    fun applyValidated(proposal: ConfigProposal, snapshotHash: String) {
        synchronized(changeLock) {
            checkStopped()
            check(!hasBackup()) { "Restore or keep the previous change first" }
            transaction().apply(snapshotHash, proposal)
        }
    }
    fun applyFile() = synchronized(changeLock) {
        check(fileAccess) { "File access is disabled" }
        checkStopped()
        check(!hasBackup()) { "Restore or keep the previous change first" }
        textFiles.apply()
    }
    fun restore() = synchronized(changeLock) {
        checkStopped(); checkSingleBackup()
        if (hasFileBackup()) textFiles.restore() else transaction().restore()
    }
    private fun checkSingleBackup() {
        check(!(backupFile.isFile && textFiles.hasBackup())) { "Conflicting undo records; both backups are retained" }
    }
    private fun checkStopped() {
        check(!SteamService.keepAlive) { "Stop the game/container before changing or restoring settings/files" }
    }
    private fun transaction(): ConfigTransaction = ConfigTransaction(ContainerUtils.getContainer(context, appId).configFile,
        backupFile)

    companion object { private val changeLock = Any() }

    private fun readPerformance(report: File): Any = runCatching {
        val source = JSONObject(readSmall(DebugReportUtils.perfFile(report), 2_000_000))
        val safe = JSONObject().put("reportModifiedAtMs", report.lastModified())
        listOf("intervalMs", "runLengthSec", "refreshRateHz", "totalMemMb").forEach { key ->
            (source.opt(key) as? Number)?.let { safe.put(key, it) }
        }
        val samples = source.optJSONArray("samples") ?: JSONArray()
        val selected = JSONArray()
        for (i in (samples.length() - 20).coerceAtLeast(0) until samples.length()) {
            val sample = samples.getJSONObject(i)
            val fields = JSONObject()
            listOf("t", "fps", "cpu", "iow", "gpu", "gfreq", "tc", "tb", "th", "hr").forEach { key ->
                (sample.opt(key) as? Number)?.let { fields.put(key, it) }
            }
            sample.optJSONArray("ft")?.let { times ->
                if (times.length() == 3 && (0..2).all { times.opt(it) is Number }) fields.put("frameTimeP50P99MaxMs", times)
            }
            selected.put(fields)
        }
        safe.put("last20Samples", selected)
    }.getOrElse { "Performance report unavailable or too large" }

    private fun readSmall(file: File, limit: Int): String {
        require(file.length() <= limit) { "File too large" }
        return file.inputStream().use { readBounded(it, limit) }
    }
    private fun readBounded(input: InputStream, limit: Int): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit + 1) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= limit) { "File too large" }
        return bytes.toString(Charsets.UTF_8)
    }
}
