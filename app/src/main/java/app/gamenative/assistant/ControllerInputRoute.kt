package app.gamenative.assistant

import app.gamenative.PluviaApp
import org.json.JSONObject
import java.util.Locale

/** Observations of the app's output, never a guest acknowledgement or an input injector. */
internal object ControllerInputRoute {
    fun snapshot(game: String): JSONObject {
        val live = LiveGameSession.view(game)
        val handler = PluviaApp.xServerView?.getxServer()?.winHandler
            ?.takeIf { live != null && it.assistantSessionToken == live.token }
        val bridge = handler?.assistantInputRoute
        // A game can exit while a read is in progress. Never label its old bridge as current.
        val current = live != null && LiveGameSession.view(game)?.token == live.token
        return JSONObject().put("game", game).put("launchId", live?.token ?: JSONObject.NULL)
            .put("available", current && bridge != null).put("status", if (current) live!!.status else "not_running")
            .put("runtimeBridge", if (current) bridge ?: JSONObject.NULL else JSONObject.NULL)
            .put("note", "App-side read at tool-call time, not an atomic game frame. Shared-memory bytes can change while read. A running game may poll SDL/evshim without making legacy discovery requests; no requests is inconclusive. No input injected, configuration modified or guest receipt inferred.")
    }

    fun stageTitle(stage: ControllerTraceBuffer.Stage) = when (stage) {
        ControllerTraceBuffer.Stage.ANDROID -> "Android tar emot"
        ControllerTraceBuffer.Stage.PROFILE_BINDING -> "Vald mappning"
        ControllerTraceBuffer.Stage.WINE_BUFFER -> "Skrivet till bryggan"
        ControllerTraceBuffer.Stage.WINE_BUFFER_UNAVAILABLE -> "Saknad brygga"
        ControllerTraceBuffer.Stage.NATIVE_WAKE -> "Native signal"
    }

    fun describe(sample: ControllerTraceBuffer.Sample): String {
        val player = sample.player?.let { "P$it · " }.orEmpty()
        if (sample.stage == ControllerTraceBuffer.Stage.NATIVE_WAKE) {
            return player + if (sample.values["sequenceAdvanced"] == 1f) "sekvensen ökade" else "sekvensen ändrades inte"
        }
        val values = sample.values.entries.filter { it.value != 0f }.ifEmpty { sample.values.entries.take(2) }
            .take(3).joinToString { "${it.key.removePrefix("KEYCODE_").removePrefix("AXIS_")}=${number(it.value)}" }
        return when (sample.stage) {
            ControllerTraceBuffer.Stage.ANDROID -> "Kontroll ${sample.device ?: "?"} · $player$values" +
                if (sample.route == "blocked_by_overlay_or_pause") " · stoppad av panel/paus" else ""
            ControllerTraceBuffer.Stage.PROFILE_BINDING -> "$values → " + when {
                sample.values.keys.any { it.substringAfter(':').startsWith("GAMEPAD_") } -> "${player}kontrollbryggan"
                sample.values.keys.any { it.substringAfter(':').startsWith("MOUSE_") } -> "mus"
                sample.values.keys.any { it.substringAfter(':').startsWith("KEY_") } -> "tangentbord"
                else -> "profilens åtgärd"
            }
            else -> "$player$values"
        }
    }

    fun age(ageMs: Long) = if (ageMs < 1000) "nu" else "${ageMs / 1000} s sedan"
    private fun number(value: Float) = if (value == value.toInt().toFloat()) value.toInt().toString()
        else String.format(Locale.ROOT, "%.2f", value)
}
