package app.gamenative.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ModAssistantTestApplication::class, qualifiers = "w1280dp-h800dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameCareUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var f: GameCareFixture
    @After fun cleanup() { if (::f.isInitialized) f.close() }
    @Test fun landscapeSaveReviewRestoreUndoWithoutAiRequests() = exercise("landscape")
    @Test @Config(qualifiers = "w360dp-h800dp-port") fun portraitSaveReviewRestoreUndoWithoutAiRequests() = exercise("portrait")
    private fun exercise(orientation: String) {
        f = GameCareFixture(ApplicationProvider.getApplicationContext())
        var requests = 0
        val provider = object : GameAiProvider {
            override suspend fun accounts() = ChatGptProvider.Accounts(listOf(ChatGptProvider.Account("a", "Test", true, true)), "a")
            override suspend fun select(id: String) = Unit
            override suspend fun signIn(existingId: String?, openBrowser: suspend (String) -> Unit) = Unit
            override suspend fun models() = listOf(ChatGptProvider.Model("m", "Test model"))
            override suspend fun verify(model: String) = "ok"
            override suspend fun signOut() = true
            override suspend fun chat(model: String, prompt: String, diagnostics: String?, history: List<AssistantProtocol.ChatTurn>): AssistantProtocol.Reply { requests++; error("Unexpected network request") }
            override suspend fun agentTurn(request: JSONObject): AssistantProtocol.Reply { requests++; error("Unexpected network request") }
        }
        val store = object : ConversationStore {
            override fun load(game: String, account: String) = ConversationStore.Saved()
            override fun save(game: String, account: String, saved: ConversationStore.Saved) = Unit
        }
        val model = GameAssistantViewModel(f.app, provider, store)
        lateinit var host: android.view.View
        compose.setContent { host = androidx.compose.ui.platform.LocalView.current; MaterialTheme(colorScheme = darkColorScheme()) { AssistantScreen(model, {}, {}) } }
        compose.runOnIdle { model.initialize(f.game, "Mitt spel") }; idle(model)
        compose.onNodeWithText("Spelverktyg").performClick(); idle(model)
        compose.onNodeWithTag("care-tab-Återställningsprofiler").performScrollTo().performClick()
        idle(model)
        compose.onNodeWithTag("care-profile-name").performScrollTo().performTextInput("Fungerande 120 FPS")
        compose.runOnIdle {
            val view = android.view.inspector.WindowInspector.getGlobalWindowViews().last().rootView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/assistant-care-$orientation.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("care-save-profile").performScrollTo().performClick(); idle(model)
        assertNotNull(model.state.value.careProposal)
        assertFalse(model.state.value.careProposal!!.changesMods)
        compose.onNodeWithTag("care-apply").performScrollTo().assertIsEnabled().performClick(); idle(model)
        assertFalse(model.state.value.careUndo); assertEquals(0, requests)
        val care = f.care()
        val id = careIo { care.inventory(false).getJSONArray("profiles").getJSONObject(0).getString("profile_id") }
        f.container.configFile.writeText(JSONObject(f.container.configFile.readText()).put("screenSize", "1280x720").toString())
        compose.onNodeWithText("Spelverktyg").performClick(); idle(model)
        compose.onNodeWithTag("care-tab-Återställningsprofiler").performScrollTo().performClick()
        idle(model)
        compose.onNodeWithTag("care-restore-$id").performScrollTo().performClick(); idle(model)
        compose.onNodeWithTag("care-apply").performScrollTo().performClick(); idle(model)
        assertTrue(model.state.value.status, model.state.value.careUndo)
        assertEquals("1920x1080", JSONObject(f.container.configFile.readText()).getString("screenSize"))
        compose.onNodeWithTag("care-undo").performScrollTo().performClick(); idle(model)
        assertEquals("1280x720", JSONObject(f.container.configFile.readText()).getString("screenSize"))
        assertEquals(0, requests); assertFalse(model.state.value.careUndo)
        compose.runOnIdle {
            @Suppress("UNCHECKED_CAST")
            val state = GameAssistantViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(model) as kotlinx.coroutines.flow.MutableStateFlow<AssistantUiState>
            state.value = state.value.copy(screenshot = GameScreenshot.forTest(Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888), f.game))
        }
        compose.onNodeWithContentDescription("Förhandsvisa bifogad spelbild").performClick()
        compose.onNodeWithText("Spelbild före sändning").assertIsDisplayed()
        compose.onNodeWithText("Ta bort bilden").performClick()
        assertNull(model.state.value.screenshot); assertEquals(0, requests)
    }
    private fun idle(model: GameAssistantViewModel) { compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); !model.state.value.busy } }
}
