package app.gamenative.assistant

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.BuildConfig
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameAssistantActivity : ComponentActivity() {
    private val model: GameAssistantViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.AI_ASSISTANT_ENABLED) { finish(); return }
        model.initialize(intent.getStringExtra("app_id").orEmpty(), intent.getStringExtra("game_title").orEmpty())
        setContent {
            PluviaTheme {
                AssistantScreen(model, onClose = { finish() }, openBrowser = { url ->
                    CustomTabsIntent.Builder().build().launchUrl(this, url.toUri())
                })
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.initialize(intent.getStringExtra("app_id").orEmpty(), intent.getStringExtra("game_title").orEmpty())
    }
}

data class AssistantUiState(
    val game: String = "",
    val gameTitle: String = "",
    val busy: Boolean = false,
    val status: String = "",
    val accounts: ChatGptProvider.Accounts = ChatGptProvider.Accounts(emptyList(), null),
    val models: List<ChatGptProvider.Model> = emptyList(),
    val modelsUpdatedAt: Long? = null,
    val selectedModel: String = "",
    val prompt: String = "",
    val diagnostics: String = "",
    val includeDiagnostics: Boolean = false,
    val configurationReady: Boolean = false,
    val history: List<AssistantProtocol.ChatTurn> = emptyList(),
    val answer: String = "",
    val proposal: ConfigProposal? = null,
    val backup: Boolean = false,
    val verified: Boolean = false,
    val proposalChanges: List<String> = emptyList(),
    val restoreRequested: Boolean = false,
    val activities: List<String> = emptyList(),
) {
    val sendBlockReason: String?
        get() = when {
            busy -> "Wait for the current action to finish, or cancel it."
            accounts.accounts.none { it.id == accounts.selected && it.planEnabled } -> "Connect your ChatGPT plan to send a message."
            selectedModel.isBlank() -> "No model selected. Open Account and model, then Refresh models."
            prompt.isBlank() -> "Write a message to start or continue the conversation."
            else -> null
        }
}

