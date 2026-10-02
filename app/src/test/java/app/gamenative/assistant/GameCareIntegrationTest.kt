package app.gamenative.assistant

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.*
import app.gamenative.db.PluviaDatabase
import app.gamenative.mods.*
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.Container
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

internal class GameCareFixture(val app: ModAssistantTestApplication) {
    val game = "CUSTOM_GAME_42"
    val folder: File
    val container: Container
    val shared: File
    val other: Container
    init {
        app.testDatabase = Room.inMemoryDatabaseBuilder(app, PluviaDatabase::class.java).allowMainThreadQueries().build()
        PrefManager.init(app); SteamService.keepAlive = false; LiveGameSession.end()
        DownloadService::class.java.getDeclaredField("baseDataDirPath").apply { isAccessible = true; set(null, app.filesDir.canonicalPath) }
        DownloadService::class.java.getDeclaredField("baseExternalAppDirPath").apply { isAccessible = true; set(null, "") }
        PrefManager.useExternalStorage = false; PrefManager.externalStoragePath = ""
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        folder = File(app.filesDir.canonicalFile, "Offline Game").apply { mkdirs() }
        File(folder, ".gamenative").writeText("{\"appId\":42}")
        File(folder, ".gamenative-offline").writeText("1")
        testWindowsExe(File(folder, "game.exe"))
        File(folder, "settings.ini").writeText("original")
        PrefManager.customGameManualFolders = setOf(folder.path); CustomGameScanner.invalidateCache()
        shared = ControlsProfile.getProfileFile(app, 880001).apply { parentFile!!.mkdirs() }
        shared.writeText("""{"id":880001,"name":"Shared controller","listed":true,"elements":[],"controllers":[{"id":"*","name":"Gamepad","controllerBindings":[{"keyCode":96,"binding":"GAMEPAD_BUTTON_A"},{"keyCode":110,"binding":"OPEN_NAVIGATION_MENU"}]}]}""")
        fun create(id: String) = Container(id).apply {
            rootDir = File(ImageFs.find(app).rootDir, "home/xuser-$id").apply { mkdirs() }
            drives = "A:${folder.path}"; executablePath = "game.exe"; screenSize = "1920x1080"; inputType = 3
            envVars = "SECRET_TOKEN=do-not-send"; putExtra("profileId", "880001")
            check(saveDataChecked())
        }
        container = create(game); other = create("CUSTOM_GAME_99")
    }
    fun care() = GameCare(app, game)
    fun args(action: String = "save", kind: String = "game", name: String = "Working", id: String = "", mods: List<String> = emptyList()) = JSONObject()
        .put("action", action).put("kind", kind).put("name", name).put("profile_id", id).put("mod_ids", JSONArray(mods)).put("reason", "Test per-game restoration")
    fun close() { SteamService.keepAlive = false; LiveGameSession.end(); app.testDatabase.close() }
}

