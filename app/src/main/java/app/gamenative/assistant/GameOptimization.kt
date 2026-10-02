package app.gamenative.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import app.gamenative.powercontrol.metrics.MetricsSnapshot

/** Local, game-scoped goals and explicitly recorded experiments. Never edits game configuration. */
class GameOptimizationStore(private val directory: File, private val game: String) {
    init { require(game.matches(Regex("[A-Za-z0-9_-]{1,160}"))) }
    private val file get() = File(directory, "$game.json")
    fun read(): JSONObject = synchronized(lock) {
        if (!file.exists()) return@synchronized JSONObject().put("schema", 1).put("game", game)
            .put("targetFps", 30).put("scene", "").put("runs", JSONArray())
        check(file.length() <= 512_000) { "Optimeringshistoriken är för stor. Originalet har bevarats." }
        JSONObject(file.readText()).also {
            check(it.getInt("schema") == 1 && it.getString("game") == game) { "Ogiltig optimeringshistorik. Originalet har bevarats." }
        }
    }
    fun goal(target: Int, scene: String) = synchronized(lock) {
        require(target in targets && scene.length <= 120)
        write(read().put("targetFps", target).put("scene", DiagnosticRedactor.text(scene).trim().take(120)))
    }
    fun add(run: JSONObject) = synchronized(lock) {
        require(run.getString("game") == game && run.getString("id").matches(Regex("[a-f0-9-]{36}")))
        val data = read()
        val old = data.getJSONArray("runs")
        val runs = (0 until old.length()).map { old.getJSONObject(it) }.filterNot { it.getString("id") == run.getString("id") } + run
        if (!data.has("baselineId") && run.getBoolean("usable")) data.put("baselineId", run.getString("id"))
        val baseline = data.optString("baselineId")
        val keep = (runs.filter { it.getString("id") == baseline } + runs.filterNot { it.getString("id") == baseline }.takeLast(7))
            .sortedBy { it.getLong("recordedAtMs") }
        write(data.put("runs", JSONArray(keep)))
    }
    fun baseline(id: String) = synchronized(lock) {
        val data = read()
        val runs = data.getJSONArray("runs")
        require((0 until runs.length()).any { runs.getJSONObject(it).let { r -> r.getString("id") == id && r.getBoolean("usable") } })
        write(data.put("baselineId", id))
    }
    private fun write(data: JSONObject) { ConfigTransaction.atomicWrite(file, data.toString().toByteArray()) }