class GameAssistantViewModel @JvmOverloads constructor(
    application: Application,
    private val provider: GameAiProvider = ChatGptProvider(application),
    private val conversations: ConversationStore = AssistantConversations(application),
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(AssistantUiState())
    val state = mutable.asStateFlow()
    private var tools: GameAssistantTools? = null
    private var snapshotHash: String? = null
    private var job: Job? = null

    fun initialize(game: String, title: String = "") {
        if (state.value.game == game || state.value.busy) return
        job?.cancel()
        mutable.value = AssistantUiState(game = game, gameTitle = title.ifBlank { game })
        snapshotHash = null
        tools = null
        action("Opening chat…") {
            tools = GameAssistantTools(getApplication(), game, state.value.gameTitle)
            refreshAccounts()
            loadConversation()
            if (state.value.accounts.accounts.any { it.id == state.value.accounts.selected && it.planEnabled }) loadModels()
            mutable.update { it.copy(status = "") }
        }
    }
    fun prompt(value: String) { mutable.update { it.copy(prompt = value.take(4000)) } }
    fun model(value: String) { if (!state.value.busy) mutable.update { it.copy(selectedModel = value, verified = false, proposal = null, proposalChanges = emptyList()) } }
    fun editDiagnostics(value: String) { mutable.update { it.copy(diagnostics = value.take(60_000), proposal = null) } }
    fun read() = action("Reading optional game settings and log…") {
        refreshDiagnostics()
        mutable.update { it.copy(status = "Settings loaded for review. You can chat even when no log is available.") }
    }
    fun attachDiagnostics(include: Boolean) = action("Uppdaterar spelåtkomst…") {
        mutable.update { it.copy(includeDiagnostics = include, proposal = null, proposalChanges = emptyList(), restoreRequested = false,
            status = if (include) "Assistenten kan nu undersöka spelet när du skickar en fråga." else "Spelåtkomst avstängd. Du kan fortfarande chatta.") }
        saveConversation()
    }
    fun clearChat() {
        action("Ny konversation") {
            mutable.update { it.copy(history = emptyList(), answer = "", proposal = null, proposalChanges = emptyList(), activities = emptyList(), restoreRequested = false) }
            saveConversation()
        }
    }
    fun cancel() { job?.cancel() }

    fun connect(newAccount: Boolean, openBrowser: (String) -> Unit) = action("Complete sign-in in the browser, then return here. Account eligibility has not been verified.") {
        mutable.update { it.copy(verified = false, models = emptyList(), modelsUpdatedAt = null, selectedModel = "", proposal = null, history = emptyList(), answer = "", includeDiagnostics = false) }
        provider.signIn(if (newAccount) null else state.value.accounts.selected) { url -> withContext(Dispatchers.Main) { openBrowser(url) } }
        refreshAccounts()
        loadConversation()
        if (state.value.accounts.accounts.any { it.id == state.value.accounts.selected && it.planEnabled }) {
            loadModels()
            mutable.update { it.copy(status = "Ansluten till ditt ChatGPT-abonnemang. Skriv ett meddelande för att börja.") }
        } else mutable.update { it.copy(status = "Identity signed in. ChatGPT plan usage was not granted; AI requests are disabled.") }
    }
    fun select(id: String) = action("Selecting connection…") {
        provider.select(id)
        mutable.update { it.copy(models = emptyList(), modelsUpdatedAt = null, selectedModel = "", verified = false, proposal = null, answer = "", history = emptyList(), includeDiagnostics = false) }
        refreshAccounts()
        loadConversation()
        if (state.value.accounts.accounts.any { it.id == id && it.planEnabled }) loadModels()
        mutable.update { it.copy(status = "") }
    }
    fun refreshModels() = action("Hämtar tillgängliga modeller…") { loadModels(); mutable.update { it.copy(status = "Modellistan har uppdaterats.") } }
    fun verify() = action("Verifying an AI response using your ChatGPT plan…") {
        mutable.update { it.copy(verified = false) }
        val answer = provider.verify(state.value.selectedModel)
        mutable.update { it.copy(verified = true, status = "Completed AI response: $answer") }
    }
    fun disconnect() = action("Signing out…") {
        val revoked = provider.signOut()
        mutable.update { it.copy(verified = false, models = emptyList(), modelsUpdatedAt = null, selectedModel = "", proposal = null, answer = "", history = emptyList(), includeDiagnostics = false,
            status = if (revoked) "Signed out. Registration retained for reconnecting." else "Signed out locally. Remote revocation was not confirmed; disconnect this app in ChatGPT settings.") }
    }
    fun send() {
        val before = state.value
        val blocked = before.sendBlockReason
        if (blocked != null) {
            if (!before.busy) mutable.update { it.copy(status = blocked) }
            return
        }
        action("Waiting for a reply using your ChatGPT plan…") {
            mutable.update { it.copy(proposal = null, proposalChanges = emptyList(), restoreRequested = false, activities = emptyList(), answer = "", verified = false) }
            val gameTools = requireNotNull(tools)
            gameTools.beginTurn()
            val reply = if (before.includeDiagnostics) {
                GameAssistantAgent.run(before.selectedModel, before.prompt, before.history, gameTools, provider::agentTurn) { progress ->
                    mutable.update { it.copy(status = progress, activities = if (progress == "Tänker…") it.activities else (it.activities + progress).distinct()) }
                }
            } else provider.chat(before.selectedModel, before.prompt, null, before.history)
            check(before.includeDiagnostics || (reply.proposal == null && reply.toolCall == null && !reply.restoreRequested)) { "Unexpected tool without game access" }
            if (reply.proposal != null) snapshotHash = requireNotNull(gameTools.preparedHash)
            val history = (before.history + AssistantProtocol.conversationTurn(before.prompt, reply)).takeLast(AssistantProtocol.HISTORY_TURNS)
            mutable.update { it.copy(history = history, prompt = "", proposal = reply.proposal, verified = true,
                proposalChanges = gameTools.preparedChanges, restoreRequested = reply.restoreRequested, status = "") }
            saveConversation()
        }
    }
    fun localProposal() = action("Preparing local experiment…") {
        check(snapshotHash != null) { "Read a game configuration first" }
        mutable.update { it.copy(answer = "Local test suggestion — no AI request was made.",
            proposal = ConfigProposal(30, null, "Test GameNative's existing 30 FPS limiter. It may reduce unnecessary GPU work when the game exceeds 30 FPS; it cannot fix a game that cannot reach 30 FPS. Compare the same scene and restore if worse."),
            status = "Local test proposal ready; no performance improvement has been measured.") }
    }
    fun apply() = action("Backing up and applying the reviewed settings…") {
        val proposal = requireNotNull(state.value.proposal)
        val hash = requireNotNull(snapshotHash)
        withContext(Dispatchers.IO) { requireNotNull(tools).applyValidated(proposal, hash) }
        refreshDiagnostics()
        recordAction("Ändringarna har sparats. Starta spelet på nytt för att testa. Ångra finns kvar efter appomstart.")
    }
    fun restore() = action("Restoring affected settings…") {
        withContext(Dispatchers.IO) { requireNotNull(tools).restore() }
        refreshDiagnostics()
        recordAction("Tidigare inställningar har återställts. Övriga inställningar är bevarade.")
    }
    fun dismissProposal() { if (!state.value.busy) mutable.update { it.copy(proposal = null, proposalChanges = emptyList(), restoreRequested = false) } }
    fun keepChanges() = action("Behåller inställningarna…") {
        withContext(Dispatchers.IO) { requireNotNull(tools).keepChanges() }
        recordAction("Ändringarna behålls. Återställningspunkten är borttagen; nästa ändring får en ny säkerhetskopia.")
    }
    private suspend fun recordAction(message: String) {
        mutable.update { it.copy(history = (it.history + AssistantProtocol.ChatTurn("[Åtgärd i appen]", message)).takeLast(AssistantProtocol.HISTORY_TURNS),
            status = "", proposal = null, proposalChanges = emptyList(), restoreRequested = false) }
        saveConversation()
    }
    private suspend fun saveConversation() {
        val current = state.value
        val account = current.accounts.selected ?: return
        withContext(Dispatchers.IO) { conversations.save(current.game, account, ConversationStore.Saved(current.history, current.includeDiagnostics)) }
    }
    private suspend fun loadConversation() {
        val account = state.value.accounts.selected ?: return
        val saved = withContext(Dispatchers.IO) { conversations.load(state.value.game, account) }
        mutable.update { it.copy(history = saved.history, includeDiagnostics = saved.gameAccess) }
    }

    private suspend fun refreshDiagnostics() {
        snapshotHash = null
        mutable.update { it.copy(configurationReady = false, diagnostics = "", proposal = null) }
        val snapshot = withContext(Dispatchers.IO) { requireNotNull(tools).readDiagnostics() }
        snapshotHash = snapshot.hash
        mutable.update { it.copy(diagnostics = snapshot.text, configurationReady = true, proposal = null) }
    }
    private suspend fun refreshAccounts() {
        val accounts = provider.accounts()
        mutable.update { it.copy(accounts = accounts) }
    }
    private suspend fun loadModels() {
        val models = provider.models()
        mutable.update { it.copy(models = models, modelsUpdatedAt = System.currentTimeMillis(),
            selectedModel = it.selectedModel.takeIf { selected -> models.any { model -> model.slug == selected } } ?: models.firstOrNull()?.slug.orEmpty(),
            verified = false, proposal = null) }
        check(models.isNotEmpty()) { "No models available for this connection" }
    }
    private fun action(message: String, block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, status = message) }
        job = viewModelScope.launch {
            try { block() } catch (e: CancellationException) {
                mutable.update { it.copy(status = "Cancelled. No new proposal accepted.", proposal = null) }
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(status = DiagnosticRedactor.text(e.message ?: "Operation failed").take(1500)) }
            } finally {
                // Refresh local account and undo state without issuing another network request.
                try {
                    refreshAccounts()
                    val backup = withContext(Dispatchers.IO) { tools?.hasBackup() ?: false }
                    mutable.update { it.copy(backup = backup) }
                } catch (_: Exception) { /* The operation's error remains visible. */ }
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}