internal fun <T> careIo(block: suspend () -> T): T {
    val task = CompletableFuture.supplyAsync { runBlocking { block() } }
    val deadline = System.nanoTime() + 30_000_000_000L
    while (!task.isDone && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
    return task.get(1, java.util.concurrent.TimeUnit.SECONDS)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ModAssistantTestApplication::class)
class GameCareIntegrationTest {
    private lateinit var f: GameCareFixture
    @Before fun setup() { f = GameCareFixture(ApplicationProvider.getApplicationContext()) }
    @After fun cleanup() { f.close() }

    @Test fun gameProfileRestoresSettingsAndUndoPreservesSecretsLaunchAndOtherGames() = careIo {
        val care = f.care(); val shared = f.shared.readBytes(); val other = f.other.configFile.readBytes()
        val snapshot = care.capture()
        assertFalse(snapshot.toString().contains("do-not-send"))
        care.apply(care.prepare("propose_game_profile", f.args(), true))
        val id = care.inventory(false).getJSONArray("profiles").getJSONObject(0).getString("profile_id")
        f.container.configFile.writeText(JSONObject(f.container.configFile.readText()).put("screenSize", "1280x720").put("execArgs", "--manual").toString())
        care.apply(care.prepare("propose_game_profile", f.args("restore", name = "", id = id), true))
        assertEquals("1920x1080", JSONObject(f.container.configFile.readText()).getString("screenSize"))
        assertEquals("--manual", JSONObject(f.container.configFile.readText()).getString("execArgs"))
        assertTrue(JSONObject(f.container.configFile.readText()).getString("envVars").contains("do-not-send"))
        assertTrue(f.care().hasUndo()); f.care().restore()
        assertEquals("1280x720", JSONObject(f.container.configFile.readText()).getString("screenSize"))
        assertArrayEquals(shared, f.shared.readBytes()); assertArrayEquals(other, f.other.configFile.readBytes())
        assertFalse(f.care().hasUndo())
    }

    @Test fun remapCopiesOnlySelectedGameAndUndoRestoresBindingsWithoutTouchingMenu() = careIo {
        val care = f.care(); val before = care.capture().getJSONObject("controls")
        val shared = f.shared.readBytes(); val other = f.other.configFile.readBytes()
        val info = care.readControls(); assertFalse(info.toString().contains("do-not-send"))
        val args = JSONObject().put("controller_id", "controller_0").put("button", "A").put("output", "GAMEPAD_BUTTON_B").put("reason", "Swap one button")
        val preview = care.prepare("propose_controller_binding", args, false)
        assertTrue(GameProfiles.same(before, care.capture().get("controls")))
        care.apply(preview)
        val bindings = care.capture().getJSONObject("controls").getJSONArray("controllers").getJSONObject(0).getJSONArray("controllerBindings")
        assertTrue(bindings.toString().contains("GAMEPAD_BUTTON_B")); assertTrue(bindings.toString().contains("OPEN_NAVIGATION_MENU"))
        assertNotEquals("880001", JSONObject(f.container.configFile.readText()).getJSONObject("extraData").getString("profileId"))
        assertArrayEquals(shared, f.shared.readBytes()); assertArrayEquals(other, f.other.configFile.readBytes())
        f.care().restore(); assertTrue(GameProfiles.same(before, f.care().capture().get("controls")))
        care.readControls()
        assertTrue(runCatching { care.prepare("propose_controller_binding", args.put("button", "HOME"), false) }.isFailure)
        SteamService.keepAlive = true
        assertTrue(runCatching { care.prepare("propose_controller_binding", args.put("button", "A"), false) }.isFailure)
    }

    private suspend fun install(id: String, priority: Int) {
        val cache = NexusModManager.cacheRoot(f.app, f.game)
        val extracted = File(cache, "extracted/$id").apply { mkdirs() }
        File(extracted, "settings.ini").writeText(id)
        File(extracted, "new.ini").writeText(id)
        val mod = ModInstall(id, f.game, ModInstallSource.LOCAL_ARCHIVE.name, modName = id, fileName = "$id.zip", archivePath = "", extractedPath = extracted.path)
        val dao = f.app.testDatabase.modDao(); dao.upsertInstall(mod)
        val profile = ModProfileManager.ensureActiveProfile(dao, f.game)
        val plan = ModInstallPlan(listOf("settings.ini", "new.ini").map { path -> PlannedModFile(path, "GAME_DIR", path, ModTargetResolver.normalizedTargetKey("GAME_DIR", path), PlannedFileStatus.PLACED, PlacementOrigin.MANUAL_RECIPE, reason = "reviewed") })
        val result = NexusModManager.applyInstall(f.app, mod, emptyList(), f.folder, "", true, profileId = profile.profileId, priority = priority, reviewedPlan = plan)
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        dao.upsertProfileInstallState(ModProfileInstallState(profile.profileId, id, f.game, true, priority))
    }
    @Test fun overlappingModProfilesSwitchWinnerThenUndoAndOriginalRestoresActualFiles() = careIo {
        install("a_lower", 0); install("b_upper", 1)
        assertEquals("b_upper", File(f.folder, "settings.ini").readText())
        val mods = GameProfileMods(f.app, f.game) { f.folder }
        assertEquals("b_upper", mods.inspect().getJSONArray("conflicts").getJSONObject(0).getString("activeWinner"))
        val care = f.care()
        care.apply(care.prepare("propose_game_profile", f.args(kind = "mods", name = "Reverse", mods = listOf("b_upper", "a_lower")), true))
        val reverse = care.inventory(true).getJSONArray("profiles").getJSONObject(0).getString("profile_id")
        val preview = care.prepare("propose_game_profile", f.args("restore", "mods", "", reverse), true)
        assertTrue(runCatching { care.apply(preview, false) }.isFailure)
        assertEquals("b_upper", File(f.folder, "settings.ini").readText())
        care.apply(preview, true)
        assertEquals("a_lower", File(f.folder, "settings.ini").readText())
        f.care().restore(); assertEquals("b_upper", File(f.folder, "settings.ini").readText())
        assertEquals("b_upper", File(f.folder, "new.ini").readText())
        care.apply(care.prepare("propose_game_profile", f.args(kind = "mods", name = "Original"), true))
        val original = care.inventory(true).getJSONArray("profiles").objects().single { it.getString("name") == "Original" }.getString("profile_id")
        care.apply(care.prepare("propose_game_profile", f.args("restore", "mods", "", original), true), true)
        assertEquals("original", File(f.folder, "settings.ini").readText())
        assertFalse(File(f.folder, "new.ini").exists())
        File(f.folder, "settings.ini").writeText("external edit while mods are off")
        assertTrue(runCatching { f.care().restore() }.isFailure); assertTrue(f.care().hasUndo())
        assertEquals("external edit while mods are off", File(f.folder, "settings.ini").readText())
        File(f.folder, "settings.ini").writeText("original")
        f.care().restore(); assertEquals("b_upper", File(f.folder, "settings.ini").readText())
        File(f.folder, "settings.ini").writeText("manual edit")
        assertTrue(runCatching { care.prepare("propose_game_profile", f.args("restore", "mods", "", original), true) }.isFailure)
        assertEquals("manual edit", File(f.folder, "settings.ini").readText()); assertFalse(care.hasUndo())
    }
    @Test fun modPermissionAndExistingUndoProtectAgainstUnreviewedProfileWrites() = careIo {
        val care = f.care()
        assertTrue(runCatching { care.prepare("propose_game_profile", f.args(), false) }.isFailure)
        care.apply(care.prepare("propose_game_profile", f.args(kind = "controls"), false))
        val id = care.inventory(false).getJSONArray("profiles").getJSONObject(0).getString("profile_id")
        f.container.configFile.writeText(JSONObject(f.container.configFile.readText()).put("inputType", 1).toString())
        File(f.app.noBackupFilesDir, "assistant/undo/${f.game}.json").apply { parentFile!!.mkdirs(); writeText("existing undo") }
        assertTrue(runCatching { care.prepare("propose_game_profile", f.args("restore", "controls", "", id), false) }.isFailure)
        assertEquals(1, JSONObject(f.container.configFile.readText()).getInt("inputType"))
    }
    @Test fun preflightAndImportPreparationProtectExistingEnvironmentsAndWorkBeforeFirstLaunch() = careIo {
        val item = requireNotNull(CustomGameScanner.createLibraryItemFromFolder(f.folder.path))
        var creates = 0
        val before = f.container.configFile.readBytes()
        OfflineGameImport.prepareEnvironment(f.app, item) { creates++ }
        assertEquals(0, creates); assertArrayEquals(before, f.container.configFile.readBytes())
        assertFalse(preflightBeforePlay(f.app, f.game).findings.any { it.severity == "error" })
        assertTrue(f.container.rootDir.deleteRecursively())
        assertFalse(preflightBeforePlay(f.app, f.game).findings.any { it.severity == "error" })
        f.container.rootDir.mkdirs(); File(f.container.rootDir, "save.dat").writeText("preserve")
        assertTrue(runCatching { OfflineGameImport.prepareEnvironment(f.app, item) { creates++ } }.isFailure)
        assertEquals("preserve", File(f.container.rootDir, "save.dat").readText()); assertEquals(0, creates)
        f.container.rootDir.deleteRecursively()
        OfflineGameImport.prepareEnvironment(f.app, item) { creates++; f.container.rootDir.mkdirs(); check(f.container.saveDataChecked()) }
        assertEquals(1, creates)
        File(f.folder, "game.exe").delete()
        assertTrue(preflightBeforePlay(f.app, f.game).findings.any { it.title == "Startfilen saknas" })
    }
}
