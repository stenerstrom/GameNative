package app.gamenative.assistant

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.inputcontrols.ControllerManager
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControllerTestUiTest {
    @get:Rule val compose = createComposeRule()
    private val game = "STEAM_42"

    @After fun cleanup() {
        InGameAssistantUi.close(game)
        ControllerTestUi.clear(game)
        LiveGameSession.end()
        ControllerInputTrace.buffer.reset()
        SteamService.keepAlive = false
    }

    @Test fun landscapeProbeAndReviewedFix() = exercise("landscape")

    @Test @Config(qualifiers = "w600dp-h960dp-port")
    fun portraitProbeAndReviewedFix() = exercise("portrait")

    @Test fun landscapeLiveMonitorAndCombinedAnalysis() = exercise("live-landscape", liveMonitor = true)

    @Test @Config(qualifiers = "w600dp-h960dp-port")
    fun portraitLiveMonitorAndCombinedAnalysis() = exercise("live-portrait", liveMonitor = true)

    private fun exercise(orientation: String, liveMonitor: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PrefManager.init(app)
        ControllerManager.getInstance().init(app)
        PluviaApp.isActivityInForeground = true
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        val root = File(ImageFs.find(app).rootDir, "home/xuser-$game").apply { mkdirs() }
        val container = Container(game).apply { setRootDir(root); inputType = 4 }
        assertTrue(container.saveDataChecked())
        val original = container.configFile.readBytes()
        SteamService.keepAlive = true
        val token = LiveGameSession.buffer.begin(game)
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
                if (outputs.isEmpty()) return tool("read_input_route")
                val combined = JSONObject(outputs[0].getString("output"))
                val trace = combined.getJSONObject("trace")
                assertTrue(trace.getBoolean("available"))
                assertTrue(combined.getJSONObject("controllers").has("runtimeBridge"))
                assertTrue(combined.getJSONObject("settings").has("configuration"))
                assertTrue(combined.getJSONObject("settings").has("editableSettings"))
                assertFalse(combined.getJSONObject("route").getBoolean("available")) // This UI test has no native bridge.
                assertEquals(2, trace.getJSONObject("counts").getInt("ANDROID"))
                assertEquals(0, trace.getJSONObject("counts").getInt("WINE_BUFFER"))
                assertFalse(trace.getBoolean("recording"))
                if (outputs.size == 1) return tool("propose_settings", """{"changes":[{"setting":"inputApi","value":"BOTH"}],"reason":"Testförslag: prova XInput och DirectInput efter omstart. Testet visar Android-signaler men ingen bryggskrivning; orsaken är ännu okänd."}""")
                return AssistantProtocol.Reply("Android registrerade knapparna. Testet visar ingen skrivning till Wine-bryggan. Granska förslaget efter att spelet stängts och jämför med ett nytt test.", null)
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
        var lifecycle: Lifecycle? = null
        var gameView: android.view.View? = null
        var gameDisposed = false
        compose.setContent {
            lifecycle = LocalLifecycleOwner.current.lifecycle
            gameView = androidx.compose.ui.platform.LocalView.current
            MaterialTheme {
                Box {
                    DisposableEffect(Unit) { onDispose { gameDisposed = true } }
                    Text("Spelet körs")
                    AssistantQuickMenuButton(game) { }
                    ControllerTestOverlay(game)
                }
                if (InGameAssistantUi.isOpenFor(game)) InGameAssistantPanel(model, { InGameAssistantUi.close(game) }) { error("No browser expected") }
            }
        }
        compose.runOnIdle { model.initialize(game, "Dark Souls") }
        idle(model)
        compose.onNodeWithTag("open-in-game-assistant").performClick()
        compose.onNodeWithTag("controller-test-open").performClick()
        compose.onNodeWithTag(if (liveMonitor) "controller-live-start" else "controller-test-start").performScrollTo().performClick()
        compose.onNodeWithTag("in-game-assistant-panel").assertDoesNotExist()
        compose.onNodeWithTag("controller-test-overlay").assertIsDisplayed()
        assertEquals(Lifecycle.State.RESUMED, lifecycle!!.currentState)
        assertFalse(gameDisposed)
        assertEquals(0, requests)
        compose.runOnIdle {
            // Android routing and real mapping/shared-memory writes are separately exercised in ControllerBridgeTest.
            ControllerInputTrace.buffer.record(token, ControllerTraceBuffer.Stage.ANDROID, 34, 1, mapOf("BUTTON_A" to 1f), "gameplay_dispatch")
            ControllerInputTrace.buffer.record(token, ControllerTraceBuffer.Stage.ANDROID, 34, 1, mapOf("BUTTON_A" to 0f), "gameplay_dispatch")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(21))
        }
        compose.mainClock.advanceTimeBy(500)
        if (liveMonitor) {
            assertTrue(ControllerInputTrace.summary(game)!!.active) // Outlasts the original 20-second probe.
            compose.onNodeWithTag("controller-overlay-size").performClick()
            compose.onNodeWithText("Visa mer").assertIsDisplayed().performClick()
            compose.onNodeWithText("Kontroll 1 · P1 · BUTTON_A=0").assertIsDisplayed()
            compose.runOnIdle {
                val view = gameView!!.rootView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File("build/assistant-overlay-$orientation.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } else compose.onNodeWithText("Kontrolltest klart").assertIsDisplayed()
        assertEquals(0, requests) // Ending a probe must not consume the user's plan.
        compose.onNodeWithText(if (liveMonitor) "Avsluta och granska" else "Granska testet").performClick()
        assertFalse(ControllerInputTrace.summary(game)!!.active)
        compose.onNodeWithTag("controller-test-analyze").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/assistant-controller-$orientation.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithTag("controller-test-analyze").performClick()
        idle(model)
        assertEquals(3, requests)
        assertTrue(model.state.value.history.single().user.contains("Ingen input når spelet"))
        assertNotNull(model.state.value.proposal)
        compose.onNodeWithText("Tillämpa").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { model.apply() }
        idle(model)
        assertArrayEquals(original, container.configFile.readBytes())
        assertFalse(model.state.value.backup)
    }

    private fun idle(model: GameAssistantViewModel) {
        compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); !model.state.value.busy }
    }
}
