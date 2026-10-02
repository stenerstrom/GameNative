package app.gamenative.assistant

import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallSource
import app.gamenative.data.ModInstallStatus
import app.gamenative.db.PluviaDatabase
import app.gamenative.mods.NexusModManager
import app.gamenative.service.SteamService
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.GameMetadata
import app.gamenative.utils.GameMetadataManager
import com.winlator.xenvironment.ImageFs
import java.io.File
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

/** Exercises the actual chat, tool loop, review card and native deployment, without a real AI/network call. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ModAssistantTestApplication::class, qualifiers = "w1280dp-h800dp-land")
class AssistantModScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<ModAssistantTestApplication>()
    private val game = "CUSTOM_GAME_42"
    @After fun cleanup() { app.testDatabase.close(); CustomGameScanner.invalidateCache(); SteamService.keepAlive = false }

    @Test fun chatInvestigatesModThenReviewedLoaderAppliesAndUndoWorksAfterReopening() {
        app.testDatabase = Room.inMemoryDatabaseBuilder(app, PluviaDatabase::class.java).allowMainThreadQueries().build()
        PrefManager.init(app); SteamService.keepAlive = false
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        val root = File(app.filesDir, "selected-game").apply { mkdirs() }
        GameMetadataManager.write(root, GameMetadata(appId = 42))
        PrefManager.customGameManualFolders = setOf(root.path)
        CustomGameScanner.invalidateCache()
        val target = File(root, "settings.ini").apply { writeText("FPS=60\r\n") }
        val extracted = File(NexusModManager.cacheRoot(app, game), "extracted/test_mod").apply { mkdirs() }
        File(extracted, "settings.ini").writeText("FPS=30\r\n")
        File(extracted, "dinput8.dll").writeBytes(byteArrayOf(0x4d, 0x5a, 0, 1, 2))
        File(extracted, "README.txt").writeText("Copy all files to the game folder.")
        runBlocking { app.testDatabase.modDao().upsertInstall(ModInstall("test_mod", game, ModInstallSource.LOCAL_ARCHIVE.name,
            modName = "Test loader", fileName = "test.zip", archivePath = "", extractedPath = extracted.path)) }
        val store = object : ConversationStore {
            var saved = ConversationStore.Saved(gameAccess = true, modAccess = true)
            override fun load(game: String, account: String) = saved
            override fun save(game: String, account: String, saved: ConversationStore.Saved) { this.saved = saved }
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
                if (outputs.isEmpty()) return tool("read_mods", JSONObject())
                val id = JSONObject(outputs[0].getString("output")).getJSONArray("mods").getJSONObject(0).getString("mod_id")
                if (outputs.size == 1) return tool("inspect_mod", JSONObject().put("mod_id", id))
                if (outputs.size == 2) return tool("read_mod_document", JSONObject().put("mod_id", id).put("path", "README.txt"))
                if (outputs.size == 3) {
                    val plans = JSONObject(outputs[1].getString("output")).getJSONArray("installationPlans")
                    val plan = (0 until plans.length()).map { plans.getJSONObject(it) }.first { it.getString("label").contains("behåll paketets") }
                    return tool("propose_mod_action", JSONObject().put("mod_id", id).put("action", "install")
                        .put("plan_id", plan.getString("plan_id")).put("reason", "Filerna ska ligga i spelmappen enligt README."))
                }
                assertFalse(JSONObject(outputs.last().getString("output")).getBoolean("applied"))
                return AssistantProtocol.Reply("Granska modden nedan.", null)
            }
            private fun tool(name: String, args: JSONObject) = AssistantProtocol.completedResponse(JSONObject().put("status", "completed")
                .put("output", JSONArray().put(JSONObject().put("type", "function_call").put("namespace", "game").put("name", name)
                    .put("call_id", "call_$name").put("arguments", args.toString()))))
        }
        var model = GameAssistantViewModel(app, provider, store)
        val current = androidx.compose.runtime.mutableStateOf(model)
        compose.setContent { MaterialTheme { AssistantScreen(current.value, {}, {}) } }
        compose.runOnIdle { model.initialize(game, "Testspel") }
        fun idle() = compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); !model.state.value.busy }
        idle()
        compose.onNodeWithText("Skriv ett meddelande…").performTextInput("Installera min mod")
        compose.onNodeWithContentDescription("Skicka").performClick()
        idle()
        assertNotNull(model.state.value.modProposal)
        assertEquals("FPS=60\r\n", target.readText())
        compose.onNodeWithText("Tillämpa modändring").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("mod-loader-approval").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Tillämpa modändring").performScrollTo().performClick()
        idle()
        assertTrue(model.state.value.backup)
        assertEquals("FPS=30\r\n", target.readText())
        assertArrayEquals(File(extracted, "dinput8.dll").readBytes(), File(root, "dinput8.dll").readBytes())
        compose.runOnIdle { model = GameAssistantViewModel(app, provider, store); current.value = model; model.initialize(game, "Testspel") }
        idle()
        assertTrue(model.state.value.modAccess)
        assertNull(model.state.value.modProposal)
        compose.onNodeWithText("Ångra ändring").performScrollTo().performClick()
        idle()
        assertFalse(model.state.value.backup)
        assertEquals("FPS=60\r\n", target.readText())
        assertFalse(File(root, "dinput8.dll").exists())
        runBlocking { assertEquals(ModInstallStatus.READY.name, app.testDatabase.modDao().getInstall("test_mod")!!.status) }
        compose.onNodeWithText("Skriv ett meddelande…").assertIsDisplayed()
    }
}
