package app.gamenative.assistant

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
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
        compose.onNode(isToggleable()).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.includeDiagnostics && !model.state.value.busy }
        assertTrue(store.saved.gameAccess)
        compose.onNodeWithText("Klart").performClick()
        compose.onNodeWithText("Spelåtkomst på").assertIsDisplayed()
    }
}
