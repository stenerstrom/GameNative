package app.gamenative.assistant

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AssistantChatViewModelTest {
    private lateinit var app: Application
    private lateinit var provider: FakeProvider
    private lateinit var conversations: MemoryConversations
    private val game = "STEAM_42"

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        provider = FakeProvider()
        conversations = MemoryConversations()
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
    }

    private fun opened(): GameAssistantViewModel = GameAssistantViewModel(app, provider, conversations).also { it.initialize(game); idle(it) }
    private fun idle(model: GameAssistantViewModel) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (!model.state.value.busy) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("View model stayed busy: ${model.state.value.status}")
    }
    private fun container() {
        val root = File(ImageFs.find(app).rootDir, "home/xuser-$game").apply { mkdirs() }
        assertTrue(Container(game).apply { setRootDir(root); screenSize = "1920x1080" }.saveDataChecked())
    }
    private fun send(model: GameAssistantViewModel, text: String) { model.prompt(text); model.send(); idle(model) }

    @Test fun reopensConnectedAccountAndSendsWithoutAContainerLogOrDiagnostics() {
        val model = opened()
        assertEquals("from-catalog", model.state.value.selectedModel)
        assertEquals(1, provider.modelLoads)
        assertTrue(provider.calls.isEmpty()) // Catalog loading never performs inference.
        assertTrue(model.state.value.diagnostics.isEmpty())
        model.prompt("Hello")
        assertNull(model.state.value.sendBlockReason)
        model.send(); idle(model)
        assertNull(provider.calls.single().diagnostics)
        assertEquals("Hello", model.state.value.history.single().user)
        assertTrue(model.state.value.verified)
        val reopened = opened()
        assertEquals("from-catalog", reopened.state.value.selectedModel)
        assertEquals("Hello", reopened.state.value.history.single().user)
    }

    @Test fun followUpIncludesPriorExchangeAndClearStartsFresh() {
        val model = opened()
        send(model, "Hello")
        send(model, "Explain that")
        assertEquals("Hello", provider.calls.last().history.single().user)
        model.clearChat(); idle(model)
        send(model, "New topic")
        assertTrue(provider.calls.last().history.isEmpty())
    }

    @Test fun optionalSettingsAttachWithoutADebugRunAndCanBeRemoved() {
        container()
        val model = opened()
        model.attachDiagnostics(true); idle(model)
        assertTrue(model.state.value.includeDiagnostics)
        assertTrue(model.state.value.diagnostics.isEmpty()) // No manual diagnostic step.
        send(model, "Help with the settings")
        assertTrue(provider.agentRequests.isNotEmpty())
        assertTrue(provider.agentRequests.last().getJSONArray("input").toString().contains("1920x1080"))
        model.attachDiagnostics(false); idle(model)
        send(model, "General question")
        assertNull(provider.calls.last().diagnostics)
    }

    @Test fun catalogueFailureExplainsDisabledSendAndCanBeRetried() {
        provider.catalogFailure = true
        val model = opened()
        model.prompt("Hello")
        assertTrue(model.state.value.sendBlockReason!!.contains("Refresh models"))
        model.send(); idle(model)
        assertTrue(provider.calls.isEmpty())
        provider.catalogFailure = false
        model.refreshModels(); idle(model)
        assertNull(model.state.value.sendBlockReason)
    }

    @Test fun switchingAccountsClearsHistoryAndOptInAttachment() {
        container()
        val model = opened()
        model.attachDiagnostics(true); idle(model)
        send(model, "First account")
        model.select("second"); idle(model)
        assertTrue(model.state.value.history.isEmpty())
        assertFalse(model.state.value.includeDiagnostics)
        assertEquals("from-catalog", model.state.value.selectedModel)
        model.select("first"); idle(model)
        assertEquals("First account", model.state.value.history.single().user)
        assertTrue(model.state.value.includeDiagnostics)
    }

    @Test fun gameAccessAndTranscriptPersistButPendingApprovalsDoNot() {
        container()
        val model = opened()
        model.attachDiagnostics(true); idle(model)
        provider.agentProposal = org.json.JSONObject("""{"changes":[{"setting":"inputApi","value":"XINPUT"}],"reason":"test"}""")
        send(model, "Test controller")
        assertNotNull(model.state.value.proposal)
        val reopened = opened()
        assertTrue(reopened.state.value.includeDiagnostics)
        assertEquals("Test controller", reopened.state.value.history.single().user)
        assertNull(reopened.state.value.proposal)
        reopened.clearChat(); idle(reopened)
        assertTrue(opened().state.value.history.isEmpty())
    }

    @Test fun failureKeepsMessageForRetryAndDoesNotAcceptReplyOrProposal() {
        val model = opened()
        provider.chatFailure = true
        send(model, "Please retry this")
        assertEquals("Please retry this", model.state.value.prompt)
        assertTrue(model.state.value.history.isEmpty())
        assertNull(model.state.value.proposal)
        assertFalse(model.state.value.verified)
    }

    @Test fun unsolicitedProposalWithoutAttachmentIsRejected() {
        val model = opened()
        provider.reply = AssistantProtocol.Reply("Apply this", ConfigProposal(30, null, "Test"))
        send(model, "Hello")
        assertNull(model.state.value.proposal)
        assertFalse(model.state.value.verified)
        assertTrue(model.state.value.history.isEmpty())
    }

    @Test fun controllerChatStagesChangeUntilApplyThenRefreshesSnapshotAndSupportsUndo() {
        container()
        SteamService.keepAlive = false
        val file = ContainerUtils.getContainer(app, game).configFile
        val original = file.readText()
        val model = opened()
        model.attachDiagnostics(true); idle(model)
        provider.agentProposal = org.json.JSONObject("""{"changes":[{"setting":"inputApi","value":"XINPUT"}],"reason":"Test XInput"}""")
        send(model, "Enable XInput")
        assertNotNull(model.state.value.proposal)
        assertEquals(original, file.readText()) // AI response does not apply anything.
        assertFalse(model.state.value.backup)
        model.apply(); idle(model)
        assertEquals(2, org.json.JSONObject(file.readText()).getInt("inputType"))
        assertNull(model.state.value.proposal)
        assertTrue(model.state.value.backup)
        assertTrue(model.state.value.diagnostics.contains("XINPUT"))
        val reopened = opened()
        assertTrue(reopened.state.value.backup)
        reopened.restore(); idle(reopened)
        assertEquals(org.json.JSONObject(original).toString(), org.json.JSONObject(file.readText()).toString())
        assertFalse(reopened.state.value.backup)
    }

    @Test fun cancellingRequestLeavesConversationUnchangedAndAllowsAnotherMessage() {
        val model = opened()
        provider.gate = CompletableDeferred()
        model.prompt("Hello"); model.send()
        shadowOf(Looper.getMainLooper()).idle()
        model.cancel(); idle(model)
        assertTrue(model.state.value.history.isEmpty())
        assertFalse(model.state.value.verified)
        assertEquals("Hello", model.state.value.prompt)
        assertNull(model.state.value.sendBlockReason)
    }

    private class FakeProvider : GameAiProvider {
        data class Call(val diagnostics: String?, val history: List<AssistantProtocol.ChatTurn>)
        var selected = "first"
        var modelLoads = 0
        var catalogFailure = false
        var chatFailure = false
        var reply = AssistantProtocol.Reply("Hello from the model", null)
        var gate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<Call>()
        val agentRequests = mutableListOf<org.json.JSONObject>()
        var agentProposal: org.json.JSONObject? = null
        override suspend fun accounts() = ChatGptProvider.Accounts(listOf("first", "second").map { ChatGptProvider.Account(it, it, true, true) }, selected)
        override suspend fun select(id: String) { selected = id }
        override suspend fun signIn(existingId: String?, openBrowser: suspend (String) -> Unit) = Unit
        override suspend fun models(): List<ChatGptProvider.Model> {
            modelLoads++
            check(!catalogFailure) { "Catalog offline" }
            return listOf(ChatGptProvider.Model("from-catalog", "Available model"))
        }
        override suspend fun verify(model: String) = "Verified"
        override suspend fun signOut() = true
        override suspend fun chat(model: String, prompt: String, diagnostics: String?, history: List<AssistantProtocol.ChatTurn>): AssistantProtocol.Reply {
            calls += Call(diagnostics, history)
            gate?.await()
            check(!chatFailure) { "Network failed" }
            return reply
        }
        override suspend fun agentTurn(request: org.json.JSONObject): AssistantProtocol.Reply {
            agentRequests += org.json.JSONObject(request.toString())
            val input = request.getJSONArray("input")
            val outputs = (0 until input.length()).map { input.getJSONObject(it) }.filter { it.optString("type") == "function_call_output" }
            if (outputs.isEmpty()) return tool("read_configuration", org.json.JSONObject())
            if (outputs.size == 1 && agentProposal != null) return tool("propose_settings", agentProposal!!)
            return reply
        }
        private fun tool(name: String, args: org.json.JSONObject): AssistantProtocol.Reply {
            val item = org.json.JSONObject().put("type", "function_call").put("namespace", "game").put("name", name)
                .put("call_id", "call_$name").put("arguments", args.toString())
            return AssistantProtocol.completedResponse(org.json.JSONObject().put("status", "completed").put("output", org.json.JSONArray().put(item)))
        }
    }
    private class MemoryConversations : ConversationStore {
        private val data = mutableMapOf<Pair<String, String>, ConversationStore.Saved>()
        override fun load(game: String, account: String) = data[game to account] ?: ConversationStore.Saved()
        override fun save(game: String, account: String, saved: ConversationStore.Saved) { data[game to account] = saved }
    }
}
