package app.gamenative.assistant

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.IntentLaunchManager
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineGameUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun landscapeReviewInstallerThenFindGameAndUndo() = exercise("landscape")
    @Test @Config(qualifiers = "w360dp-h800dp-port") fun portraitReviewInstallerThenFindGameAndUndo() = exercise("portrait")

    private fun exercise(orientation: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PrefManager.init(app); SteamService.keepAlive = false; LiveGameSession.end()
        DownloadService::class.java.getDeclaredField("baseDataDirPath").apply { isAccessible = true; set(null, app.filesDir.canonicalPath) }; DownloadService::class.java.getDeclaredField("baseExternalAppDirPath").apply { isAccessible = true; set(null, "") }
        PrefManager.useExternalStorage = false; PrefManager.externalStoragePath = ""
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        val game = "CUSTOM_GAME_42"
        val folder = File(app.filesDir.canonicalFile, "Offline Game").apply { mkdirs() }
        File(folder, ".gamenative").writeText("{\"appId\":42}")
        File(folder, ".gamenative-offline").writeText("1")
        testWindowsExe(File(folder, "setup.exe"))
        PrefManager.customGameManualFolders = setOf(folder.path); CustomGameScanner.invalidateCache()
        val root = File(ImageFs.find(app).rootDir, "home/xuser-$game").apply { mkdirs() }
        val container = Container(game).apply { setRootDir(root); drives = "A:${folder.path}"; executablePath = "before.exe"; execArgs = "--old"; inputType = 3 }
        assertTrue(container.saveDataChecked())
        val original = container.configFile.readText()
        var chooseGame = false
        var requests = 0
        val provider = object : GameAiProvider {
            override suspend fun accounts() = ChatGptProvider.Accounts(listOf(ChatGptProvider.Account("a", "Test", true, true)), "a")
            override suspend fun select(id: String) = Unit
            override suspend fun signIn(existingId: String?, openBrowser: suspend (String) -> Unit) = error("No browser")
            override suspend fun models() = listOf(ChatGptProvider.Model("m", "Test model"))
            override suspend fun verify(model: String) = "ok"
            override suspend fun signOut() = true
            override suspend fun chat(model: String, prompt: String, diagnostics: String?, history: List<AssistantProtocol.ChatTurn>) = error("Agent expected")
            override suspend fun agentTurn(request: JSONObject): AssistantProtocol.Reply {
                requests++
                assertTrue(request.getJSONArray("tools").toString().contains("inspect_offline_installation"))
                val input = request.getJSONArray("input")
                val outputs = (0 until input.length()).map { input.getJSONObject(it) }.filter { it.optString("type") == "function_call_output" }
                if (outputs.isEmpty()) return call("inspect_offline_installation")
                val data = JSONObject(outputs[0].getString("output"))
                assertFalse(data.toString(), data.has("error"))
                if (outputs.size == 1) {
                    val list = data.getJSONArray("executables")
                    val path = if (chooseGame) "Wine/Games/Offline Game/game.exe" else "Spelmapp/setup.exe"
                    val file = (0 until list.length()).map { list.getJSONObject(it) }.single { it.getString("path") == path }
                    return call("propose_offline_action", JSONObject().put("action", if (chooseGame) "select_game_exe" else "run_installer")
                        .put("file_id", file.getString("file_id")).put("reason", "Granska den hittade EXE-filen för detta spel.").toString())
                }
                val proposal = JSONObject(outputs[1].getString("output"))
                assertFalse(proposal.toString(), proposal.has("error")); assertFalse(proposal.getBoolean("executed"))
                return AssistantProtocol.Reply("Granska förslaget nedan. Installation och spelstart behöver provas på enheten.", null)
            }
            private fun call(name: String, args: String = "{}") = AssistantProtocol.completedResponse(JSONObject().put("status", "completed").put("output",
                JSONArray().put(JSONObject().put("type", "function_call").put("namespace", "game").put("name", name).put("call_id", name).put("arguments", args))))
        }
        val conversations = object : ConversationStore {
            override fun load(game: String, account: String) = ConversationStore.Saved()
            override fun save(game: String, account: String, saved: ConversationStore.Saved) = Unit
        }
        val model = GameAssistantViewModel(app, provider, conversations)
        lateinit var hostView: android.view.View
        compose.setContent { hostView = androidx.compose.ui.platform.LocalView.current; MaterialTheme(colorScheme = darkColorScheme()) { AssistantScreen(model, {}, {}) } }
        compose.runOnIdle { model.initialize(game, "Mitt offlinespel") }; idle(model)
        assertEquals(0, requests)
        compose.onNodeWithTag("offline-help").performScrollTo().performClick(); idle(model)
        assertEquals(3, requests); assertNotNull(model.state.value.offlineProposal)
        assertEquals(original, container.configFile.readText()); assertNull(shadowOf(app).nextStartedActivity)
        compose.onNodeWithTag("offline-apply").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            // Save the composed root through the test-owned Android view, including portrait wrapping.
            val view = hostView.rootView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/assistant-offline-$orientation.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithTag("offline-apply").performClick(); idle(model)
        val launch = requireNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(game, IntentLaunchManager.parseLaunchIntent(launch)!!.appId)
        assertNull(IntentLaunchManager.parseLaunchIntent(launch)!!.containerConfig)
        assertEquals("setup.exe", JSONObject(container.configFile.readText()).getString("executablePath"))
        assertTrue(model.state.value.offlineUndo); assertEquals(3, requests)
        testWindowsExe(File(root, ".wine/drive_c/Games/Offline Game/game.exe")); chooseGame = true
        compose.onNodeWithTag("offline-help").performScrollTo().performClick(); idle(model)
        compose.onNodeWithTag("offline-apply").performScrollTo().performClick(); idle(model)
        assertEquals(6, requests); assertNull(shadowOf(app).nextStartedActivity)
        val chosen = JSONObject(container.configFile.readText())
        assertEquals("C:\\Games\\Offline Game\\game.exe", chosen.getString("executablePath")); assertEquals(3, chosen.getInt("inputType"))
        compose.onNodeWithTag("offline-undo").performScrollTo().performClick(); idle(model)
        assertEquals(JSONObject(original).toString(), JSONObject(container.configFile.readText()).toString())
        assertEquals(6, requests); assertFalse(model.state.value.offlineUndo)
        assertTrue(File(root, ".wine/drive_c/Games/Offline Game/game.exe").exists())
    }
    private fun idle(model: GameAssistantViewModel) { compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); !model.state.value.busy } }
}
