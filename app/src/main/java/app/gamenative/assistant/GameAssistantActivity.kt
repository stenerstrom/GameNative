package app.gamenative.assistant

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.BuildConfig
import app.gamenative.mods.LocalModImporter
import app.gamenative.mods.LocalModSourceType
import app.gamenative.mods.DuplicateLocalModContentException
import app.gamenative.mods.ModDownloadRegistry
import app.gamenative.service.NexusModImportService
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

class GameAssistantActivity : ComponentActivity() {
    private val model: GameAssistantViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.AI_ASSISTANT_ENABLED) { finish(); return }
        model.initialize(intent.getStringExtra("app_id").orEmpty(), intent.getStringExtra("game_title").orEmpty(), intent.getStringExtra("debug_report_id"), intent.getBooleanExtra("debug_setup", false))
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
        model.initialize(intent.getStringExtra("app_id").orEmpty(), intent.getStringExtra("game_title").orEmpty(), intent.getStringExtra("debug_report_id"), intent.getBooleanExtra("debug_setup", false))
    }
}

data class AssistantUiState(
    val game: String = "",
    val gameTitle: String = "",
    val screenshot: GameScreenshot? = null,
    val debugSheet: Boolean = false,
    val debugReports: List<org.json.JSONObject> = emptyList(),
    val debugReportId: String? = null,
    val debugReport: org.json.JSONObject? = null,
    val reportAttached: Boolean = false,
    val busy: Boolean = false,
    val status: String = "",
    val accounts: ChatGptProvider.Accounts = ChatGptProvider.Accounts(emptyList(), null),
    val models: List<ChatGptProvider.Model> = emptyList(),
    val modelsUpdatedAt: Long? = null,
    val selectedModel: String = "",
    val prompt: String = "",
    val diagnostics: String = "",
    val includeDiagnostics: Boolean = false,
    val webAccess: Boolean = true,
    val fileAccess: Boolean = false,
    val modAccess: Boolean = false,
    val configurationReady: Boolean = false,
    val history: List<AssistantProtocol.ChatTurn> = emptyList(),
    val answer: String = "",
    val proposal: ConfigProposal? = null,
    val fileProposal: GameTextFiles.Preview? = null,
    val modProposal: ModActionPreview? = null,
    val offlineProposal: OfflineGamePreview? = null,
    val offlineUndo: Boolean = false,
    val careProposal: CarePreview? = null,
    val careUndo: Boolean = false,
    val careInventory: String = "{}",
    val controlInventory: String = "{}",
    val preflight: String = "{}",
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

    fun initialize(game: String, title: String = "", reportId: String? = null, debugSetup: Boolean = false) {
        if (state.value.busy) return
        if (state.value.game == game) {
            action("Läser felsökningsrapporter…") {
                mutable.update { it.copy(debugSheet = debugSetup || it.debugSheet, reportAttached = if (reportId != null) false else it.reportAttached, debugReportId = reportId ?: it.debugReportId) }
                loadDebugReports(reportId ?: CodexDebugSession.status.value?.takeIf { it.game == game && it.recording }?.reportId ?: state.value.debugReportId)
                mutable.update { it.copy(status = "") }
            }
            return
        }
        job?.cancel()
        mutable.value = AssistantUiState(game = game, gameTitle = title.ifBlank { game }, debugSheet = debugSetup, debugReportId = reportId)
        snapshotHash = null
        tools = null
        action("Opening chat…") {
            tools = GameAssistantTools(getApplication(), game, state.value.gameTitle)
            mutable.update { it.copy(offlineUndo = tools!!.hasOfflineUndo()) }
            refreshAccounts()
            loadConversation()
            if (reportId != null) mutable.update { it.copy(debugReportId = reportId, reportAttached = false) }
            loadDebugReports(reportId ?: state.value.debugReportId)
            if (state.value.accounts.accounts.any { it.id == state.value.accounts.selected && it.planEnabled }) loadModels()
            mutable.update { it.copy(status = "") }
        }
    }
    private suspend fun loadDebugReports(preferred: String? = state.value.debugReportId) {
        val app = getApplication<Application>(); val game = state.value.game
        val reports = CodexDebugSession.reports(app, game)
        val id = preferred?.takeIf { wanted -> reports.any { it.optString("id") == wanted } } ?: reports.firstOrNull()?.getString("id")
        val report = id?.let { withContext(Dispatchers.IO) { CodexDebugSession.store(app).read(game, it) } }
        mutable.update { it.copy(debugReports = reports, debugReportId = id, debugReport = report,
            reportAttached = it.reportAttached && id == it.debugReportId) }
    }
    fun openDebug() = action("Läser felsökningsrapporter…") {
        loadDebugReports(); mutable.update { it.copy(debugSheet = true, status = "") }
    }
    fun closeDebug() { mutable.update { it.copy(debugSheet = false) } }
    fun selectDebugReport(id: String) = action("Läser vald rapport…") {
        check(state.value.debugReports.any { it.optString("id") == id }) { "Rapporten finns inte för detta spel." }
        mutable.update { it.copy(reportAttached = false) }
        loadDebugReports(id); saveConversation(); mutable.update { it.copy(status = "") }
    }
    fun startDebug(problem: DebugProblem) = action("Startar lokal felsökning…") {
        val app = getApplication<Application>(); val game = state.value.game
        if (LiveGameSession.view(game) != null) {
            val id = CodexDebugSession.start(app, game, problem)
            loadDebugReports(id)
            mutable.update { it.copy(debugSheet = false, status = "Insamling pågår lokalt. Markera när problemet händer och begär sedan analys.") }
            InGameAssistantUi.close(game)
        } else {
            try {
                val launch = CodexDebugSession.requestLaunch(app, game, problem)
                mutable.update { it.copy(debugSheet = false, status = "Startar spelet med lokal felsökning. Rapporten öppnas här efteråt.") }
                app.startActivity(launch)
            } catch (error: Exception) {
                CodexDebugSession.cancelRequest(game)
                throw error
            }
        }
    }
    fun markDebug() = action("Markerar problemet…") {
        CodexDebugSession.mark(state.value.game); loadDebugReports(CodexDebugSession.status.value?.reportId)
        mutable.update { it.copy(status = "Tidpunkten är markerad i rapporten.") }
    }
    fun stopDebug() = action("Sparar rapporten…") {
        CodexDebugSession.finish(state.value.game); loadDebugReports(CodexDebugSession.status.value?.reportId)
        mutable.update { it.copy(status = "Rapporten är sparad. Du kan fortsätta spela eller analysera den här.") }
    }
    fun deleteDebug() = action("Tar bort lokal rapport…") {
        val id = requireNotNull(state.value.debugReportId)
        withContext(Dispatchers.IO) { CodexDebugSession.store(getApplication()).delete(state.value.game, id) }
        mutable.update { it.copy(debugReportId = null, reportAttached = false) }; loadDebugReports()
        saveConversation()
        mutable.update { it.copy(status = "Rapporten är borttagen. Sparade chattsvar och återställningspunkter behålls.") }
    }
    fun importOldDebug() = action("Läser äldre lokal rapport…") {
        val id = CodexDebugSession.importLegacy(getApplication(), state.value.game)
        loadDebugReports(id); mutable.update { it.copy(status = "Äldre rapport importerad lokalt. Tidpunkter och inställningar är inte verifierade mot en bestämd körning.") }
    }
    fun detachDebug() = action("Kopplar loss rapporten…") {
        tools?.debugReport = null; mutable.update { it.copy(reportAttached = false, status = "") }; saveConversation()
    }
    fun analyzeDebug() {
        if (state.value.busy || state.value.debugReportId == null) return
        val problem = DebugProblem.entries.firstOrNull { it.name == state.value.debugReport?.optString("problem") }?.label ?: "Annat"
        mutable.update { it.copy(includeDiagnostics = true, reportAttached = true, debugSheet = false,
            prompt = it.prompt.ifBlank { "Undersök min felsökningsrapport ($problem). Vad visar underlaget och vad är nästa konkreta åtgärd?" }) }
        send()
    }
    fun captureScreenshot() = action("Läser spelets bildyta…") {
        val shot = GameScreenshot.capture(state.value.game)
        mutable.update { it.copy(screenshot = shot, status = "Granska bilden. Den skickas med nästa meddelande först när du trycker Skicka.") }
    }
    fun removeScreenshot() { if (!state.value.busy) mutable.update { it.copy(screenshot = null) } }
    fun prompt(value: String) { mutable.update { it.copy(prompt = value.take(4000)) } }
    fun model(value: String) { if (!state.value.busy) mutable.update { it.copy(selectedModel = value, verified = false, proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null, proposalChanges = emptyList()) } }
    fun editDiagnostics(value: String) { mutable.update { it.copy(diagnostics = value.take(60_000), proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null) } }
    fun read() = action("Reading optional game settings and log…") {
        refreshDiagnostics()
        mutable.update { it.copy(status = "Settings loaded for review. You can chat even when no log is available.") }
    }
    fun attachDiagnostics(include: Boolean) = action("Uppdaterar spelåtkomst…") {
        if (!include) mutable.update { it.copy(reportAttached = false) }
        mutable.update { it.copy(includeDiagnostics = include, fileAccess = include && it.fileAccess, modAccess = include && it.modAccess, proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null, proposalChanges = emptyList(), restoreRequested = false,
            status = if (include) "Assistenten kan nu undersöka spelet när du skickar en fråga." else "Spelåtkomst avstängd. Du kan fortfarande chatta.") }
        saveConversation()
    }
    fun allowWeb(allow: Boolean) = action("Uppdaterar webbsökning…") {
        mutable.update { it.copy(webAccess = allow, status = "") }
        saveConversation()
    }
    fun allowFiles(allow: Boolean) = action("Uppdaterar filåtkomst…") {
        check(!allow || state.value.includeDiagnostics) { "Aktivera spelverktyg först" }
        mutable.update { it.copy(fileAccess = allow, proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null, proposalChanges = emptyList(),
            restoreRequested = false, status = if (allow) "Assistenten kan läsa spelets stödda textfiler och föreslå ändringar när du skickar en fråga."
                else "Filåtkomst avstängd. Tidigare ändringar kan fortfarande ångras.") }
        saveConversation()
    }
    fun allowMods(allow: Boolean) = action("Uppdaterar modåtkomst…") {
        check(!allow || state.value.includeDiagnostics) { "Aktivera spelverktyg först" }
        mutable.update { it.copy(modAccess = allow, proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null, status = "") }
        saveConversation()
    }
    fun modLibraryClosed() = action("Uppdaterar modstatus…") {
        tools?.beginTurn()
        mutable.update { it.copy(proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null, status = "Modbiblioteket är uppdaterat. Be assistenten läsa dina moddar för att fortsätta.") }
    }
    fun importMod(type: LocalModSourceType, uris: List<Uri>) = action("Importerar modpaket…") {
        check(state.value.includeDiagnostics && state.value.modAccess) { "Aktivera Tillåt modhantering först" }
        check(uris.isNotEmpty())
        val app = getApplication<Application>()
        uris.forEach { uri -> runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        val source = when (type) {
            LocalModSourceType.ARCHIVE -> LocalModImporter.inspectArchive(app, uris.single())
            LocalModSourceType.FILES -> LocalModImporter.inspectFiles(app, uris)
            LocalModSourceType.FOLDER -> LocalModImporter.inspectFolder(app, uris.single())
        }
        val importId = "local_${java.util.UUID.randomUUID()}"
        val importContext = currentCoroutineContext()
        val import = NexusModImportService.enqueueLocalImport(app, state.value.game, source, LocalModImporter.suggestedModName(source, "Importerad mod"), "",
            installId = importId, onProgress = { p -> if (importContext.isActive) mutable.update { it.copy(status = "Importerar mod: ${(p.progress * 100).toInt()} %") } })
        val imported = try {
            import.await()
        } catch (cancel: CancellationException) {
            if (!import.isCompleted) ModDownloadRegistry.requestCancel(importId)
            throw cancel
        } catch (e: DuplicateLocalModContentException) { e.existingInstall }
        tools?.beginTurn()
        recordAction("${DiagnosticRedactor.text(imported.modName)} finns i modbiblioteket. Paketet är importerat; inga nya modfiler har installerats i spelet.")
        mutable.update { it.copy(prompt = "Granska den importerade modden med mod_id ${imported.installId}. Läs instruktionerna och föreslå hur den ska installeras.") }
    }
    fun clearChat() {
        action("Ny konversation") {
            mutable.update { it.copy(reportAttached = false) }
            mutable.update { it.copy(history = emptyList(), screenshot = null, answer = "", proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null, proposalChanges = emptyList(), activities = emptyList(), restoreRequested = false) }
            saveConversation()
        }
    }
    fun cancel() { job?.cancel() }

    fun connect(newAccount: Boolean, openBrowser: (String) -> Unit) = action("Complete sign-in in the browser, then return here. Account eligibility has not been verified.") {
        mutable.update { it.copy(reportAttached = false) }
        mutable.update { it.copy(verified = false, screenshot = null, models = emptyList(), modelsUpdatedAt = null, selectedModel = "", proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null, history = emptyList(), answer = "", includeDiagnostics = false, fileAccess = false, modAccess = false) }
        provider.signIn(if (newAccount) null else state.value.accounts.selected) { url -> withContext(Dispatchers.Main) { openBrowser(url) } }
        refreshAccounts()
        loadConversation()
        loadDebugReports()
        if (state.value.accounts.accounts.any { it.id == state.value.accounts.selected && it.planEnabled }) {
            loadModels()
            mutable.update { it.copy(status = "Ansluten till ditt ChatGPT-abonnemang. Skriv ett meddelande för att börja.") }
        } else mutable.update { it.copy(status = "Identity signed in. ChatGPT plan usage was not granted; AI requests are disabled.") }
    }
    fun select(id: String) = action("Selecting connection…") {
        mutable.update { it.copy(reportAttached = false) }
        provider.select(id)
        mutable.update { it.copy(screenshot = null, models = emptyList(), modelsUpdatedAt = null, selectedModel = "", verified = false, proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null, answer = "", history = emptyList(), includeDiagnostics = false, fileAccess = false, modAccess = false) }
        refreshAccounts()
        loadConversation()
        loadDebugReports()
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
        mutable.update { it.copy(reportAttached = false) }
        val revoked = provider.signOut()
        mutable.update { it.copy(verified = false, screenshot = null, models = emptyList(), modelsUpdatedAt = null, selectedModel = "", proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null, answer = "", history = emptyList(), includeDiagnostics = false, fileAccess = false, modAccess = false,
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
            mutable.update { it.copy(proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null, proposalChanges = emptyList(), restoreRequested = false, activities = emptyList(), answer = "", verified = false) }
            val gameTools = requireNotNull(tools)
            gameTools.beginTurn()
            gameTools.debugReport = if (before.includeDiagnostics && before.reportAttached) {
                CodexDebugSession.frozen(getApplication(), before.game, requireNotNull(before.debugReportId))
            } else null
            gameTools.screenshot = before.screenshot
            gameTools.fileAccess = before.includeDiagnostics && before.fileAccess
            gameTools.modAccess = before.includeDiagnostics && before.modAccess
            gameTools.webAccess = before.webAccess
            val progress: (String) -> Unit = { message ->
                mutable.update { it.copy(status = message, activities = if (message == "Tänker…") it.activities else (it.activities + message).distinct()) }
            }
            val reply = if (before.includeDiagnostics) {
                GameAssistantAgent.run(before.selectedModel, before.prompt, before.history, gameTools,
                    respond = { request -> provider.agentTurn(request, progress) }, progress = progress)
            } else if (before.screenshot != null) {
                provider.agentTurn(AssistantProtocol.request(before.selectedModel, before.prompt, null, before.history, before.webAccess)
                    .apply { before.screenshot.attach(this) }, progress)
            } else provider.chat(before.selectedModel, before.prompt, null, before.history, before.webAccess, progress)
            gameTools.screenshot = null
            check(before.includeDiagnostics || (reply.proposal == null && reply.toolCall == null && reply.fileProposal == null && reply.modProposal == null && reply.offlineProposal == null && reply.careProposal == null && !reply.restoreRequested)) { "Unexpected tool without game access" }
            if (reply.proposal != null) snapshotHash = requireNotNull(gameTools.preparedHash)
            val reportNote = gameTools.debugReport?.let { "\n[Felsökningsrapport ${it.getString("id")} · ${it.optString("state")}]" }.orEmpty()
            val history = (before.history + AssistantProtocol.conversationTurn(before.prompt + reportNote + if (before.screenshot != null) "\n[Bifogad spelbild; sparas inte i historiken]" else "", reply)).takeLast(AssistantProtocol.HISTORY_TURNS)
            mutable.update { it.copy(history = history, prompt = "", screenshot = null, proposal = reply.proposal, fileProposal = reply.fileProposal, modProposal = reply.modProposal, offlineProposal = reply.offlineProposal, careProposal = reply.careProposal, verified = true,
                proposalChanges = gameTools.preparedChanges, restoreRequested = reply.restoreRequested, status = "") }
            saveConversation()
        }
    }
    fun localProposal() = action("Preparing local experiment…") {
        check(snapshotHash != null) { "Read a game configuration first" }
        mutable.update { it.copy(answer = "Local test suggestion — no AI request was made.",
            fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null,
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
    fun applyLive(preview: ControllerLiveTransactions.Preview) = action("Provar kontrollbryggan live…") {
        check(state.value.includeDiagnostics && preview.game == state.value.game)
        check(LiveControllerChanges.preview(state.value.game, requireNotNull(state.value.proposal)) == preview) {
            "Förslaget eller bryggan ändrades. Granska läget igen."
        }
        LiveControllerChanges.apply(preview)
        recordAction("Bryggans liveförsök: ${preview.after.describe()}. Gäller nästa äldre kontrollförfrågan till bryggan. SDL:s startval och sparade inställningar är oförändrade. Testa om spelet reagerar; Ångra liveförsök finns i panelen.")
    }
    fun restoreLive() = action("Ångrar liveförsöket…") {
        LiveControllerChanges.restore(state.value.game)
        recordAction("Kontrollbryggans tidigare läge har återställts för denna omgång. Inga sparade inställningar ändrades.")
    }
    fun reconnectController() = action("Återansluter kontrollbryggan…") {
        LiveControllerChanges.reconnect(state.value.game)
        recordAction("Kontrollbryggan har fått en kort frånkoppling och ny anslutningsstatus. Spelet kör vidare. Detta bekräftar inte att spelet upptäckt kontrollen; stäng panelen och prova knapparna.")
    }
    fun applyFile() = action("Säkerhetskopierar och sparar filändringen…") {
        check(state.value.includeDiagnostics && state.value.fileAccess) { "Filåtkomst är avstängd" }
        val preview = requireNotNull(state.value.fileProposal) { "Inget granskat filförslag finns" }
        withContext(Dispatchers.IO) { requireNotNull(tools).applyFile() }
        recordAction("Filändringen i ${preview.path} har sparats. Starta spelet för att testa. Originalfilen kan återställas med Ångra ändring även efter appomstart.")
    }
    fun applyMod(loaderApproved: Boolean) = action("Tillämpa modändring…") {
        check(state.value.includeDiagnostics && state.value.modAccess) { "Modåtkomst är avstängd" }
        val preview = requireNotNull(state.value.modProposal) { "Inget granskat modförslag finns" }
        requireNotNull(tools).applyMod(loaderApproved)
        recordAction("${preview.title}: modfilerna har ändrats och verifierats. Ångra ändring återställer detta försök. Funktion och kompatibilitet måste fortfarande testas i spelet.")
    }
    fun loadCare() = action("Läser spelverktyg…") {
        val t = requireNotNull(tools)
        val data = t.care.inventory(true)
        val controls = t.care.readControls()
        val check = t.preflight()
        mutable.update { it.copy(careInventory = data.toString(), controlInventory = controls.toString(), preflight = check.toString(), status = "") }
    }
    fun prepareCareLocal(tool: String, args: org.json.JSONObject) = action("Förbereder profil för granskning…") {
        val preview = requireNotNull(tools).care.prepare(tool, args, true)
        mutable.update { it.copy(proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = preview, status = "") }
    }
    fun applyCare(modsApproved: Boolean = false) = action("Säkerhetskopierar och tillämpar profil…") {
        val message = requireNotNull(tools).care.apply(requireNotNull(state.value.careProposal), modsApproved)
        recordAction(message)
    }
    fun restoreCare() = action("Återställer föregående profil…") {
        requireNotNull(tools).care.restore()
        recordAction("Profiländringen har återställts. Andra inställningar och filer har bevarats.")
    }
    fun keepCare() = action("Behåller profilen…") {
        requireNotNull(tools).care.keep()
        recordAction("Profiländringen behålls. De namngivna profilerna finns kvar; ångrapunkten för det senaste bytet är borttagen.")
    }
    fun deleteCare(id: String) = action("Tar bort sparad profil…") {
        requireNotNull(tools).care.delete(id)
        val inventory = requireNotNull(tools).care.inventory(true).toString()
        mutable.update { it.copy(careInventory = inventory, status = "Profilen togs bort. Aktiva spelinställningar påverkas inte.") }
    }
    fun askCare(question: String, mods: Boolean = false) {
        if (state.value.busy) return
        mutable.update { it.copy(includeDiagnostics = true, modAccess = mods || it.modAccess, prompt = question) }
        send()
    }
    fun offlineHelp() {
        if (state.value.busy) return
        mutable.update { it.copy(includeDiagnostics = true,
            prompt = "Hjälp mig installera eller starta det här offlinespelet. Undersök spelmappen och föreslå nästa steg. Om spelet redan är installerat, hjälp mig välja rätt startfil.") }
        send()
    }
    fun applyOffline() = action("Förbereder spelets startfil…") {
        check(state.value.includeDiagnostics) { "Spelåtkomst är avstängd." }
        val preview = requireNotNull(state.value.offlineProposal)
        val game = state.value.game
        requireNotNull(tools).applyOffline(preview)
        mutable.update { it.copy(offlineUndo = true) }
        val installer = preview.action == "run_installer"
        recordAction(if (installer) "Installerarens startfil ${preview.path} har valts. Öppnar den i Wine. Slutför Windows-guiden, stäng den och be mig hitta spelets EXE. Installationens resultat är ännu inte verifierat."
            else "${preview.path} har valts som spelets startfil. Starta spelet från biblioteket för att prova. Ångra startfil återställer det tidigare valet.")
        if (installer) getApplication<Application>().startActivity(OfflineGameLaunch.intent(getApplication(), game))
    }
    fun restoreOffline() = action("Återställer tidigare startfil…") {
        requireNotNull(tools).restoreOffline()
        mutable.update { it.copy(offlineUndo = false) }
        recordAction("Tidigare startfil och startargument har återställts. Installerade filer och Windows-registret påverkas inte av Ångra startfil.")
    }
    fun restore() = action("Återställer senaste ändringen…") {
        val wasFile = requireNotNull(tools).hasFileBackup()
        val wasMod = requireNotNull(tools).hasModBackup()
        withContext(Dispatchers.IO) { requireNotNull(tools).restoreAll() }
        if (!wasFile && !wasMod) refreshDiagnostics()
        recordAction(if (wasMod) "Modändringen har återställts." else if (wasFile) "Originalfilen har återställts från säkerhetskopian." else "Tidigare inställningar har återställts. Övriga inställningar är bevarade.")
    }
    fun dismissProposal() { if (!state.value.busy) mutable.update { it.copy(proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null, proposalChanges = emptyList(), restoreRequested = false) } }
    fun keepChanges() = action("Behåller inställningarna…") {
        withContext(Dispatchers.IO) { requireNotNull(tools).keepAll() }
        recordAction("Ändringarna behålls. Återställningspunkten är borttagen; nästa ändring får en ny säkerhetskopia.")
    }
    private suspend fun recordAction(message: String) {
        mutable.update { it.copy(history = (it.history + AssistantProtocol.ChatTurn("[Åtgärd i appen]", message)).takeLast(AssistantProtocol.HISTORY_TURNS),
            status = "", proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null, proposalChanges = emptyList(), restoreRequested = false) }
        saveConversation()
    }
    private suspend fun saveConversation() {
        val current = state.value
        val account = current.accounts.selected ?: return
        withContext(Dispatchers.IO) { conversations.save(current.game, account, ConversationStore.Saved(current.history, current.includeDiagnostics, current.fileAccess, current.modAccess,
            current.debugReportId.takeIf { current.includeDiagnostics && current.reportAttached }, current.webAccess)) }
    }
    private suspend fun loadConversation() {
        val account = state.value.accounts.selected ?: return
        val saved = withContext(Dispatchers.IO) { conversations.load(state.value.game, account) }
        mutable.update { it.copy(history = saved.history, includeDiagnostics = saved.gameAccess, fileAccess = saved.gameAccess && saved.fileAccess, modAccess = saved.gameAccess && saved.modAccess,
            debugReportId = saved.debugReportId, reportAttached = saved.gameAccess && saved.debugReportId != null, webAccess = saved.webAccess) }
    }

    private suspend fun refreshDiagnostics() {
        snapshotHash = null
        mutable.update { it.copy(configurationReady = false, diagnostics = "", proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null) }
        val snapshot = withContext(Dispatchers.IO) { requireNotNull(tools).readDiagnostics() }
        snapshotHash = snapshot.hash
        mutable.update { it.copy(diagnostics = snapshot.text, configurationReady = true, proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null) }
    }
    private suspend fun refreshAccounts() {
        val accounts = provider.accounts()
        mutable.update { it.copy(accounts = accounts) }
    }
    private suspend fun loadModels() {
        val models = provider.models()
        mutable.update { it.copy(models = models, modelsUpdatedAt = System.currentTimeMillis(),
            selectedModel = it.selectedModel.takeIf { selected -> models.any { model -> model.slug == selected } } ?: models.firstOrNull()?.slug.orEmpty(),
            verified = false, proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null) }
        check(models.isNotEmpty()) { "No models available for this connection" }
    }
    private fun action(message: String, block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, status = message) }
        job = viewModelScope.launch {
            try { block() } catch (e: CancellationException) {
                mutable.update { it.copy(status = "Cancelled. No new proposal accepted.", proposal = null, fileProposal = null, modProposal = null, offlineProposal = null, careProposal = null) }
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(status = DiagnosticRedactor.text(e.message ?: "Operation failed").take(1500)) }
            } finally {
                tools?.screenshot = null
                tools?.debugReport = null
                // Refresh local account and undo state without issuing another network request.
                withContext(NonCancellable) {
                    try {
                        refreshAccounts()
                        val backup = withContext(Dispatchers.IO) { tools?.hasBackup() ?: false }
                        mutable.update { it.copy(backup = backup, offlineUndo = tools?.hasOfflineUndo() ?: false, careUndo = tools?.care?.hasUndo() ?: false) }
                    } catch (_: Exception) { /* The operation's error remains visible. */ }
                }
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}
