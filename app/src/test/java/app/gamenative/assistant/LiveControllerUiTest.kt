package app.gamenative.assistant

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.inputcontrols.ControllerManager
import com.winlator.widget.XServerRendererView
import com.winlator.winhandler.WinHandler
import com.winlator.xenvironment.ImageFs
import com.winlator.xserver.XServer
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land",
    shadows = [ShadowControllerWinHandlerNative::class], instrumentedPackages = ["com.winlator.winhandler"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveControllerUiTest {
    @get:Rule val compose = createComposeRule()
    private val game = "STEAM_42"
    @After fun cleanup() {
        InGameAssistantUi.close(game); ControllerTestUi.clear(game)
        LiveGameSession.end(); SteamService.keepAlive = false; PluviaApp.xServerView = null
    }
    @Test fun landscapeTrialAndUndoPreserveExistingDurableBackup() = exercise("landscape")
    @Test @Config(qualifiers = "w600dp-h960dp-port")
    fun portraitTrialAndUndoPreserveExistingDurableBackup() = exercise("portrait")

    private fun exercise(orientation: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PrefManager.init(app); ControllerManager.getInstance().init(app)
        PluviaApp.isActivityInForeground = true
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        val root = File(ImageFs.find(app).rootDir, "home/xuser-$game").apply { mkdirs() }
        val container = Container(game).apply { setRootDir(root); inputType = WinHandler.PreferredInputApi.DINPUT.ordinal }
        assertTrue(container.saveDataChecked())
        val tools = GameAssistantTools(app, game)
        val snapshot = tools.readDiagnostics()
        tools.applyValidated(ConfigProposal(null, null, "Earlier saved change", inputApi = ControllerInputApi.XINPUT), snapshot.hash)
        val savedConfig = container.configFile.readBytes()
        val backup = File(app.noBackupFilesDir, "assistant/undo/$game.json")
        val savedBackup = backup.readBytes()

        SteamService.keepAlive = true
        val token = LiveGameSession.buffer.begin(game)
        val server = mock<XServer>()
        val renderer = mock<XServerRendererView>().also {
            whenever(it.context).thenReturn(app); whenever(it.getxServer()).thenReturn(server)
        }
        val bridge = WinHandler(server, renderer)
        whenever(server.winHandler).thenReturn(bridge)
        WinHandler::class.java.getDeclaredField("assistantSessionToken").apply { isAccessible = true; set(bridge, token) }
        WinHandler::class.java.getDeclaredField("running").apply { isAccessible = true; setBoolean(bridge, true) }
        bridge.setPreferredInputApi(WinHandler.PreferredInputApi.XINPUT)
        PluviaApp.xServerView = renderer
        var requests = 0
        val provider = object : GameAiProvider {
            override suspend fun accounts() = ChatGptProvider.Accounts(listOf(ChatGptProvider.Account("a", "Test", true, true)), "a")
            override suspend fun select(id: String) = Unit
            override suspend fun signIn(existingId: String?, openBrowser: suspend (String) -> Unit) = error("No sign-in expected")
            override suspend fun models() = listOf(ChatGptProvider.Model("model", "Test model"))
            override suspend fun verify(model: String) = "verified"
            override suspend fun signOut() = true
            override suspend fun chat(model: String, prompt: String, diagnostics: String?, history: List<AssistantProtocol.ChatTurn>) = error("Game access is granted")
            override suspend fun agentTurn(request: JSONObject): AssistantProtocol.Reply {
                requests++
                val input = request.getJSONArray("input")
                val outputs = (0 until input.length()).map { input.getJSONObject(it) }.filter { it.optString("type") == "function_call_output" }
                return when (outputs.size) {
                    0 -> tool("inspect_controllers")
                    1 -> {
                        assertTrue(JSONObject(outputs[0].getString("output")).getJSONObject("liveControllerTrial").getBoolean("available"))
                        tool("read_configuration")
                    }
                    2 -> tool("propose_settings", """{"changes":[{"setting":"inputApi","value":"BOTH"}],"reason":"Prova båda API:erna i den äldre bryggan. Det ändrar inte SDL:s startval."}""")
                    else -> {
                        assertTrue(outputs[2].getString("output").contains("awaiting_user_approval"))
                        AssistantProtocol.Reply("Du kan prova bryggan live. Din sparade ångrapunkt finns kvar; kontrollera om spelet reagerar.", null)
                    }
                }
            }
            private fun tool(name: String, args: String = "{}") = AssistantProtocol.completedResponse(JSONObject().put("status", "completed")
                .put("output", JSONArray().put(JSONObject().put("type", "function_call").put("namespace", "game").put("name", name)
                    .put("call_id", "call_$name").put("arguments", args))))
        }
        val store = object : ConversationStore {
            var saved = ConversationStore.Saved(gameAccess = true)
            override fun load(game: String, account: String) = saved
            override fun save(game: String, account: String, saved: ConversationStore.Saved) { this.saved = saved }
        }
        val model = GameAssistantViewModel(app, provider, store)
        compose.setContent { MaterialTheme { InGameAssistantPanel(model, {}, {}) } }
        compose.runOnIdle { model.initialize(game, "Dark Souls") }
        idle(model)
        assertTrue(model.state.value.backup)
        compose.onNodeWithText("Skriv ett meddelande…").performTextInput("Prova kontrollbryggan utan att starta om.")
        compose.onNodeWithContentDescription("Skicka").performClick()
        idle(model)
        assertEquals(4, requests)
        compose.onNodeWithTag("controller-try-live").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/assistant-live-controller-$orientation.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithTag("controller-try-live").performClick()
        idle(model)
        assertEquals("BOTH", bridge.assistantControllerStatus.getString("inputApi"))
        assertArrayEquals(savedConfig, container.configFile.readBytes())
        assertArrayEquals(savedBackup, backup.readBytes())
        compose.onNodeWithTag("controller-undo-live").assertIsDisplayed().performClick()
        idle(model)
        assertEquals("XINPUT", bridge.assistantControllerStatus.getString("inputApi"))
        compose.onNodeWithTag("controller-undo-live").assertDoesNotExist()
        assertArrayEquals(savedConfig, container.configFile.readBytes())
        assertArrayEquals(savedBackup, backup.readBytes())
        assertTrue(SteamService.keepAlive)
        assertEquals(4, requests) // Local trial/undo never makes another AI request.
        compose.onNodeWithTag("controller-test-open").performClick()
        compose.onNodeWithTag("controller-reconnect").assertIsDisplayed().performClick()
        idle(model)
        assertFalse(bridge.assistantControllerStatus.getBoolean("reconnecting"))
        assertArrayEquals(savedBackup, backup.readBytes())
        assertArrayEquals(savedConfig, container.configFile.readBytes())
        assertEquals(4, requests)
    }
    private fun idle(model: GameAssistantViewModel) {
        compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); !model.state.value.busy }
    }
}
