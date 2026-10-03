package app.gamenative.assistant

import android.os.SystemClock
import app.gamenative.BuildConfig
import app.gamenative.powercontrol.metrics.MetricsSnapshot
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A bounded, in-memory view of one launch. Tokens reject output from older processes/samplers. */
class LiveSessionBuffer(private val wallTime: () -> Long, private val elapsedTime: () -> Long) {
    data class Point(val metrics: MetricsSnapshot, val elapsedMs: Long, val quality: String,
        val assistantVisible: Boolean, val frameSampleStride: Int)
    data class Line(val timestampMs: Long, val text: String)
    data class View(val game: String, val token: String, val startedAtMs: Long, val status: String,
        val sampleAgeMs: Long?, val points: List<Point>, val lines: List<Line>, val omittedLines: Int,
        val debugRun: Boolean, val assistantVisible: Boolean, val paused: Boolean) {
        val current: MetricsSnapshot? get() = points.lastOrNull()?.metrics?.takeIf { status == "live" }
        fun json(): String {
            fun number(value: Float?): Any = value?.takeIf { it.isFinite() } ?: JSONObject.NULL
            fun point(p: Point) = JSONObject().put("timestampMs", p.metrics.timestampMs).put("quality", p.quality)
                .put("assistantPanelVisible", p.assistantVisible).put("frameSampleStride", p.frameSampleStride)
                .put("fps", if (p.quality == "live") number(p.metrics.fps) else JSONObject.NULL)
                .put("frameTimeP50Ms", if (p.quality == "live") number(p.metrics.frameTimeP50Ms) else JSONObject.NULL)
                .put("frameTimeP95Ms", if (p.quality == "live") number(p.metrics.frameTimeP95Ms) else JSONObject.NULL)
                .put("frameTimeMaxMs", if (p.quality == "live") number(p.metrics.frameTimeMaxMs) else JSONObject.NULL)
                .put("frameIntervals", p.metrics.totalFrameCount).put("slowFrameCount", p.metrics.slowFrameCount)
                .put("cpuUsagePercent", number(p.metrics.cpuUsagePercent)).put("cpuUsageSource", p.metrics.cpuUsageSource.name)
                .put("gpuUsagePercent", number(p.metrics.gpuUsagePercent))
                .put("cpuTempC", p.metrics.cpuTempC ?: JSONObject.NULL).put("gpuTempC", p.metrics.gpuTempC ?: JSONObject.NULL)
            return JSONObject().put("available", true).put("game", game).put("sessionId", token)
                .put("startedAtMs", startedAtMs).put("status", status).put("sampleAgeMs", sampleAgeMs ?: JSONObject.NULL)
                .put("current", if (status == "live") points.lastOrNull()?.let(::point) ?: JSONObject.NULL else JSONObject.NULL)
                .put("recentSamples", JSONArray(points.map(::point))).put("assistantPanelVisible", assistantVisible)
                .put("log", JSONObject().put("source", "Current launch's ProcessHelper stdout/stderr; includes Wine/launcher helpers, not a screen capture")
                    .put("debugRun", debugRun).put("omittedLines", omittedLines)
                    .put("lines", JSONArray(lines.map { JSONObject().put("timestampMs", it.timestampMs).put("text", it.text) }))
                    .put("note", "Normal launches may emit very little output. No log lines does not prove there was no error."))
                .put("measurementNote", "Read at tool-call time. Existing render-hook metrics sampled every 500 ms over overlapping 2-second windows; up to 30 seconds retained. CPU/GPU sensors can be unavailable; CPU is not necessarily the game's process. Frame stride may adjust for frame generation. Opening chat can affect performance. Paused/warm-up/no-frame samples are not gameplay FPS. Recent samples are historical if status is stale. This is not a controlled before/after benchmark; no screen, game memory or automatic continuous AI monitoring.")
                .toString()
        }
    }
    private data class Session(val game: String, val token: String, val startedAtMs: Long,
        var resumedAt: Long, val debugRun: Boolean, var paused: Boolean = false, var collecting: Boolean = true,
        var assistantVisible: Boolean = false, var privateKey: Boolean = false, var omitted: Int = 0,
        var burstSecond: Long = -1, var burstCount: Int = 0,
        val points: ArrayDeque<Point> = ArrayDeque(), val lines: ArrayDeque<Line> = ArrayDeque())
    private var active: Session? = null

