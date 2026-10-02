package app.gamenative.assistant

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import com.winlator.inputcontrols.ControllerManager
import com.winlator.inputcontrols.GamepadState
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Explicit, bounded controller observations. Never a keyboard recorder; no files or network. */
class ControllerTraceBuffer(private val clock: () -> Long) {
    enum class Stage { ANDROID, PROFILE_BINDING, WINE_BUFFER, WINE_BUFFER_UNAVAILABLE, NATIVE_WAKE }
    enum class Mode(val durationMs: Long) { TEST(20_000), LIVE(300_000) }
    data class Sample(val atMs: Long, val stage: Stage, val device: Int?, val player: Int?,
        val values: Map<String, Float>, val route: String)
    data class LegacyClient(val port: Int, val processId: Int?, val api: String, val allowed: Boolean,
        val discoveryReplies: Int = 0, val statePackets: Int = 0, val sendFailures: Int = 0, val lastAtMs: Long)
    private data class Range(var count: Int, var min: Float, var max: Float, var last: Float)
    private data class Probe(val game: String, val launch: String, val id: String, val started: Long, val mode: Mode,
        var ended: Long? = null, var reason: String? = null, var omitted: Int = 0,
        val samples: ArrayDeque<Sample> = ArrayDeque(), val ranges: LinkedHashMap<String, Range> = linkedMapOf(),
        val counts: MutableMap<Stage, Int> = mutableMapOf(), val devices: MutableMap<Int, Int> = linkedMapOf(),
        val latestStages: MutableMap<Stage, Sample> = linkedMapOf(), val clients: MutableMap<Int, LegacyClient> = linkedMapOf())
    data class View(val game: String, val active: Boolean, val secondsLeft: Int, val androidSamples: Int,
        val mappedSamples: Int, val wineSamples: Int, val latest: String?, val json: String,
        val mode: Mode, val elapsedMs: Long, val latestStages: List<Sample>, val clients: List<LegacyClient>)
    private var probe: Probe? = null
    private val controlName = Regex("[A-Za-z0-9_.:+-]{1,90}")
    @Synchronized fun start(game: String, launch: String, mode: Mode = Mode.TEST) {
        require(game.matches(Regex("[A-Za-z0-9_-]{1,160}")) && launch.isNotBlank())
        probe = Probe(game, launch, UUID.randomUUID().toString(), clock(), mode)
    }
    @Synchronized fun reset() { probe = null }
    private fun expire(p: Probe) {
        if (p.ended == null && clock() - p.started >= p.mode.durationMs) { p.ended = p.started + p.mode.durationMs; p.reason = "duration_complete" }
    }
    @Synchronized fun finish(launch: String?, reason: String) {
        val p = probe?.takeIf { it.launch == launch } ?: return
        expire(p)
        if (p.ended == null) { p.ended = clock(); p.reason = reason }
    }
    @Synchronized fun accepts(launch: String?): Boolean {
        val p = probe?.takeIf { it.launch == launch } ?: return false
        expire(p); return p.ended == null
    }
    @Synchronized fun launchFor(game: String): String? = probe?.takeIf { it.game == game && accepts(it.launch) }?.launch
    @Synchronized fun controllerNumber(game: String, device: Int): Int? = probe?.takeIf { it.game == game }?.devices?.get(device)
    @Synchronized fun record(launch: String?, stage: Stage, device: Int?, player: Int?, values: Map<String, Float>, route: String) {
        if (!accepts(launch)) return
        val p = probe!!
        if (values.isEmpty() || values.size > 32 || route.length > 80 || player != null && player !in 1..4) return
        if (values.any { !it.key.matches(controlName) || !it.value.isFinite() || it.value !in -100f..100f }) return
        val number = device?.let {
            if (it !in p.devices && p.devices.size >= 8) { p.omitted++; return }
            p.devices.getOrPut(it) { p.devices.size + 1 }
        }
        p.counts[stage] = (p.counts[stage] ?: 0) + 1
        for ((control, value) in values) {
            val key = "${stage.name}/controller${number ?: "-"}/P${player ?: "-"}/$route/$control"
            val range = p.ranges[key]
            if (range != null) { range.count++; range.min = minOf(range.min, value); range.max = maxOf(range.max, value); range.last = value }
            else if (p.ranges.size < 256) p.ranges[key] = Range(1, value, value, value)
            else p.omitted++
        }
        // Coalesce motion/state repetitions; summary ranges still cover the complete probe.
        val last = p.samples.lastOrNull()
        val now = clock() - p.started
        val sample = Sample(now, stage, number, player, values.toMap(), route)
        p.latestStages[stage] = sample
        if (last != null && last.stage == stage && last.device == number && last.player == player && last.values == values && last.route == route && now - last.atMs < 100) return
        p.samples.addLast(sample)
        while (p.samples.size > 100) { p.samples.removeFirst(); p.omitted++ }
    }
    @Synchronized fun view(game: String, includeDetails: Boolean = true): View? {
        val p = probe?.takeIf { it.game == game } ?: return null
        expire(p)
        return View(game, p.ended == null, ((p.mode.durationMs - (clock() - p.started)).coerceAtLeast(0) + 999).toInt() / 1000,
            p.counts[Stage.ANDROID] ?: 0, p.counts[Stage.PROFILE_BINDING] ?: 0, p.counts[Stage.WINE_BUFFER] ?: 0,
            p.samples.lastOrNull()?.let { "${it.stage.name}: ${it.values.entries.take(3).joinToString { entry -> "${entry.key}=${entry.value}" }}" },
            if (includeDetails) details(p) else "", p.mode, clock() - p.started, p.latestStages.values.toList(), p.clients.values.toList())
    }

