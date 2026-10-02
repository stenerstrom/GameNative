package app.gamenative.assistant

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
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
class GameOptimizationUiTest {
    @get:Rule val compose = createComposeRule()
    private val game = "STEAM_42"
    @After fun cleanup() = runBlocking {
        InGameAssistantUi.close(game); GameOptimizationUi.clear(game); LiveGameSession.end()
        GameOptimizationSession.awaitSaved(); GameOptimizationSession.capture.reset(); SteamService.keepAlive = false
    }
    @Test fun landscapeMeasureThenAskCodexWithoutChangingWorkingControls() = exercise("landscape")
    @Test @Config(qualifiers = "w600dp-h960dp-port")
    fun portraitMeasureThenAskCodexWithoutChangingWorkingControls() = exercise("portrait")

    private fun exercise(orientation: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PrefManager.init(app); PluviaApp.isActivityInForeground = true
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        val root = File(ImageFs.find(app).rootDir, "home/xuser-$game").apply { mkdirs() }
        val container = Container(game).apply { setRootDir(root); screenSize = "1920x1080"; inputType = 3; isSdlControllerAPI = true }
        assertTrue(container.saveDataChecked())
        val original = container.configFile.readBytes()
        SteamService.keepAlive = true
        LiveGameSession.begin(game, false); GameOptimizationSession.attach(app, game, container, false)
        var requests = 0
        val provider = object : GameAiProvider {
            override suspend fun accounts() = ChatGptProvider.Accounts(listOf(ChatGptProvider.Account("a", "Test", true, true)), "a")
            override suspend fun select(id: String) = Unit
            override suspend fun signIn(existingId: String?, openBrowser: suspend (String) -> Unit) = error("Not expected")
            override suspend fun models() = listOf(ChatGptProvider.Model("model", "Test model"))
            override suspend fun verify(model: String) = "verified"
            override suspend fun signOut() = true
            override suspend fun chat(model: String, prompt: String, diagnostics: String?, history: List<AssistantProtocol.ChatTurn>) = error("Tool access expected")
            override suspend fun agentTurn(request: JSONObject): AssistantProtocol.Reply {
                requests++
                val input = request.getJSONArray("input")
                val outputs = (0 until input.length()).map { input.getJSONObject(it) }.filter { it.optString("type") == "function_call_output" }
                if (outputs.isEmpty()) return tool("read_optimization_context")
                val context = JSONObject(outputs[0].getString("output"))
                assertEquals(60, context.getJSONObject("optimization").getInt("targetFps"))
                assertEquals("Main hall", context.getJSONObject("optimization").getString("scene"))
                assertTrue(context.getJSONObject("optimization").getJSONArray("runs").getJSONObject(0).getBoolean("usable"))
                assertFalse(context.getJSONObject("comparison").getBoolean("eligible")) // One run is not before/after evidence.
                assertEquals("BOTH", context.getJSONObject("settings").getJSONObject("configuration").getJSONObject("controller").getString("inputApi"))
                if (outputs.size == 1) return tool("propose_settings", """{"changes":[{"setting":"screenSize","value":"1280x720"}],"reason":"Prova mindre GPU-arbete och mät samma scen igen. Ingen förbättring har ännu visats."}""")
                return AssistantProtocol.Reply("Referensen är sparad. Granska upplösningsförsöket och jämför en ny mätning i samma scen. Kontrollinställningarna lämnas orörda.", null)
            }
            private fun tool(name: String, args: String = "{}") = AssistantProtocol.completedResponse(JSONObject().put("status", "completed")
                .put("output", JSONArray().put(JSONObject().put("type", "function_call").put("namespace", "game").put("name", name)
                    .put("call_id", "call_$name").put("arguments", args))))
        }
        val store = object : ConversationStore {
            override fun load(game: String, account: String) = ConversationStore.Saved(gameAccess = true)
            override fun save(game: String, account: String, saved: ConversationStore.Saved) = Unit
        }
        val model = GameAssistantViewModel(app, provider, store)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box { Text("Spelet körs"); AssistantQuickMenuButton(game) { }; GameOptimizationOverlay(game) }
                if (InGameAssistantUi.isOpenFor(game)) InGameAssistantPanel(model, { InGameAssistantUi.close(game) }) { error("No browser") }
            }
        }
        compose.runOnIdle { model.initialize(game, "Bloodstained") }
        idle(model)
        compose.onNodeWithTag("open-in-game-assistant").performClick()
        compose.onNodeWithTag("optimization-open").performClick()
        compose.waitUntil(10_000) { compose.onNodeWithTag("optimization-target-60").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled).not() }
        compose.onNodeWithTag("optimization-target-60").performClick()
        compose.onNodeWithTag("optimization-scene").performTextInput("Main hall")
        compose.waitUntil(10_000) { compose.onNodeWithTag("optimization-measure").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled).not() }
        compose.onNodeWithTag("optimization-measure").performScrollTo().performClick()
        compose.waitUntil(10_000) { !InGameAssistantUi.isOpenFor(game) }
        compose.onNodeWithTag("in-game-assistant-panel").assertDoesNotExist()
        compose.onNodeWithTag("optimization-overlay").assertIsDisplayed()
        assertEquals(0, requests)
        compose.runOnIdle {
            repeat(134) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(500))
                LiveGameSession.metrics(LiveGameSession.token(), liveTestMetrics(System.currentTimeMillis()), 1)
            }
        }
        runBlocking { GameOptimizationSession.awaitSaved() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Mätningen avslutad").assertIsDisplayed()
        assertEquals(0, requests)
        compose.onNodeWithTag("optimization-review").performClick()
        compose.onNodeWithTag("optimization-analyze").performScrollTo()
        compose.waitUntil(10_000) { compose.onNodeWithTag("optimization-analyze").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled).not() }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/assistant-optimization-$orientation.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithTag("optimization-analyze").performClick()
        // Saving the local goal is asynchronous and precedes the ViewModel's busy state.
        compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); requests > 0 }
        idle(model)
        assertEquals(3, requests)
        assertEquals(setOf("screenSize"), model.state.value.proposal!!.settings.keys)
        compose.onNodeWithText("Tillämpa").performScrollTo().assertIsNotEnabled()
        assertArrayEquals(original, container.configFile.readBytes())
        assertFalse(model.state.value.backup)
        assertEquals(0, GameOptimizationSession.store(app, "GOG_42").read().getJSONArray("runs").length())
    }
    private fun idle(model: GameAssistantViewModel) { compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); !model.state.value.busy } }
}