    @Synchronized fun begin(game: String, debugRun: Boolean = false): String {
        require(game.matches(Regex("[A-Za-z0-9_-]{1,160}")))
        val token = UUID.randomUUID().toString()
        active = Session(game, token, wallTime(), elapsedTime(), debugRun)
        return token
    }
    @Synchronized fun token(): String? = active?.token
    @Synchronized fun activeView(): View? = active?.game?.let(::view)
    @Synchronized fun end() { active = null }
    @Synchronized fun paused(token: String?, paused: Boolean) {
        val session = active?.takeIf { token != null && it.token == token } ?: return
        if (session.paused && !paused) session.resumedAt = elapsedTime()
        session.paused = paused
    }
    @Synchronized fun collecting(token: String?, collecting: Boolean) {
        val session = active?.takeIf { token != null && it.token == token } ?: return
        if (!session.collecting && collecting) session.resumedAt = elapsedTime()
        session.collecting = collecting
    }
    @Synchronized fun assistant(game: String, visible: Boolean) { active?.takeIf { it.game == game }?.assistantVisible = visible }
    @Synchronized fun metrics(token: String?, metrics: MetricsSnapshot, stride: Int = 1) {
        val session = active?.takeIf { token != null && it.token == token } ?: return
        if (metrics.timestampMs < session.startedAtMs || metrics.timestampMs > wallTime() + 1000 ||
            wallTime() - metrics.timestampMs > 2500) return
        val now = elapsedTime()
        val quality = when {
            session.paused -> "paused"
            !session.collecting -> "background"
            now - session.resumedAt < 2000 -> "warming_up"
            metrics.totalFrameCount <= 0 || !metrics.fps.isFinite() || metrics.fps <= 0 -> "no_frames"
            else -> "live"
        }
        session.points.addLast(Point(metrics, now, quality, session.assistantVisible, stride))
        while (session.points.size > 60 || session.points.firstOrNull()?.let { now - it.elapsedMs > 30_000 } == true) session.points.removeFirst()
    }
    @Synchronized fun line(token: String?, raw: String) {
        val session = active?.takeIf { token != null && it.token == token } ?: return
        // Redact key blocks before ring eviction can remove the BEGIN marker. Oversized lines are dropped whole.
        val beginsKey = raw.contains("-----BEGIN") && raw.contains("PRIVATE KEY-----")
        val endsKey = raw.contains("-----END") && raw.contains("PRIVATE KEY-----")
        val hiddenKey = session.privateKey || beginsKey
        if (beginsKey) session.privateKey = true
        if (endsKey) session.privateKey = false
        if (hiddenKey || raw.length > 4096) { session.omitted++; return }
        val second = elapsedTime() / 1000
        if (session.burstSecond != second) { session.burstSecond = second; session.burstCount = 0 }
        if (++session.burstCount > 100) { session.omitted++; return }
        val text = DiagnosticRedactor.text(raw).trim()
        if (text.isBlank()) return
        session.lines.addLast(Line(wallTime(), text))
        while (session.lines.size > 160 || session.lines.sumOf { it.text.length } > 24_000) {
            session.lines.removeFirst(); session.omitted++
        }
    }
    @Synchronized fun view(game: String): View? {
        val session = active?.takeIf { it.game == game } ?: return null
        val now = elapsedTime()
        val last = session.points.lastOrNull()
        val age = last?.let { (now - it.elapsedMs).coerceAtLeast(0) }
        val status = when {
            session.paused -> "paused"
            !session.collecting -> "background"
            last == null -> "waiting"
            age!! > 2500 -> "stale"
            now - session.resumedAt < 2000 || last.elapsedMs < session.resumedAt -> "warming_up"
            else -> last.quality
        }
        return View(game, session.token, session.startedAtMs, status, age,
            session.points.filter { now - it.elapsedMs <= 30_000 }, session.lines.toList(), session.omitted,
            session.debugRun, session.assistantVisible, session.paused)
    }
}

/** Hooks are inert in the upstream build. No network, disk persistence, extra sampler or new debug flags. */
object LiveGameSession {
    internal val buffer = LiveSessionBuffer(System::currentTimeMillis, SystemClock::elapsedRealtime)
    @JvmStatic fun begin(game: String, debugRun: Boolean) {
        if (BuildConfig.AI_ASSISTANT_ENABLED) { GameOptimizationSession.end(); LiveControllerChanges.clear(); ControllerInputTrace.buffer.reset(); buffer.begin(game, debugRun) }
    }
    @JvmStatic fun token() = buffer.token()
    @JvmStatic fun line(token: String?, line: String) { buffer.line(token, line) }
    @JvmStatic fun paused(token: String?, paused: Boolean) {
        buffer.paused(token, paused)
        if (paused) GameOptimizationSession.interrupt(token, "Spelet pausades.")
    }
    fun collecting(token: String?, collecting: Boolean) {
        if (!collecting) ControllerInputTrace.background(token)
        if (!collecting) GameOptimizationSession.interrupt(token, "Appen gick i bakgrunden eller mätningen pausades.")
        buffer.collecting(token, collecting)
    }
    fun metrics(token: String?, snapshot: MetricsSnapshot, stride: Int) {
        buffer.metrics(token, snapshot, stride)
        GameOptimizationSession.sample(token, snapshot, stride)
    }
    fun assistant(game: String, visible: Boolean) {
        buffer.assistant(game, visible)
        if (visible) GameOptimizationSession.stop(game, "Chatten öppnades under mätningen.")
    }
    fun end() {
        ControllerInputTrace.endLaunch(token())
        buffer.activeView()?.let(CodexDebugSession::ending)
        GameOptimizationSession.end(); buffer.end(); LiveControllerChanges.clear()
    }
    fun view(game: String) = buffer.view(game)
    fun read(game: String) = view(game)?.json() ?: """{"available":false,"note":"No active launch for the selected game. Historical reports are separate; start the game to read live data."}"""
}
