package app.gamenative.assistant

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import okio.Buffer
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
class AssistantWebScreenTest {
    @get:Rule val compose = createComposeRule()
    private class Provider : GameAiProvider {
        val requests = mutableListOf<JSONObject>()
        val gate = CompletableDeferred<Unit>()
        override suspend fun accounts() = ChatGptProvider.Accounts(listOf(ChatGptProvider.Account("a", "Test", true, true)), "a")
        override suspend fun select(id: String) = Unit
        override suspend fun signIn(existingId: String?, openBrowser: suspend (String) -> Unit) = Unit
        override suspend fun models() = listOf(ChatGptProvider.Model("test", "Testmodell"))
        override suspend fun verify(model: String) = "ok"
        override suspend fun signOut() = true
        override suspend fun chat(model: String, prompt: String, diagnostics: String?, history: List<AssistantProtocol.ChatTurn>) = error("Web-aware method expected")
        override suspend fun agentTurn(request: JSONObject) = error("Game tools are disabled")
        override suspend fun chat(model: String, prompt: String, diagnostics: String?, history: List<AssistantProtocol.ChatTurn>,
            webAccess: Boolean, progress: (String) -> Unit): AssistantProtocol.Reply {
            val request = AssistantProtocol.request(model, prompt, diagnostics, history, webAccess)
            requests += request
            if (!webAccess) return AssistantProtocol.Reply("Webbsökning är avstängd. Jag har inte sökt efter nya uppgifter.", null)
            progress("Söker på webben…")
            gate.await()
            val text = "Läs projektets instruktioner före en ändring."
            val citation = JSONObject().put("type", "url_citation").put("start_index", 0).put("end_index", text.length)
                .put("title", "Projektets dokumentation").put("url", "https://github.com/utkarshdalal/GameNative")
            val part = JSONObject().put("type", "output_text").put("text", text).put("annotations", JSONArray().put(citation))
            val message = JSONObject().put("type", "message").put("role", "assistant").put("content", JSONArray().put(part))
            val response = JSONObject().put("status", "completed").put("output", JSONArray()
                .put(JSONObject().put("type", "web_search_call").put("id", "ws_1").put("status", "completed")).put(message))
            val sse = "data: ${JSONObject().put("type", "response.completed").put("response", response)}\n\n"
            return ResponsesStream.read(Buffer().writeUtf8(sse), progress = progress) { IllegalStateException("Test failure") }
        }
    }
    private class Store : ConversationStore {
        var saved = ConversationStore.Saved()
        override fun load(game: String, account: String) = saved
        override fun save(game: String, account: String, saved: ConversationStore.Saved) { this.saved = saved }
    }
    @Test fun landscapeSearchSourcesAndToggle() = exercise("landscape")
    @Test @Config(qualifiers = "w360dp-h800dp-port") fun portraitSearchSourcesAndToggle() = exercise("portrait")
    private fun exercise(orientation: String) {
        val provider = Provider(); val store = Store(); val links = mutableListOf<String>()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val model = GameAssistantViewModel(app, provider, store)
        lateinit var view: android.view.View
        compose.setContent { view = LocalView.current; MaterialTheme { AssistantScreen(model, {}, { links += it }, inGame = true) } }
        compose.runOnIdle { model.initialize("STEAM_42", "Bloodstained") }; idle(model)
        compose.onNodeWithText("Webb på").assertIsDisplayed()
        compose.onNodeWithText("Skriv ett meddelande…").performTextInput("Sök efter aktuella instruktioner för GameNative")
        compose.onNodeWithContentDescription("Skicka").performClick()
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); provider.requests.isNotEmpty() }
        compose.onAllNodesWithText("Söker på webben…", substring = true).assertCountEquals(2)
        compose.onNodeWithTag("web-toggle").assertIsNotEnabled()
        assertFalse(model.state.value.includeDiagnostics)
        compose.runOnIdle { provider.gate.complete(Unit) }; idle(model)
        assertTrue(links.isEmpty())
        val node = compose.onNodeWithText("Projektets dokumentation", substring = true)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val box = layout.getBoundingBox(layout.layoutInput.text.text.indexOf("Projektets dokumentation"))
        node.performTouchInput { click(box.center) }
        assertEquals(listOf("https://github.com/utkarshdalal/GameNative"), links)
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/assistant-web-$orientation.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("web-toggle").performClick(); idle(model)
        compose.onNodeWithText("Webb av").assertIsDisplayed()
        assertFalse(store.saved.webAccess)
        assertTrue(store.saved.history.single().displayText.contains("https://github.com/utkarshdalal/GameNative"))
        compose.onNodeWithText("Skriv ett meddelande…").performTextInput("Chatta vidare utan sökning")
        compose.onNodeWithContentDescription("Skicka").performClick(); idle(model)
        assertFalse(provider.requests.last().has("tools"))
        compose.onNodeWithText("Skriv ett meddelande…").assertIsDisplayed()
    }
    private fun idle(model: GameAssistantViewModel) {
        compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); !model.state.value.busy }
    }
}
