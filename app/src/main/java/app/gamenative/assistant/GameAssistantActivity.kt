package app.gamenative.assistant

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.BuildConfig
import app.gamenative.ui.theme.PluviaTheme
import java.text.DateFormat
import java.util.Date
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
        model.initialize(intent.getStringExtra("app_id").orEmpty())
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
        model.initialize(intent.getStringExtra("app_id").orEmpty())
    }
}

data class AssistantUiState(
    val game: String = "",
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
) {
    val sendBlockReason: String?
        get() = when {
            busy -> "Wait for the current action to finish, or cancel it."
            accounts.accounts.none { it.id == accounts.selected && it.planEnabled } -> "Connect your ChatGPT plan to send a message."
            selectedModel.isBlank() -> "No model selected. Open Account and model, then Refresh models."
            prompt.isBlank() -> "Write a message to start or continue the conversation."
            includeDiagnostics && (!configurationReady || diagnostics.isBlank()) -> "Read the optional settings, or turn off Attach settings and log to chat without them."
            else -> null
        }
}

class GameAssistantViewModel @JvmOverloads constructor(
    application: Application,
    private val provider: GameAiProvider = ChatGptProvider(application),
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(AssistantUiState())
    val state = mutable.asStateFlow()
    private var tools: GameAssistantTools? = null
    private var snapshotHash: String? = null
    private var job: Job? = null

    fun initialize(game: String) {
        if (state.value.game == game || state.value.busy) return
        job?.cancel()
        mutable.value = AssistantUiState(game = game)
        snapshotHash = null
        tools = null
        action("Opening chat…") {
            tools = GameAssistantTools(getApplication(), game)
            refreshAccounts()
            if (state.value.accounts.accounts.any { it.id == state.value.accounts.selected && it.planEnabled }) loadModels()
            mutable.update { it.copy(status = "Ready to chat. A debug run is optional.") }
        }
    }
    fun prompt(value: String) { mutable.update { it.copy(prompt = value.take(4000)) } }
    fun model(value: String) { mutable.update { it.copy(selectedModel = value, verified = false, proposal = null) } }
    fun editDiagnostics(value: String) { mutable.update { it.copy(diagnostics = value.take(60_000), proposal = null) } }
    fun read() = action("Reading optional game settings and log…") {
        refreshDiagnostics()
        mutable.update { it.copy(status = "Settings loaded for review. You can chat even when no log is available.") }
    }
    fun attachDiagnostics(include: Boolean) {
        if (state.value.busy) return
        if (include && !state.value.configurationReady) action("Reading optional game settings and log…") {
            refreshDiagnostics()
            mutable.update { it.copy(includeDiagnostics = true, status = "Review the optional attachment before sending.") }
        } else mutable.update { it.copy(includeDiagnostics = include, proposal = null) }
    }
    fun clearChat() {
        if (!state.value.busy) mutable.update { it.copy(history = emptyList(), answer = "", proposal = null, status = "New conversation. A debug run is optional.") }
    }
    fun cancel() { job?.cancel() }

    fun connect(newAccount: Boolean, openBrowser: (String) -> Unit) = action("Complete sign-in in the browser, then return here. Account eligibility has not been verified.") {
        mutable.update { it.copy(verified = false, models = emptyList(), modelsUpdatedAt = null, selectedModel = "", proposal = null, history = emptyList(), answer = "", includeDiagnostics = false) }
        provider.signIn(if (newAccount) null else state.value.accounts.selected) { url -> withContext(Dispatchers.Main) { openBrowser(url) } }
        refreshAccounts()
        if (state.value.accounts.accounts.any { it.id == state.value.accounts.selected && it.planEnabled }) {
            loadModels()
            mutable.update { it.copy(status = "Signed in; ChatGPT plan permission granted. Run Verify AI access to test an actual response.") }
        } else mutable.update { it.copy(status = "Identity signed in. ChatGPT plan usage was not granted; AI requests are disabled.") }
    }
    fun select(id: String) = action("Selecting connection…") {
        provider.select(id)
        mutable.update { it.copy(models = emptyList(), modelsUpdatedAt = null, selectedModel = "", verified = false, proposal = null, answer = "", history = emptyList(), includeDiagnostics = false) }
        refreshAccounts()
        if (state.value.accounts.accounts.any { it.id == id && it.planEnabled }) loadModels()
        mutable.update { it.copy(status = "Connection selected. Previous conversation cleared.") }
    }
    fun refreshModels() = action("Loading available models…") { loadModels(); mutable.update { it.copy(status = "Model catalog loaded; inference remains unverified.") } }
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
            val diagnostics = if (before.includeDiagnostics) {
                check(snapshotHash != null) { "Read settings before attaching them, or chat without an attachment." }
                DiagnosticRedactor.text(before.diagnostics)
            } else null
            mutable.update { it.copy(proposal = null, answer = "", verified = false) }
            val reply = provider.chat(before.selectedModel, before.prompt, diagnostics, before.history)
            check(diagnostics != null || reply.proposal == null) { "Unexpected configuration proposal without attached settings" }
            val history = (before.history + AssistantProtocol.conversationTurn(before.prompt, reply)).takeLast(AssistantProtocol.HISTORY_TURNS)
            mutable.update { it.copy(history = history, prompt = "", proposal = reply.proposal, verified = true,
                status = "Reply received. You can ask a follow-up question.") }
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
        mutable.update { it.copy(status = "Settings saved. Start a new game session to test. Undo is available after restarting the app.") }
    }
    fun restore() = action("Restoring affected settings…") {
        withContext(Dispatchers.IO) { requireNotNull(tools).restore() }
        refreshDiagnostics()
        mutable.update { it.copy(status = "Affected settings restored. Other settings were preserved.") }
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

@Composable
private fun AssistantScreen(model: GameAssistantViewModel, onClose: () -> Unit, openBrowser: (String) -> Unit) {
    val state by model.state.collectAsState()
    val selected = state.accounts.accounts.firstOrNull { it.id == state.accounts.selected }
    var accountExpanded by rememberSaveable(state.game) { mutableStateOf(false) }
    var diagnosticsExpanded by rememberSaveable(state.game) { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AI assistant · ${state.game}", style = MaterialTheme.typography.headlineSmall)
            Text("GameNative AI Dev · ${BuildConfig.VERSION_NAME} · experimental", style = MaterialTheme.typography.labelLarge)
            Text("${if (state.verified) "AI response verified" else "AI access not verified"} · ${if (selected?.planEnabled == true) "Using ChatGPT plan" else "ChatGPT plan permission not enabled"}")
            Text(state.models.firstOrNull { it.slug == state.selectedModel }?.name ?: "Choose a model to chat")
            TextButton(onClick = { accountExpanded = !accountExpanded }) { Text(if (accountExpanded) "Hide account and model" else "Account and model") }
            if (accountExpanded || selected?.planEnabled != true) {
                state.accounts.accounts.forEach { account ->
                    OutlinedButton(onClick = { model.select(account.id) }, enabled = !state.busy) {
                        Text("${if (account.id == state.accounts.selected) "✓ " else ""}${account.label}")
                    }
                }
                Button(onClick = { model.connect(false, openBrowser) }, enabled = !state.busy) { Text("Continue with ChatGPT") }
                TextButton(onClick = { model.connect(true, openBrowser) }, enabled = !state.busy) { Text("Add account / workspace") }
                if (selected?.connected == true) TextButton(onClick = { model.disconnect() }, enabled = !state.busy) { Text("Sign out") }
                TextButton(onClick = { openBrowser(ChatGptProvider.USAGE_URL) }) { Text("Manage usage and app access") }
                OutlinedButton(onClick = { model.refreshModels() }, enabled = !state.busy && selected?.planEnabled == true) { Text("Refresh models") }
                Text("Models supplied by OpenAI for this connection. This catalog can differ from the choices shown in ChatGPT or Codex.")
                state.modelsUpdatedAt?.let { Text("Catalog fetched: ${DateFormat.getDateTimeInstance().format(Date(it))}", style = MaterialTheme.typography.bodySmall) }
                state.models.forEach { available ->
                    TextButton(onClick = { model.model(available.slug) }, enabled = !state.busy) {
                        Text("${if (available.slug == state.selectedModel) "✓ " else ""}${available.name}")
                    }
                }
                if (state.selectedModel.isNotBlank()) Text("Model ID: ${state.selectedModel}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { model.verify() }, enabled = !state.busy && state.selectedModel.isNotBlank()) { Text("Verify AI access") }
                Text("If the sign-in browser stays open after approval, use Android Back to return here and check the connection.")
            }
            if (state.busy) {
                CircularProgressIndicator()
                TextButton(onClick = { model.cancel() }) { Text("Cancel") }
            }
            SelectionContainer { Text(state.status, style = MaterialTheme.typography.bodyMedium) }
            Text("Chat", style = MaterialTheme.typography.titleLarge)
            Text("Ask anything or describe a game problem. No debug run is required.")
            state.history.forEach { turn ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("You", style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(turn.user) }
                        Text("Assistant", style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(turn.assistant) }
                    }
                }
            }
            if (state.history.isNotEmpty()) TextButton(onClick = model::clearChat, enabled = !state.busy) { Text("New conversation") }
            OutlinedTextField(value = state.prompt, onValueChange = model::prompt, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(), label = { Text("Message") },
                placeholder = { Text("For example: How can I get a steadier 30 FPS?") }, maxLines = 5)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.includeDiagnostics, onCheckedChange = {
                    if (it) diagnosticsExpanded = true
                    model.attachDiagnostics(it)
                }, enabled = !state.busy)
                Text("Attach settings and log (optional)")
            }
            TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }) { Text(if (diagnosticsExpanded) "Hide optional diagnostics" else "Review optional diagnostics") }
            if (diagnosticsExpanded || state.includeDiagnostics) {
                Text("Settings can be attached even without a log. Review and remove sensitive details before sending. Filtering cannot recognize every secret.")
                OutlinedButton(onClick = model::read, enabled = !state.busy) { Text("Read configuration and game log") }
                OutlinedTextField(value = state.diagnostics, onValueChange = model::editDiagnostics, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), label = { Text("Optional diagnostic attachment") }, maxLines = 12)
                Text("If needed, AI debug run can collect a log. It is optional. Stop the game before applying or restoring settings.")
            }
            Text("Sending shares your message and the last 8 exchanges with OpenAI. Settings and logs are attached only when checked. Earlier replies can still contain details from past attachments; use New conversation to clear them.", style = MaterialTheme.typography.bodySmall)
            Text("Conversation history lasts while this screen is open, including rotation.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = model::send, enabled = state.sendBlockReason == null) {
                Text(if (state.includeDiagnostics) "Send with reviewed settings" else "Send message")
            }
            state.sendBlockReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (diagnosticsExpanded) OutlinedButton(onClick = model::localProposal, enabled = !state.busy && state.configurationReady) { Text("Local 30 FPS test proposal (no AI)") }
            if (state.answer.isNotBlank()) SelectionContainer { Text(state.answer) }
            state.proposal?.let { proposal ->
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Proposed experiment", style = MaterialTheme.typography.titleMedium)
                        proposal.fps?.let { Text("FPS limiter → enabled, $it FPS") }
                        proposal.screenSize?.let { Text("Container resolution → $it") }
                        Text(proposal.reason)
                        Text("Creates an undo backup. Takes effect at the next launch. Performance improvement is unverified.")
                        Button(onClick = { model.apply() }, enabled = !state.busy && !state.backup) { Text("Back up and apply these changes") }
                    }
                }
            }
            if (state.backup) {
                Text("An experiment has a saved backup. Restore it before applying another experiment.")
                Button(onClick = { model.restore() }, enabled = !state.busy) { Text("Restore previous settings") }
            }
            TextButton(onClick = onClose, enabled = !state.busy) { Text("Close") }
        }
    }
}