    @Synchronized fun legacy(launch: String?, port: Int, processId: Int?, api: String?, allowed: Boolean, sent: Boolean) {
        if (!accepts(launch) || port !in 1..65535 || processId != null && processId <= 0 || api != null && api !in setOf("XInput", "DirectInput")) return
        val p = probe!!
        if (port !in p.clients && p.clients.size >= 16) {
            p.clients.remove(p.clients.minBy { it.value.lastAtMs }.key); p.omitted++
        }
        val old = p.clients[port]?.takeIf { processId == null || it.processId == processId }
            ?: LegacyClient(port, processId, api ?: "unknown", allowed, lastAtMs = 0)
        p.clients[port] = old.copy(processId = processId ?: old.processId, api = api ?: old.api, allowed = allowed,
            discoveryReplies = old.discoveryReplies + if (api != null) 1 else 0,
            statePackets = old.statePackets + if (api == null && sent) 1 else 0,
            sendFailures = old.sendFailures + if (!sent) 1 else 0, lastAtMs = clock() - p.started)
    }

    // The small game overlay only needs counters; serialize the bounded trace when an agent reads it.
    private fun details(p: Probe): String {
        val counts = JSONObject().apply { Stage.entries.forEach { put(it.name, p.counts[it] ?: 0) } }
        return JSONObject().put("available", true).put("game", p.game).put("launchId", p.launch).put("probeId", p.id)
            .put("mode", p.mode.name).put("maxDurationMs", p.mode.durationMs)
            .put("recording", p.ended == null).put("durationMs", (p.ended ?: clock()) - p.started)
            .put("finishedReason", p.reason ?: JSONObject.NULL).put("counts", counts).put("omitted", p.omitted)
            .put("finishedAgeMs", p.ended?.let { clock() - it } ?: JSONObject.NULL)
            .put("latestStages", JSONArray(p.latestStages.values.map { e -> JSONObject().put("stage", e.stage.name)
                .put("ageMs", clock() - p.started - e.atMs).put("controller", e.device ?: JSONObject.NULL)
                .put("player", e.player ?: JSONObject.NULL).put("route", e.route).put("values", JSONObject(e.values)) }))
            .put("ranges", JSONArray(p.ranges.map { (key, r) -> JSONObject().put("signal", key).put("samples", r.count)
                .put("min", r.min).put("max", r.max).put("last", r.last) }))
            .put("recentEvents", JSONArray(p.samples.map { e -> JSONObject().put("elapsedMs", e.atMs).put("stage", e.stage.name)
                .put("controller", e.device ?: JSONObject.NULL).put("player", e.player ?: JSONObject.NULL).put("route", e.route)
                .put("values", JSONObject(e.values)) }))
            .put("legacyClients", JSONArray(p.clients.values.map { c -> JSONObject().put("localPort", c.port)
                .put("reportedProcessId", c.processId ?: JSONObject.NULL).put("requestedApi", c.api).put("lastReplyEnabled", c.allowed)
                .put("discoveryReplies", c.discoveryReplies).put("statePacketsSent", c.statePackets).put("sendFailures", c.sendFailures)
                .put("lastActivityAgeMs", clock() - p.started - c.lastAtMs) }))
            .put("transportLimit", "Legacy client PID is reported by a local Wine request, not verified as the game process. A successful UDP send is not a receipt. NATIVE_WAKE records sequence advancement in Android's shared buffer, not guest consumption. Stages are separate observations, not a one-to-one correlated packet trace. No SDL guest reader acknowledgement is available.")
            .put("evidenceLimit", "ANDROID = gamepad-only events reaching this game screen, including blocked routes. PROFILE_BINDING = a physical-controller binding selected by GameNative; keyboard/mouse names are mapped outputs, not typed text. WINE_BUFFER = app-side write to the controller shared-memory bridge completed; values describe GamepadState before axis encoding/trigger curve, NOT proof the game read it or reacted. WINE_BUFFER_UNAVAILABLE = no target buffer. Counts/ranges may aggregate different controls/devices and do not prove one-to-one delivery. No keyboard text, screen images, Bluetooth addresses or device descriptors captured. No Android events may mean a disconnected controller, unsupported source, no test input or input intercepted before the game screen. On-screen controls have no physical Android-gamepad stage. Compare fresh probes after a reviewed change; ask the user whether the game reacted.")
            .toString()
    }
}

