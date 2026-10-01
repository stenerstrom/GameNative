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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    val selectedModel: String = "",
    val prompt: String = "Det här spelet hackar, hjälp mig att få stabila 30 FPS.",
    val diagnostics: String = "",
    val answer: String = "",
    val proposal: ConfigProposal? = null,
    val backup: Boolean = false,
    val verified: Boolean = false,
)

class GameAssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val provider = ChatGptProvider(application)
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
        action("Reading game diagnostics…") {
            tools = GameAssistantTools(getApplication(), game)
            refreshDiagnostics()
        }
    }
    fun prompt(value: String) { mutable.update { it.copy(prompt = value.take(4000)) } }
    fun model(value: String) { mutable.update { it.copy(selectedModel = value, verified = false, proposal = null) } }
    fun editDiagnostics(value: String) { mutable.update { it.copy(diagnostics = value.take(60_000), proposal = null) } }
    fun read() = action("Reading game diagnostics…") { refreshDiagnostics() }
    fun cancel() { job?.cancel() }

    fun connect(newAccount: Boolean, openBrowser: (String) -> Unit) = action("Complete sign-in in the browser, then return here. Account eligibility has not been verified.") {
        mutable.update { it.copy(verified = false, models = emptyList(), selectedModel = "", proposal = null) }
        provider.signIn(if (newAccount) null else state.value.accounts.selected) { url -> withContext(Dispatchers.Main) { openBrowser(url) } }
        refreshAccounts()
        if (state.value.accounts.accounts.any { it.id == state.value.accounts.selected && it.planEnabled }) {
            loadModels()
            mutable.update { it.copy(status = "Signed in; ChatGPT plan permission granted. Run Verify AI access to test an actual response.") }
        } else mutable.update { it.copy(status = "Identity signed in. ChatGPT plan usage was not granted; AI requests are disabled.") }
    }
    fun select(id: String) = action("Selecting connection…") {
        provider.select(id)
        mutable.update { it.copy(models = emptyList(), selectedModel = "", verified = false, proposal = null, answer = "") }
        refreshAccounts()
        mutable.update { it.copy(status = "Connection selected. Refresh models and verify access.") }
    }
    fun refreshModels() = action("Loading available models…") { loadModels(); mutable.update { it.copy(status = "Model catalog loaded; inference remains unverified.") } }
    fun verify() = action("Verifying an AI response using your ChatGPT plan…") {
        mutable.update { it.copy(verified = false) }
        val answer = provider.verify(state.value.selectedModel)
        mutable.update { it.copy(verified = true, status = "Completed AI response: $answer") }
    }
    fun disconnect() = action("Signing out…") {
        val revoked = provider.signOut()
        mutable.update { it.copy(verified = false, models = emptyList(), selectedModel = "", proposal = null, answer = "",
            status = if (revoked) "Signed out. Registration retained for reconnecting." else "Signed out locally. Remote revocation was not confirmed; disconnect this app in ChatGPT settings.") }
    }
    fun analyze() = action("Analyzing the reviewed configuration and log with your ChatGPT plan…") {
        check(snapshotHash != null) { "Read a game configuration first" }
        mutable.update { it.copy(proposal = null, answer = "", verified = false) }
        val reply = provider.analyze(state.value.selectedModel, state.value.prompt, DiagnosticRedactor.text(state.value.diagnostics))
        mutable.update { it.copy(answer = reply.text, proposal = reply.proposal, verified = true,
            status = "AI response completed. Review any experiment before applying it.") }
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
        val snapshot = withContext(Dispatchers.IO) { requireNotNull(tools).readDiagnostics() }
        snapshotHash = snapshot.hash
        mutable.update { it.copy(diagnostics = snapshot.text, proposal = null) }
    }
    private suspend fun refreshAccounts() {
        val accounts = provider.accounts()
        mutable.update { it.copy(accounts = accounts) }
    }
    private suspend fun loadModels() {
        val models = provider.models()
        mutable.update { it.copy(models = models, selectedModel = models.firstOrNull()?.slug.orEmpty(), verified = false, proposal = null) }
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
                // Local state only: no surprise network requests on opening this screen.
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
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AI assistant · ${state.game}", style = MaterialTheme.typography.headlineSmall)
            Text("GameNative AI Dev · experimental", style = MaterialTheme.typography.labelLarge)
            Text("${if (state.verified) "AI response verified" else "AI access not verified"} · ${if (selected?.planEnabled == true) "Using ChatGPT plan" else "ChatGPT plan permission not enabled"}")
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
            state.models.forEach { available ->
                TextButton(onClick = { model.model(available.slug) }, enabled = !state.busy) {
                    Text("${if (available.slug == state.selectedModel) "✓ " else ""}${available.name}")
                }
            }
            OutlinedButton(onClick = { model.verify() }, enabled = !state.busy && state.selectedModel.isNotBlank()) { Text("Verify AI access") }
            if (state.busy) {
                CircularProgressIndicator()
                TextButton(onClick = { model.cancel() }) { Text("Cancel") }
            }
            SelectionContainer { Text(state.status, style = MaterialTheme.typography.bodyMedium) }
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
            Text("Review before sending. Only this text and your question go to OpenAI. Filtering reduces accidental disclosure but cannot recognize every secret; remove sensitive details here. No data is sent by reading diagnostics.")
            OutlinedButton(onClick = { model.read() }, enabled = !state.busy) { Text("Read configuration and game log") }
            OutlinedTextField(value = state.diagnostics, onValueChange = model::editDiagnostics, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), label = { Text("Reviewed diagnostic snapshot") }, maxLines = 12)
            Text("To collect a log: close this view, select AI debug run, reproduce the issue and exit the game. Return here before sending/deleting the debug report. Stop the game before applying or restoring settings.")
            OutlinedTextField(value = state.prompt, onValueChange = model::prompt, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(), label = { Text("What should we investigate?") }, maxLines = 5)
            Button(onClick = { model.analyze() }, enabled = !state.busy && selected?.planEnabled == true && state.selectedModel.isNotBlank() && state.diagnostics.isNotBlank() && state.prompt.isNotBlank()) {
                Text("Send reviewed diagnostics and analyze")
            }
            OutlinedButton(onClick = { model.localProposal() }, enabled = !state.busy && state.diagnostics.isNotBlank()) { Text("Local 30 FPS test proposal (no AI)") }
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