    companion object {
        val targets = listOf(30, 40, 60)
        private val lock = Any()

        /** Exclude session bookkeeping, but hash hidden settings too without disclosing credentials. */
        fun fingerprint(config: JSONObject): String {
            val source = JSONObject(config.toString()).apply { remove("sessionMetadata"); remove("name"); remove("configSource"); remove("rcfileId") }
            fun canonical(value: Any): String = when (value) {
                is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { "${JSONObject.quote(it)}:${canonical(value.get(it))}" }
                is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
                is String -> JSONObject.quote(value)
                is Number -> JSONObject.numberToString(value)
                is Boolean -> value.toString()
                else -> "null"
            }
            return AssistantProtocol.sha256(canonical(source).toByteArray())
        }

        fun comparison(data: JSONObject): JSONObject {
            val runs = data.getJSONArray("runs")
            val list = (0 until runs.length()).map { runs.getJSONObject(it) }
            val baseline = list.firstOrNull { it.getString("id") == data.optString("baselineId") }
            val latest = list.lastOrNull()
            val issues = mutableListOf<String>()
            if (baseline == null || latest == null || baseline.getString("id") == latest.getString("id")) issues += "Två separata mätningar behövs."
            else {
                if (!baseline.getBoolean("usable") || !latest.getBoolean("usable")) issues += "Minst en mätning är avbruten eller har otillräckliga data."
                if (baseline.getInt("targetFps") != latest.getInt("targetFps")) issues += "Olika FPS-mål."
                if (baseline.getString("scene") != latest.getString("scene")) issues += "Olika testscener."
                if (baseline.getString("environment") != latest.getString("environment")) issues += "Olika enhet, appversion, bildskärms- eller strömförhållanden."
                for (sensor in listOf("startCpuTempC", "startGpuTempC")) {
                    val before = baseline.getJSONObject("summary").optInt(sensor, -1)
                    val after = latest.getJSONObject("summary").optInt(sensor, -1)
                    if (before >= 0 && after >= 0 && kotlin.math.abs(before - after) > 5) issues += "Starttemperaturen ($sensor) skiljer mer än 5 °C."
                }
            }
            val result = JSONObject().put("eligible", issues.isEmpty()).put("issues", JSONArray(issues))
                .put("baselineId", baseline?.optString("id") ?: JSONObject.NULL).put("latestId", latest?.optString("id") ?: JSONObject.NULL)
                .put("limits", "Matched metadata and the user's scene label are not proof of identical gameplay, game version, in-game settings, mod files, brightness, thermal or power conditions. Missing sensors remain unknown. Repeat the same route/savestate under the same conditions before claiming an improvement. Frame-hook window statistics, not whole-run percentiles or a guarantee of stable FPS.")
            if (issues.isEmpty()) {
                val a = baseline!!.getJSONObject("summary"); val b = latest!!.getJSONObject("summary")
                result.put("meanWindowFpsDelta", b.getDouble("meanWindowFps") - a.getDouble("meanWindowFps"))
                    .put("meanWindowP95MsDelta", b.getDouble("meanWindowP95Ms") - a.getDouble("meanWindowP95Ms"))
                    .put("configurationChanged", baseline.getString("configurationId") != latest.getString("configurationId"))
            }
            return result
        }
    }
}

