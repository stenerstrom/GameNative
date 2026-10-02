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
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.service.SteamService
import com.winlator.container.Container
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
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InGameAssistantTest {
    @get:Rule val compose = createComposeRule()
    private val game = "STEAM_42"
    @After fun cleanup() { InGameAssistantUi.close(game); LiveGameSession.end(); SteamService.keepAlive = false }

    @Test fun landscapeLiveChat() = exercisePanel("landscape")

    @Test @Config(qualifiers = "w600dp-h960dp-port")
    fun portraitLiveChat() = exercisePanel("portrait")

    private fun exercisePanel(orientation: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        val root = File(ImageFs.find(app).rootDir, "home/xuser-$game").apply { mkdirs() }
        val container = Container(game).apply { setRootDir(root); screenSize = "1920x1080" }
        assertTrue(container.saveDataChecked())
        val original = container.configFile.readBytes()
        SteamService.keepAlive = true
        val token = LiveGameSession.buffer.begin(game)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        LiveGameSession.line(token, "wine: test rendering warning")
        var requests = 0
        val provider = object : GameAiProvider {
            override suspend fun accounts() = ChatGptProvider.Accounts(listOf(ChatGptProvider.Account("a", "Test", true, true)), "a")
            override suspend fun select(id: String) = Unit
            override suspend fun signIn(existingId: String?, openBrowser: suspend (String) -> Unit) = error("No browser expected")
            override suspend fun models() = listOf(ChatGptProvider.Model("model", "Test model"))
            override suspend fun verify(model: String) = "verified"
            override suspend fun signOut() = true
            override suspend fun chat(model: String, prompt: String, diagnostics: String?, history: List<AssistantProtocol.ChatTurn>) = error("Game access was granted")
            override suspend fun agentTurn(request: JSONObject): AssistantProtocol.Reply {
                requests++
                val input = request.getJSONArray("input")
                val outputs = (0 until input.length()).map { input.getJSONObject(it) }.filter { it.optString("type") == "function_call_output" }
                if (outputs.isEmpty()) {
                    LiveGameSession.metrics(token, liveTestMetrics(System.currentTimeMillis()), 1)
                    return tool("read_live_session")
                }
                val live = JSONObject(outputs[0].getString("output"))
                assertEquals("live", live.getString("status"))
                assertEquals(29.5, live.getJSONObject("current").getDouble("fps"), 0.01)
                assertTrue(live.getJSONObject("log").toString().contains("test rendering warning"))
                if (outputs.size == 1) return tool("read_configuration")
                if (outputs.size == 2) return tool("propose_settings", """{"changes":[{"setting":"fpsLimiterTarget","value":"30"},{"setting":"fpsLimiterEnabled","value":"true"}],"reason":"Testa jämnare bildtakt efter att spelet har stängts."}""")
                return AssistantProtocol.Reply("Jag läste den pågående omgången: 29,5 FPS och p95 41 ms. Det är ingen jämförbar före/efter-mätning. Granska förslaget när spelet har stängts.", null)
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
        var menuDismissals = 0
        var gameDisposed = false
        var activityLifecycle: Lifecycle? = null
        compose.setContent {
            activityLifecycle = LocalLifecycleOwner.current.lifecycle
            MaterialTheme {
                Box {
                    DisposableEffect(Unit) { onDispose { gameDisposed = true } }
                    Text("Spelet finns kvar")
                    AssistantQuickMenuButton(game) { assertTrue(InGameAssistantUi.isOpenFor(game)); menuDismissals++ }
                }
                if (InGameAssistantUi.isOpenFor(game)) InGameAssistantPanel(model, { InGameAssistantUi.close(game) }, { error("No activity launch expected") })
            }
        }
        compose.runOnIdle { model.initialize(game, "Dark Souls") }
        idle(model)
        compose.onNodeWithTag("open-in-game-assistant").performClick()
        assertEquals(Lifecycle.State.RESUMED, activityLifecycle!!.currentState)
        compose.onNodeWithContentDescription("Tillbaka till spelet").assertIsDisplayed()
        compose.onNodeWithText("Skriv ett meddelande…").assertIsDisplayed().performTextInput("Varför hackar spelet just nu?")
        assertEquals(0, requests) // Opening/polling the panel never calls a model.
        compose.onNodeWithContentDescription("Skicka").performClick()
        idle(model)
        assertEquals(4, requests)
        assertNotNull(model.state.value.proposal)
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithTag("live-session-status").assertTextContains("Live · 29.5 FPS", substring = true)
        compose.runOnIdle { LiveGameSession.paused(token, true) }
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithTag("live-session-status").assertTextContains("Spelet är pausat", substring = true)
        compose.onNodeWithText("Återuppta spelet").assertIsDisplayed()
        compose.runOnIdle { LiveGameSession.paused(token, false) }
        compose.runOnIdle {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
            LiveGameSession.metrics(token, liveTestMetrics(System.currentTimeMillis()), 1)
        }
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithTag("live-session-status").assertTextContains("Live · 29.5 FPS", substring = true)
        compose.onNodeWithText("Tillämpa").performScrollTo().assertIsNotEnabled()
        assertArrayEquals(original, container.configFile.readBytes())
        // Backend also rejects writes even if the UI were bypassed.
        compose.runOnIdle { model.apply() }
        idle(model)
        assertArrayEquals(original, container.configFile.readBytes())
        assertFalse(model.state.value.backup)
        compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val target = File("build/assistant-live-$orientation.png").apply { parentFile!!.mkdirs() }
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithContentDescription("Tillbaka till spelet").performClick()
        compose.onNodeWithTag("in-game-assistant-panel").assertDoesNotExist()
        assertFalse(gameDisposed)
        compose.onNodeWithTag("open-in-game-assistant").performClick()
        compose.onNodeWithContentDescription("Tillbaka till spelet").assertIsDisplayed()
        assertEquals(2, menuDismissals)
        assertEquals(4, requests)
        assertEquals(1, store.saved.history.size)
        assertEquals(1, model.state.value.history.size)
    }

    private fun idle(model: GameAssistantViewModel) {
        compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); !model.state.value.busy }
    }
}
