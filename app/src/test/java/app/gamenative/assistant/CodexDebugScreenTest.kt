package app.gamenative.assistant

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.service.SteamService
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
class CodexDebugScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val game = "STEAM_42"
    private class Conversations : ConversationStore {
        private val data = mutableMapOf<String, ConversationStore.Saved>()
        override fun load(game: String, account: String) = data["$game/$account"] ?: ConversationStore.Saved()
        override fun save(game: String, account: String, saved: ConversationStore.Saved) { data["$game/$account"] = saved }
    }
    private class Provider : GameAiProvider {
        var selected = "first"; var requests = 0; var fail = false
        var outputs = ""
        override suspend fun accounts() = ChatGptProvider.Accounts(listOf("first", "second").map { ChatGptProvider.Account(it, it, true, true) }, selected)
        override suspend fun select(id: String) { selected = id }
        override suspend fun signIn(existingId: String?, openBrowser: suspend (String) -> Unit) = Unit
        override suspend fun models() = listOf(ChatGptProvider.Model("test", "Test model"))
        override suspend fun verify(model: String) = "ok"
        override suspend fun signOut() = true
        override suspend fun chat(model: String, prompt: String, diagnostics: String?, history: List<AssistantProtocol.ChatTurn>) = AssistantProtocol.Reply("Hej", null)
        override suspend fun agentTurn(request: JSONObject): AssistantProtocol.Reply {
            requests++
            if (fail) error("Offline test; rapporten finns kvar.")
            val input = request.getJSONArray("input")
            val results = (0 until input.length()).mapNotNull { input.optJSONObject(it) }.filter { it.optString("type") == "function_call_output" }
            outputs = results.joinToString { it.optString("output") }
            return when (results.size) {
                0 -> debugTestCall("read_debug_report")
                1 -> debugTestCall("search_debug_report", JSONObject().put("section", "log").put("query", "missing").put("from_ms", 0).put("to_ms", 0).put("offset", 0))
                else -> AssistantProtocol.Reply("Den valda körningen visar en saknad test.dll. Inget har ändrats.", null)
            }
        }
    }
    private fun report(launch: String, at: Long, line: String): String {
        val store = CodexDebugSession.store(app)
        val report = store.create(game, launch, at, DebugProblem.CRASH,
            JSONObject().put("configuration", JSONObject().put("screenSize", "1280x720")), "test")
        report.put("state", "ready").put("captureStartedAtMs", at)
            .put("lines", JSONArray().put(JSONObject().put("timestampMs", at + 5).put("text", line)))
        store.save(report)
        return report.getString("id")
    }
    private fun idle(model: GameAssistantViewModel) {
        compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); !model.state.value.busy }
    }
    @Test fun landscapeSelectReviewAnalyzeAndRetry() = exercise("landscape")
    @Test @Config(qualifiers = "w360dp-h800dp-port") fun portraitSelectReviewAnalyzeAndRetry() = exercise("portrait")
    private fun exercise(orientation: String) {
        PrefManager.init(app); SteamService.keepAlive = false; LiveGameSession.end()
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        val first = report("chosen-launch", 1000, "missing test.dll in chosen launch")
        report("newer-launch", 2000, "missing WRONG newer launch")
        val provider = Provider()
        val conversations = Conversations()
        val model = GameAssistantViewModel(app, provider, conversations)
        lateinit var view: android.view.View
        compose.setContent { view = LocalView.current; MaterialTheme { AssistantScreen(model, {}, {}) } }
        compose.runOnIdle { model.initialize(game, "Bloodstained", first) }; idle(model)
        assertEquals(first, model.state.value.debugReportId)
        assertEquals(0, provider.requests)
        assertFalse(model.state.value.reportAttached)
        compose.onNodeWithTag("debug-report-card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("debug-open").performClick(); idle(model)
        compose.onNodeWithText("Connect Discord").assertDoesNotExist()
        compose.onNodeWithTag("debug-review").performScrollTo()
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        compose.runOnIdle {
            val window = android.view.inspector.WindowInspector.getGlobalWindowViews().last().rootView
            val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
            window.draw(Canvas(bitmap))
            File("build/codex-debug-before-$orientation.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("debug-review").performClick()
        compose.onNodeWithText("Visa underlag före analys").assertDoesNotExist()
        compose.onNodeWithText("Översikt").assertIsDisplayed()
        compose.runOnIdle {
            val window = android.view.inspector.WindowInspector.getGlobalWindowViews().last().rootView
            val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
            window.draw(Canvas(bitmap))
            File("build/codex-debug-$orientation.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            model.closeDebug()
        }
        provider.fail = true
        compose.onNodeWithTag("debug-analyze").performScrollTo().performClick(); idle(model)
        assertEquals(1, provider.requests)
        assertTrue(model.state.value.status.contains("Offline test"))
        assertEquals(first, CodexDebugSession.store(app).read(game, first).getString("id"))
        assertTrue(model.state.value.history.isEmpty())
        compose.waitForIdle(); assertEquals(1, provider.requests) // no background retry
        provider.fail = false
        compose.onNodeWithTag("debug-analyze").performScrollTo().performClick(); idle(model)
        assertEquals(4, provider.requests)
        assertTrue(provider.outputs.contains("chosen launch"))
        assertFalse(provider.outputs.contains("WRONG newer"))
        assertTrue(model.state.value.history.single().user.contains(first))
        assertTrue(model.state.value.reportAttached)
        assertNull(model.state.value.proposal)
        val reopened = GameAssistantViewModel(app, provider, conversations)
        compose.runOnIdle { reopened.initialize(game) }; idle(reopened)
        assertEquals(first, reopened.state.value.debugReportId)
        assertTrue(reopened.state.value.reportAttached)
        assertEquals(4, provider.requests)
        compose.runOnIdle { model.select("second") }; idle(model)
        assertFalse(model.state.value.reportAttached)
        assertTrue(model.state.value.history.isEmpty())
        assertEquals(4, provider.requests)
        compose.runOnIdle { model.select("first") }; idle(model)
        assertTrue(model.state.value.reportAttached)
        compose.runOnIdle { model.detachDebug() }; idle(model)
        assertNull(conversations.load(game, "first").debugReportId)
    }
}