object ControllerInputTrace {
    internal val buffer = ControllerTraceBuffer(SystemClock::elapsedRealtime)
    private val axes = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
        MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
        MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y)
    fun start(game: String, mode: ControllerTraceBuffer.Mode = ControllerTraceBuffer.Mode.TEST) {
        check(BuildConfig.AI_ASSISTANT_ENABLED)
        val live = requireNotNull(LiveGameSession.view(game)) { "Starta spelet först." }
        check(!live.paused && PluviaApp.isActivityInForeground) { "Återuppta spelet före kontrolltestet." }
        buffer.start(game, live.token, mode)
    }
    fun view(game: String) = buffer.view(game)
    fun summary(game: String) = buffer.view(game, includeDetails = false)
    fun read(game: String): String {
        val view = view(game) ?: return """{"available":false,"note":"No controller observation yet. Settings and current bridge can still be inspected. In the in-game assistant choose Kontroll och input, then Visa input live (up to five minutes) or the 20-second test; use the controller in the game, then review and request analysis. No debug run needed."}"""
        return JSONObject(view.json).put("sameLaunchStillRunning", LiveGameSession.view(game)?.token == JSONObject(view.json).getString("launchId")).toString()
    }
    fun finish(game: String) { LiveGameSession.view(game)?.let { buffer.finish(it.token, "returned_to_chat") } }
    fun endLaunch(token: String?) { buffer.finish(token, "game_stopped") }
    fun background(token: String?) { buffer.finish(token, "app_backgrounded") }
    @JvmStatic fun nativeWake(token: String?, player: Int, before: Int, after: Int) {
        if (!buffer.accepts(token)) return
        buffer.record(token, ControllerTraceBuffer.Stage.NATIVE_WAKE, null, player,
            mapOf("sequenceAdvanced" to if (before != after) 1f else 0f), "native_shared_memory_wake")
    }
    @JvmStatic fun legacyReply(token: String?, port: Int, processId: Int, xinput: Boolean, allowed: Boolean, sent: Boolean) {
        buffer.legacy(token, port, processId, if (xinput) "XInput" else "DirectInput", allowed, sent)
    }
    @JvmStatic fun legacyState(token: String?, port: Int, enabled: Boolean, sent: Boolean) {
        buffer.legacy(token, port, null, null, enabled, sent)
    }
    private fun launch(game: String): String? = buffer.launchFor(game)?.takeIf { it == LiveGameSession.token() }
    private fun allowed(device: InputDevice?) = device != null && !device.isVirtual &&
        (device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK))
    internal fun allowedKey(code: Int) = KeyEvent.isGamepadButton(code) || code in setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
    @JvmStatic fun key(game: String, event: KeyEvent, blocked: Boolean) {
        val token = launch(game) ?: return
        if (!allowed(event.device) || !allowedKey(event.keyCode) || event.repeatCount != 0 || event.action !in 0..1) return
        recordAndroid(token, event.deviceId, mapOf(KeyEvent.keyCodeToString(event.keyCode) to if (event.action == KeyEvent.ACTION_DOWN) 1f else 0f), blocked)
    }
    @JvmStatic fun motion(game: String, event: MotionEvent?, blocked: Boolean) {
        val token = launch(game) ?: return
        if (event == null || !allowed(event.device) || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return
        recordAndroid(token, event.deviceId, axes.filter { event.device?.getMotionRange(it, event.source) != null }
            .associate { MotionEvent.axisToString(it) to event.getAxisValue(it) }, blocked)
    }
    private fun recordAndroid(token: String, device: Int, values: Map<String, Float>, blocked: Boolean) {
        val slot = ControllerManager.getInstance().getSlotForDevice(device)
        buffer.record(token, ControllerTraceBuffer.Stage.ANDROID, device, (slot + 1).takeIf { slot in 0..3 }, values,
            if (blocked) "blocked_by_overlay_or_pause" else "gameplay_dispatch")
    }
    @JvmStatic fun binding(token: String?, device: Int, source: Int, binding: String, down: Boolean, offset: Float) {
        if (!buffer.accepts(token)) return
        buffer.record(token, ControllerTraceBuffer.Stage.PROFILE_BINDING, device.takeIf { it >= 0 }, 1,
            mapOf("source${source}:$binding" to if (!down) 0f else if (offset != 0f) offset else 1f), "physical_profile")
    }
    @JvmStatic fun guestState(token: String?, player: Int, state: GamepadState?, written: Boolean, virtual: Boolean) {
        if (!buffer.accepts(token) || state == null) return
        val values = linkedMapOf("LX" to state.thumbLX, "LY" to state.thumbLY, "RX" to state.thumbRX,
            "RY" to state.thumbRY, "LT" to state.triggerL, "RT" to state.triggerR)
        listOf("A", "B", "X", "Y", "LB", "RB", "BACK", "START", "L3", "R3", "L2", "R2").forEachIndexed { i, name -> values[name] = if (state.isPressed(i)) 1f else 0f }
        listOf("UP", "RIGHT", "DOWN", "LEFT").forEachIndexed { i, name -> values[name] = if (state.dpad[i]) 1f else 0f }
        buffer.record(token, if (written) ControllerTraceBuffer.Stage.WINE_BUFFER else ControllerTraceBuffer.Stage.WINE_BUFFER_UNAVAILABLE,
            null, player, values, if (virtual) "profile_or_on_screen" else "physical_passthrough")
    }
}
