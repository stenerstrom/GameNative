package app.gamenative.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Audited per-game settings. Adding a capability requires a typed mapping, not arbitrary JSON access. */
object GameSettingCatalog {
    data class Setting(val id: String, val path: String, val label: String, val choices: List<String>,
        val help: String, val decode: (String) -> Any = { it }) {
        fun validate(value: String): Any {
            require(value in choices) { "Unsupported value for $id" }
            return decode(value)
        }
        fun current(config: JSONObject): String {
            val raw = if (path.startsWith("extraData.")) config.optJSONObject("extraData")?.opt(path.substringAfter('.')) else config.opt(path)
            return choices.firstOrNull { decode(it).toString() == raw?.toString() }
                ?: raw?.toString()?.let { DiagnosticRedactor.text(it).take(160) } ?: "Not stored (default)"
        }
        fun display(value: String): String = when {
            value == "Not stored (default)" -> "Standardvärde (ej sparat)"
            id == "inputApi" -> ControllerInputApi.entries.firstOrNull { it.name == value }?.label ?: value
            id == "directInputMapper" -> DirectInputMapper.entries.firstOrNull { it.name == value }?.label ?: value
            choices == listOf("false", "true") -> when (value) { "true" -> "På"; "false" -> "Av"; else -> value }
            else -> value
        }
    }
    private fun toggle(id: String, label: String, help: String, path: String = id) =
        Setting(id, path, label, listOf("false", "true"), help) { it == "true" }
    val settings = listOf(
        Setting("screenSize", "screenSize", "Resolution", listOf("640x480", "800x600", "960x540", "1024x576", "1024x768", "1280x720", "1280x800", "1366x768", "1440x810", "1440x900", "1600x900", "1920x1080", "1920x1200", "2560x1440"), "Container resolution; game can override. Lower resolution can reduce GPU load."),
        toggle("fpsLimiterEnabled", "FPS limiter", "Frame cap, not frame generation.", "extraData.fpsLimiterEnabled"),
        Setting("fpsLimiterTarget", "extraData.fpsLimiterTarget", "FPS limit", listOf("20", "25", "30", "40", "45", "50", "60", "72", "90", "120"), "Also enable fpsLimiterEnabled to activate. Cannot create missing frames.") { it.toInt() },
        Setting("inputApi", "inputType", "Controller API", ControllerInputApi.entries.map { it.name }, "BOTH: XInput and DirectInput. AUTO is automatic, not disabled.") { ControllerInputApi.valueOf(it).nativeApi.ordinal },
        Setting("directInputMapper", "dinputMapperType", "DirectInput mapper", DirectInputMapper.entries.map { it.name }, "STANDARD or XINPUT mapper; does not itself enable the XInput API.") { DirectInputMapper.valueOf(it).storedValue },
        toggle("sdlControllerAPI", "SDL controller API", "Selects SDL controller handling for the next launch."),
        toggle("useSteamInput", "Steam Input", "Per-game Steam Input setting; does not pair Bluetooth devices.", "extraData.useSteamInput"),
        toggle("disableMouseInput", "Disable mouse input", "Disables mouse input; keep an alternative control method."),
        toggle("touchscreenMode", "Touchscreen mode", "Direct touch instead of touchpad interaction."),
        toggle("shooterMode", "Shooter controls", "Dynamic stick behavior in on-screen controls."),
        Setting("vibrationIntensity", "extraData.vibrationIntensity", "Vibration intensity (%)", (0..100 step 10).map { it.toString() }, "Controller vibration strength."),
        Setting("audioDriver", "audioDriver", "Audio driver", listOf("alsa", "pulseaudio"), "Existing audio backends; restart the game."),
        toggle("pulseaudioLowLatency", "Low-latency audio", "PulseAudio only; may increase crackling on some devices."),
        Setting("rendererPresentMode", "rendererPresentMode", "Renderer presentation mode", listOf("fifo", "mailbox"), "The two modes exposed by GameNative's graphics tab. Support and latency depend on the renderer/driver."),
        Setting("box64Preset", "box64Preset", "Box64 preset", listOf("STABILITY", "COMPATIBILITY", "INTERMEDIATE", "PERFORMANCE"), "Built-in Box64 presets; does not affect games running with FEX instead."),
        toggle("useDRI3", "DRI3", "Graphics compatibility experiment; may affect rendering."),
        toggle("portraitMode", "Portrait mode", "Per-game portrait layout."),
        Setting("externalDisplayMode", "externalDisplayMode", "External display input", listOf("off", "touchpad", "keyboard", "hybrid"), "Controls on the other display; does not connect a display."),
        toggle("externalDisplaySwap", "Swap external display", "Swap game and input displays."),
        Setting("suspendPolicy", "suspendPolicy", "Suspend behavior", listOf("manual", "auto", "never"), "GameNative container suspension policy."),
    )
    private val byId = settings.associateBy { it.id }
    val paths = settings.map { it.path }.toSet()
    fun setting(id: String) = requireNotNull(byId[id]) { "Unsupported setting: $id" }
    fun patch(changes: Map<String, String>): Map<String, Any> {
        require(changes.size in 1..8) { "Use between one and eight related setting changes" }
        return changes.map { (id, value) -> setting(id).let { it.path to it.validate(value) } }.toMap()
    }
    fun describe(config: JSONObject): JSONArray = JSONArray().apply {
        settings.forEach { put(JSONObject().put("setting", it.id).put("label", it.label).put("current", it.current(config))
            .put("allowedValues", JSONArray(it.choices)).put("help", it.help)) }
    }
    fun parseProposal(json: JSONObject): ConfigProposal {
        require(json.keys().asSequence().toSet() == setOf("changes", "reason")) { "Unexpected proposal fields" }
        val list = json.getJSONArray("changes")
        require(list.length() in 1..8)
        val changes = linkedMapOf<String, String>()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            require(item.keys().asSequence().toSet() == setOf("setting", "value"))
            val id = item.get("setting"); val value = item.get("value")
            require(id is String && value is String && !changes.containsKey(id)) { "Invalid or duplicate setting" }
            changes[id] = value
        }
        val reason = json.get("reason")
        require(reason is String)
        return ConfigProposal(null, null, reason, settings = changes)
    }
}
