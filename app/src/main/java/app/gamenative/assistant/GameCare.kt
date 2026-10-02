package app.gamenative.assistant

import android.content.Context
import android.view.KeyEvent
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.inputcontrols.ControlProfileService
import app.gamenative.mods.ModDeploymentCoordinator
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Adapter for this game's named profiles; profile contents never leave private app storage. */
class GameCare(private val context: Context, private val game: String) : GameProfiles.Backend {
    private val profiles = GameProfiles(File(context.noBackupFilesDir, "assistant/profiles/$game"), game, this)
    private val mods = GameProfileMods(context, game) { GameFileRoots.discover(context, game).firstOrNull { it.id == "game" }?.directory }
    private var inspectedControls: JSONObject? = null
    private val choices = linkedMapOf<String, JSONObject>()
    private fun configFile() = ContainerUtils.getContainer(context, game).configFile
    private fun manager() = InputControlsManager(context)
    fun beginTurn() { profiles.beginTurn(); inspectedControls = null; choices.clear() }
    fun hasUndo() = profiles.hasUndo()

    override fun checkStopped() {
        check(!SteamService.keepAlive && LiveGameSession.token() == null) { "Stäng spelet innan en profil sparas eller ändras." }
    }
    private fun checkOtherUndo() {
        check(listOf("undo", "file-undo", "mod-undo").none { File(context.noBackupFilesDir, "assistant/$it/$game.json").exists() }) {
            "Ångra eller behåll det befintliga inställnings-/fil-/modförsöket först. Dess backup är bevarad."
        }
    }
    override suspend fun capture(): JSONObject {
        val config = JSONObject(configFile().readText())
        val settings = JSONObject()
        paths.forEach { key ->
            val parent = if (key.startsWith("extraData.")) config.optJSONObject("extraData") else config
            val name = key.substringAfter('.')
            settings.put(key, JSONObject().put("present", parent?.has(name) == true).put("value", parent?.opt(name) ?: JSONObject.NULL))
        }
        val manager = manager()
        val selected = manager.getProfile(config.optJSONObject("extraData")?.optString("profileId")?.toIntOrNull() ?: 0) ?: manager.getProfile(0)
        val controls = selected?.let { normalizeControls(ControlProfileService.readProfileJson(context, it)) }
        return JSONObject().put("settings", settings).put("controls", controls ?: JSONObject.NULL).put("mods", mods.capture())
    }
    override suspend fun validate(target: JSONObject) {
        checkStopped(); checkOtherUndo()
        require(target.keys().asSequence().all { it in setOf("settings", "controls", "mods") })
        target.optJSONObject("settings")?.let { settings ->
            require(settings.keys().asSequence().all { it in paths })
            settings.keys().forEach { key -> val value = settings.getJSONObject(key); require(value.get("present") is Boolean && value.has("value")) }
        }
        if (target.has("controls") && !target.isNull("controls")) ControlProfileService.preview(target.getJSONObject("controls"))
        target.optJSONObject("mods")?.let { if (!GameProfiles.same(mods.capture(), it)) mods.validate(it) }
    }
    override suspend fun guard(shape: JSONObject) = if (shape.has("mods")) mods.fingerprints() else JSONObject()
    override suspend fun write(target: JSONObject) {
        checkStopped(); checkOtherUndo()
        // Native deployment journals protect individual mod files; the surrounding profile journal retains the full previous selection.
        target.optJSONObject("mods")?.let { mods.write(it) }
        checkStopped()
        val file = configFile()
        val config = JSONObject(file.readText())
        target.optJSONObject("settings")?.let { settings -> settings.keys().forEach { key ->
            val parent = if (key.startsWith("extraData.")) config.optJSONObject("extraData") ?: JSONObject().also { config.put("extraData", it) } else config
            val entry = settings.getJSONObject(key)
            if (entry.getBoolean("present")) parent.put(key.substringAfter('.'), entry.get("value")) else parent.remove(key.substringAfter('.'))
        } }
        if (target.has("controls")) {
            val json = target.optJSONObject("controls")
            val extra = config.optJSONObject("extraData") ?: JSONObject().also { config.put("extraData", it) }
            if (json == null) extra.remove("profileId") else {
                // Copy-on-write: never change a global/library profile or another game's working profile.
                val id = manager().nextProfileId()
                val copy = GameProfiles.copy(json).put("id", id).put("name", "Spelprofil · $game").put("listed", false)
                    .put("gameOwnerId", ContainerUtils.getContainer(context, game).id).put("libraryProfileId", -1)
                ConfigTransaction.atomicWrite(ControlsProfile.getProfileFile(context, id), copy.toString().toByteArray())
                extra.put("profileId", id.toString())
            }
        }
        ConfigTransaction.atomicWrite(file, config.toString().toByteArray())
        withContext(Dispatchers.Main) { PluviaApp.inputControlsManager?.reloadProfiles() }
    }
    suspend fun inventory(includeMods: Boolean): JSONObject = withContext(Dispatchers.IO) {
        JSONObject().put("profiles", profiles.inventory()).put("undoAvailable", hasUndo())
            .put("scope", "Named local snapshots. game includes saved graphics/runtime choices, controls and tracked mod selection/order; controls only controller-related settings and bindings; mods only reviewed mod deployments. No saves/game binaries/registry/runtime package copies. No proof that a saved state works unless the user tested it.")
            .put("mods", if (includeMods) mods.inspect() else JSONObject.NULL)
    }
    suspend fun readControls(): JSONObject = withContext(Dispatchers.IO) {
        if (!ContainerUtils.hasContainer(context, game)) return@withContext JSONObject().put("library", JSONArray())
            .put("note", "Ingen spelmiljö finns ännu. Förbered spelet via importskärmen eller spelets inställningar i biblioteket först.")
        val current = capture()
        inspectedControls = GameProfiles.copy(current)
        choices.clear()
        val library = JSONArray()
        manager().profiles.take(100).forEach { profile ->
            val key = java.util.UUID.randomUUID().toString()
            val json = normalizeControls(ControlProfileService.readProfileJson(context, profile))
            choices[key] = json
            val preview = ControlProfileService.preview(json)
            library.put(JSONObject().put("profile_id", key).put("name", DiagnosticRedactor.text(profile.name))
                .put("onScreenControls", preview.elementCount).put("physicalBindings", preview.physicalBindingCount))
        }
        val controllers = current.optJSONObject("controls")?.optJSONArray("controllers") ?: JSONArray()
        val bindings = JSONArray()
        for (i in 0 until controllers.length()) {
            val controller = controllers.getJSONObject(i)
            bindings.put(JSONObject().put("controller_id", "controller_$i").put("name", DiagnosticRedactor.text(controller.optString("name")))
                .put("wildcard", controller.optString("id") == "*").put("bindings", controller.optJSONArray("controllerBindings") ?: JSONArray()))
        }
        JSONObject().put("library", library).put("currentBindings", bindings).put("allowedButtons", JSONArray(buttons.keys.toList()))
            .put("allowedOutputs", JSONArray(outputs)).put("note", "Profile IDs and controller IDs valid only until next read/turn. Applying copies bindings into this game's private working profile; no other games or global player slots change. A stopped game is required. Read read_input_route for actual player slots and trace. Source button MODE/Home is intentionally preserved for opening Quick Menu. Physical remapping changes one button; sticks retain their existing tuning. For player assignment use the existing Controller menu; no global slot writes from this tool.")
    }
    suspend fun prepare(name: String, args: JSONObject, allowMods: Boolean): CarePreview = withContext(Dispatchers.IO) {
        checkStopped()
        when (name) {
            "propose_game_profile" -> {
                require(args.keys().asSequence().toSet() == setOf("action", "kind", "name", "profile_id", "mod_ids", "reason"))
                val action = args.getString("action"); val kind = args.getString("kind"); val reason = args.getString("reason")
                require(kind in setOf("game", "controls", "mods"))
                check(kind == "controls" || allowMods) { "Aktivera modåtkomst för en profil med modval." }
                val ids = args.getJSONArray("mod_ids").let { a -> (0 until a.length()).map { a.getString(it) } }
                require(ids.size <= 150 && ids.distinct().size == ids.size)
                if (action == "restore") {
                    val metadata = profiles.inventory().let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.single { it.getString("profile_id") == args.getString("profile_id") }
                    require(metadata.getString("kind") == kind && ids.isEmpty())
                    profiles.prepareRestore(args.getString("profile_id"), reason)
                } else {
                    require(action == "save" && args.getString("profile_id").isEmpty())
                    val current = capture()
                    val payload = project(current, kind)
                    if (kind == "mods") {
                        val packages = payload.getJSONObject("mods")
                        require(ids.all(packages::has)) { "Välj mod-ID:n från read_game_profiles." }
                        packages.keys().forEach { id -> packages.getJSONObject(id).put("enabled", id in ids).put("priority", ids.indexOf(id).coerceAtLeast(0)) }
                        // Empty selection is the Original profile. Other packages need a reviewed placement first.
                        ids.forEach { check(packages.getJSONObject(it).getJSONArray("placement").length() > 0) { "Installera modden med en granskad filplacering först." } }
                    } else require(ids.isEmpty())
                    profiles.prepareSave(kind, args.getString("name"), payload, reason)
                }
            }
            "propose_control_profile" -> {
                require(args.keys().asSequence().toSet() == setOf("profile_id", "reason"))
                requireNotNull(inspectedControls) { "Läs kontrollprofilerna först." }
                val selected = requireNotNull(choices[args.getString("profile_id")]) { "Läs profilbiblioteket igen." }
                profiles.prepareChange("Byt kontrollprofil för detta spel", args.getString("reason"), JSONObject().put("controls", selected))
            }
            "propose_controller_binding" -> {
                require(args.keys().asSequence().toSet() == setOf("controller_id", "button", "output", "reason"))
                val snapshot = requireNotNull(inspectedControls) { "Läs kontrollprofilerna först." }
                check(GameProfiles.same(snapshot.opt("controls"), capture().opt("controls"))) { "Kontrollprofilen ändrades. Läs igen." }
                val controls = GameProfiles.copy(snapshot.getJSONObject("controls"))
                val index = args.getString("controller_id").removePrefix("controller_").toIntOrNull()
                require(index != null && args.getString("controller_id") == "controller_$index" && index in 0 until controls.getJSONArray("controllers").length())
                val button = args.getString("button"); val output = args.getString("output")
                val code = requireNotNull(buttons[button]); require(output in outputs)
                val controller = controls.getJSONArray("controllers").getJSONObject(index)
                val list = controller.getJSONArray("controllerBindings")
                val updated = JSONArray((0 until list.length()).map { list.getJSONObject(it) }.filterNot { it.optInt("keyCode") == code })
                updated.put(JSONObject().put("keyCode", code).put("binding", output))
                controller.put("controllerBindings", updated)
                profiles.prepareChange("$button → $output", args.getString("reason"), JSONObject().put("controls", controls))
            }
            else -> error("Unsupported profile action")
        }
    }
    suspend fun apply(preview: CarePreview, modsApproved: Boolean = false) = withContext(Dispatchers.IO) {
        check(!preview.changesMods || modsApproved) { "Granska och godkänn modval och filprioritet först." }
        ModDeploymentCoordinator.withGameLock(game) { profiles.apply(preview, BuildConfig.VERSION_NAME) }
    }
    suspend fun restore() = withContext(Dispatchers.IO) { ModDeploymentCoordinator.withGameLock(game) { profiles.restore() } }
    suspend fun keep() = withContext(Dispatchers.IO) { profiles.keep() }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { profiles.delete(id) }
    suspend fun modReport() = withContext(Dispatchers.IO) { mods.inspect() }

