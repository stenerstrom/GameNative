package app.gamenative.assistant

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land")
class AssistantScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun reviewedFileDiffCanBeAppliedAndUndoneFromChat() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        SteamService.keepAlive = false
        val file = File(ImageFs.find(app).rootDir, "home/xuser-STEAM_42/.wine/drive_c/users/steamuser/Documents/settings.ini").apply {
            parentFile!!.mkdirs(); writeText("FPS=60\r\n")
        }
        val provider = object : GameAiProvider {
            override suspend fun accounts() = ChatGptProvider.Accounts(listOf(ChatGptProvider.Account("a", "Test", true, true)), "a")
            override suspend fun select(id: String) = Unit
            override suspend fun signIn(existingId: String?, openBrowser: suspend (String) -> Unit) = Unit
            override suspend fun models() = listOf(ChatGptProvider.Model("model", "Test model"))
            override suspend fun verify(model: String) = "verified"
            override suspend fun signOut() = true
            override suspend fun chat(model: String, prompt: String, diagnostics: String?, history: List<AssistantProtocol.ChatTurn>) = error("Tools expected")
            override suspend fun agentTurn(request: JSONObject): AssistantProtocol.Reply {
                val input = request.getJSONArray("input")
                val outputs = (0 until input.length()).map { input.getJSONObject(it) }.filter { it.optString("type") == "function_call_output" }
                if (outputs.isEmpty()) return tool("list_game_files", JSONObject().put("query", "settings.ini"))
                val id = JSONObject(outputs[0].getString("output")).getJSONArray("files").getJSONObject(0).getString("file_id")
                if (outputs.size == 1) return tool("read_game_file", JSONObject().put("file_id", id))
                if (outputs.size == 2) return tool("propose_file_edit", JSONObject().put("file_id", id).put("reason", "Testa en gräns på 30 FPS.")
                    .put("replacements", JSONArray().put(JSONObject().put("old_text", "FPS=60").put("new_text", "FPS=30"))))
                return AssistantProtocol.Reply("Granska ändringen nedan.", null)
            }
            private fun tool(name: String, args: JSONObject) = AssistantProtocol.completedResponse(JSONObject().put("status", "completed")
                .put("output", JSONArray().put(JSONObject().put("type", "function_call").put("namespace", "game").put("name", name)
                    .put("call_id", "call_$name").put("arguments", args.toString()))))
        }
        val store = object : ConversationStore {
            override fun load(game: String, account: String) = ConversationStore.Saved(gameAccess = true, fileAccess = true)
            override fun save(game: String, account: String, saved: ConversationStore.Saved) = Unit
        }
        val model = GameAssistantViewModel(app, provider, store)
        compose.setContent { MaterialTheme { AssistantScreen(model, {}, {}) } }
        compose.runOnIdle { model.initialize("STEAM_42", "Testspel") }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); !model.state.value.busy }
        compose.onNodeWithText("Skriv ett meddelande…").performTextInput("Ändra FPS i filen")
        compose.onNodeWithContentDescription("Skicka").performClick()
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.fileProposal != null && !model.state.value.busy }
        compose.onNodeWithText("Föreslagen filändring").assertIsDisplayed()
        compose.onNodeWithText("@@ rad 1 @@\n- FPS=60\n+ FPS=30").assertIsDisplayed()
        assertEquals("FPS=60\r\n", file.readText())
        compose.onNodeWithText("Tillämpa filändring").performClick()
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.backup && !model.state.value.busy }
        assertEquals("FPS=30\r\n", file.readText())
        compose.onNodeWithText("Ångra ändring").performClick()
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); !model.state.value.backup && !model.state.value.busy }
        assertEquals("FPS=60\r\n", file.readText())
        compose.onNodeWithText("Skriv ett meddelande…").assertIsDisplayed()
    }
    @Test fun simpleChatKeepsComposerVisibleAndMovesAccountAndDataAccessOutOfTranscript() {
        val provider = object : GameAiProvider {
            override suspend fun accounts() = ChatGptProvider.Accounts(listOf(ChatGptProvider.Account("a", "Test account", true, true)), "a")
            override suspend fun select(id: String) = Unit
            override suspend fun signIn(existingId: String?, openBrowser: suspend (String) -> Unit) = Unit
            override suspend fun models() = listOf(ChatGptProvider.Model("model", "Test model"))
            override suspend fun verify(model: String) = "verified"
            override suspend fun signOut() = true
            override suspend fun chat(model: String, prompt: String, diagnostics: String?, history: List<AssistantProtocol.ChatTurn>) = AssistantProtocol.Reply("**Hej!** Vad behöver du hjälp med?", null)
            override suspend fun agentTurn(request: JSONObject) = error("No access granted")
        }
        val store = object : ConversationStore {
            var saved = ConversationStore.Saved()
            override fun load(game: String, account: String) = saved
            override fun save(game: String, account: String, saved: ConversationStore.Saved) { this.saved = saved }
        }
        val model = GameAssistantViewModel(ApplicationProvider.getApplicationContext<Application>(), provider, store)
        compose.setContent { MaterialTheme { AssistantScreen(model, {}, {}) } }
        compose.runOnIdle { model.initialize("STEAM_42", "Dark Souls") }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); !model.state.value.busy }
        compose.onNodeWithText("Dark Souls").assertIsDisplayed()
        compose.onNodeWithText("Skriv ett meddelande…").assertIsDisplayed()
        compose.onNodeWithText("Optional diagnostic attachment").assertDoesNotExist()
        compose.onNodeWithText("Konto och modell").assertDoesNotExist()
        compose.onNodeWithText("Skriv ett meddelande…").performTextInput("Hej")
        compose.onNodeWithContentDescription("Skicka").performClick()
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.history.isNotEmpty() && !model.state.value.busy }
        compose.onNodeWithText("Hej! Vad behöver du hjälp med?").assertIsDisplayed()
        compose.onNodeWithText("Skriv ett meddelande…").assertIsDisplayed()
        compose.onNodeWithText("Ge spelåtkomst").performClick()
        compose.onNodeWithText("Tillåt spelverktyg").assertIsDisplayed()
        compose.onNodeWithTag("game-access").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.includeDiagnostics && !model.state.value.busy }
        assertTrue(store.saved.gameAccess)
        assertFalse(store.saved.fileAccess)
        compose.onNodeWithTag("file-access").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.fileAccess && !model.state.value.busy }
        assertTrue(store.saved.fileAccess)
        compose.onNodeWithText("Klart").performScrollTo().performClick()
        compose.onNodeWithText("Spel- och filåtkomst på").assertIsDisplayed()
    }
}