/** 5 seconds to return to gameplay, then a fixed 60-second observation. No callbacks into input/rendering. */
class OptimizationCapture(private val clock: () -> Long, private val wall: () -> Long) {
    data class View(val game: String, val active: Boolean, val countdown: Int, val secondsLeft: Int, val samples: Int, val result: String?)
    private data class Run(val game: String, val launch: String, val target: Int, val scene: String,
        val configuration: String, val settings: String, val environment: String, val started: Long, val recorded: Long,
        var last: Long = Long.MIN_VALUE, var rejected: Int = 0, var strideChanged: Boolean = false,
        var result: String? = null, val samples: MutableList<MetricsSnapshot> = mutableListOf())
    private var run: Run? = null
    @Synchronized fun start(game: String, launch: String, target: Int, scene: String, configuration: String, settings: String, environment: String) {
        require(game.matches(Regex("[A-Za-z0-9_-]{1,160}")) && target in GameOptimizationStore.targets && scene.isNotBlank() && scene.length <= 120)
        check(run?.result != null || run == null) { "En mätning pågår redan." }
        run = Run(game, launch, target, scene, configuration, settings, environment, clock(), wall())
    }
    @Synchronized fun sample(launch: String?, metrics: MetricsSnapshot, validGameplay: Boolean, stride: Int) {
        val r = run?.takeIf { it.launch == launch && it.result == null } ?: return
        val elapsed = clock() - r.started
        if (elapsed in 5000..65_000 && stride != 1) r.strideChanged = true
        // Each collector value covers the PREVIOUS two seconds. Do not include countdown/menu time.
        if (elapsed < 7000 || elapsed > 65_000 || r.last != Long.MIN_VALUE && clock() - r.last < 2000) return
        // Collector delivery is synchronous. Space windows on the monotonic clock, not wall time
        // (which may be adjusted by Android). Wall time below is only a freshness guard.
        if (!validGameplay || stride != 1 || metrics.timestampMs > wall() + 1000 || wall() - metrics.timestampMs > 1000 ||
            !metrics.fps.isFinite() || metrics.fps <= 0 || !metrics.frameTimeP95Ms.isFinite() || metrics.frameTimeP95Ms < 0 ||
            !metrics.frameTimeMaxMs.isFinite() || metrics.frameTimeMaxMs < 0 || metrics.totalFrameCount <= 0) { r.rejected++; return }
        r.last = clock()
        if (r.samples.size < 30) r.samples += metrics
    }
    @Synchronized fun finish(reason: String, configurationStable: Boolean = true, environmentStable: Boolean = true): String? {
        val r = run?.takeIf { it.result == null } ?: return null
        val s = r.samples
        fun mean(read: (MetricsSnapshot) -> Float?) = s.mapNotNull(read).filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.average()
        val summary = JSONObject().put("windows", s.size).put("observedWindowSeconds", s.size * 2)
            .put("meanWindowFps", mean { it.fps } ?: JSONObject.NULL)
            .put("meanWindowP95Ms", mean { it.frameTimeP95Ms } ?: JSONObject.NULL)
            .put("worstFrameMs", s.maxOfOrNull { it.frameTimeMaxMs } ?: JSONObject.NULL)
            .put("windowsNearTargetPercent", if (s.isEmpty()) JSONObject.NULL else 100.0 * s.count { it.fps >= r.target * 0.97 } / s.size)
            .put("meanCpuPercent", mean { it.cpuUsagePercent } ?: JSONObject.NULL).put("meanGpuPercent", mean { it.gpuUsagePercent } ?: JSONObject.NULL)
            .put("startCpuTempC", s.firstOrNull()?.cpuTempC ?: JSONObject.NULL).put("startGpuTempC", s.firstOrNull()?.gpuTempC ?: JSONObject.NULL)
            .put("peakTemperatureC", s.flatMap { listOfNotNull(it.cpuTempC, it.gpuTempC) }.maxOrNull() ?: JSONObject.NULL)
        val issues = mutableListOf<String>()
        if (reason != "completed") issues += reason
        if (clock() - r.started < 65_000) issues += "Mätningen avslutades före 60 sekunder."
        if (s.size < 27) issues += "Färre än 54 av 60 sekunder har giltiga mätfönster."
        if (r.strideChanged) issues += "Bildgenerering/annan mätstride ingick; denna jämförelse kräver stride 1."
        if (!configurationStable) issues += "Sparad konfiguration ändrades efter spelstart."
        if (!environmentStable) issues += "Ström- eller bildskärmsförhållanden ändrades under mätningen."
        return JSONObject().put("id", UUID.randomUUID().toString()).put("game", r.game).put("launchId", r.launch)
            .put("targetFps", r.target).put("scene", r.scene).put("configurationId", r.configuration).put("settings", JSONObject(r.settings))
            .put("environment", r.environment).put("recordedAtMs", r.recorded).put("durationMs", (clock() - r.started - 5000).coerceIn(0, 60_000))
            .put("usable", issues.isEmpty()).put("issues", JSONArray(issues)).put("rejectedWindows", r.rejected).put("summary", summary)
            .put("method", "v1: fixed 60s, 5s countdown, 2s render-hook windows sampled at least 2s apart on the monotonic clock; mean FPS and MEAN of window p95, not whole-run p95. At least 27 windows required. No input injection or automatic configuration changes. Missing CPU/GPU sensors are unknown; CPU may represent the whole device.")
            .toString().also { r.result = it }
    }
    @Synchronized fun due() = run?.let { it.result == null && clock() - it.started >= 67_000 } == true
    @Synchronized fun view(game: String): View? = run?.takeIf { it.game == game }?.let {
        val elapsed = clock() - it.started
        View(game, it.result == null, ((5000 - elapsed).coerceAtLeast(0) + 999).toInt() / 1000,
            ((65_000 - elapsed).coerceAtLeast(0) + 999).toInt() / 1000, it.samples.size, it.result)
    }
    @Synchronized fun reset() { run = null }
}