    companion object {
        private val controlPaths = setOf("inputType", "dinputMapperType", "sdlControllerAPI", "disableMouseInput", "touchscreenMode", "shooterMode", "gestureConfig", "shooterConfig", "extraData.useSteamInput", "extraData.vibrationIntensity")
        private val paths = GameSettingCatalog.paths + controlPaths + setOf("graphicsDriver", "graphicsDriverVersion", "graphicsDriverConfig", "dxwrapper", "dxwrapperConfig", "displayRendererMode", "containerVariant", "wineVersion", "emulator", "box64Version", "fexcoreVersion", "fexcorePreset", "wincomponents", "cpuList", "cpuListWoW64", "extraData.lsfgEnabled")
        internal fun project(current: JSONObject, kind: String): JSONObject = when (kind) {
            "game" -> GameProfiles.copy(current)
            "controls" -> JSONObject().put("controls", current.get("controls")).put("settings", JSONObject().apply {
                controlPaths.forEach { put(it, current.getJSONObject("settings").get(it)) }
            })
            "mods" -> JSONObject().put("mods", current.getJSONObject("mods"))
            else -> error("Unsupported profile kind")
        }
        internal fun normalizeControls(json: JSONObject) = GameProfiles.copy(json).apply {
            listOf("id", "name", "listed", "gameOwnerId", "libraryProfileId", "sectionSources").forEach(::remove)
            put("name", "Kontrollprofil")
        }
        val buttons = linkedMapOf("A" to KeyEvent.KEYCODE_BUTTON_A, "B" to KeyEvent.KEYCODE_BUTTON_B, "X" to KeyEvent.KEYCODE_BUTTON_X, "Y" to KeyEvent.KEYCODE_BUTTON_Y,
            "L1" to KeyEvent.KEYCODE_BUTTON_L1, "R1" to KeyEvent.KEYCODE_BUTTON_R1, "L2" to KeyEvent.KEYCODE_BUTTON_L2, "R2" to KeyEvent.KEYCODE_BUTTON_R2,
            "START" to KeyEvent.KEYCODE_BUTTON_START, "SELECT" to KeyEvent.KEYCODE_BUTTON_SELECT, "L3" to KeyEvent.KEYCODE_BUTTON_THUMBL, "R3" to KeyEvent.KEYCODE_BUTTON_THUMBR,
            "DPAD_UP" to KeyEvent.KEYCODE_DPAD_UP, "DPAD_DOWN" to KeyEvent.KEYCODE_DPAD_DOWN, "DPAD_LEFT" to KeyEvent.KEYCODE_DPAD_LEFT, "DPAD_RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT)
        val outputs = Binding.values().filter { it.name.startsWith("GAMEPAD_") || it.name.startsWith("KEY_") || it.name in setOf("SHOW_KEYBOARD", "OPEN_NAVIGATION_MENU") }.map { it.name }
    }
}
