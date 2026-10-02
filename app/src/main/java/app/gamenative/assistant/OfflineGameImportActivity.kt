package app.gamenative.assistant

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.data.LibraryItem
import app.gamenative.service.SteamService
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.CustomGameImporter
import app.gamenative.utils.CustomGameScanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Copy via the Android grant; publish only a completed copy. Original source is never removed. */
internal object OfflineGameImport {
    suspend fun copy(context: Context, uri: Uri, progress: (CustomGameImporter.Progress) -> Unit): LibraryItem = withContext(Dispatchers.IO) {
        check(!SteamService.keepAlive) { "Avsluta spelet före import." }
        val root = File(CustomGameScanner.importRootPath).canonicalFile
        check(root.isDirectory || root.mkdirs())
        val staging = java.nio.file.Files.createTempDirectory(root.parentFile.toPath(), ".offline-import-").toFile()
        try {
            val copied = File(CustomGameImporter.importFromTreeUri(context, uri, false, staging, progress).getOrThrow())
            currentCoroutineContext().ensureActive()
            // The copy receives its own library identity and must not become a Steam import.
            val oldMetadata = File(copied, ".gamenative")
            check(!oldMetadata.exists() || oldMetadata.delete()) { "Kunde inte skapa en separat spelidentitet." }
            File(copied, ".gamenative-offline").writeText("1\n")
            val executable = copied.walkTopDown().maxDepth(12).take(20_000).firstOrNull {
                it.isFile && it.extension.equals("exe", true) && OfflineGameTools.peArchitecture(it) != null
            }
            require(executable != null) { "Ingen Windows EXE hittades. Välj hela installationsmappen med setup.exe och dess .bin-filer, eller en uppackad spelmapp. MSI/ZIP/7z behöver förberedas separat." }
            // No cancellable gap between publishing the directory and registering it.
            withContext(NonCancellable) {
                var target = File(root, copied.name)
                var n = 1
                while (target.exists()) target = File(root, "${copied.name} (${n++})")
                check(copied.renameTo(target)) { "Kunde inte färdigställa importen. Originalfilerna finns kvar." }
                val item = requireNotNull(CustomGameScanner.createLibraryItemFromFolder(target.path)) { "Spelmappen kunde inte registreras." }
                check(item.appId.startsWith("CUSTOM_GAME_"))
                PrefManager.customGameManualFolders = PrefManager.customGameManualFolders + target.path
                CustomGameScanner.invalidateCache()
                item
            }
        } finally { staging.deleteRecursively() }
    }
}

class OfflineGameImportViewModel(application: Application) : AndroidViewModel(application) {
    data class State(val busy: Boolean = false, val progress: String = "", val item: LibraryItem? = null, val error: String? = null)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun import(uri: Uri) {
        if (state.value.busy) return
        mutable.value = State(busy = true)
        job = viewModelScope.launch {
            try {
                var shown = 0L
                val item = OfflineGameImport.copy(getApplication(), uri) {
                    if (it.copiedBytes - shown >= 8_000_000) {
                        shown = it.copiedBytes
                        mutable.value = State(busy = true, progress = "${shown / 1_000_000} MB kopierat · ${DiagnosticRedactor.text(it.currentFile).take(90)}")
                    }
                }
                mutable.value = State(item = item)
            } catch (e: CancellationException) {
                mutable.value = State(error = "Importen avbröts. Originalfilerna finns kvar."); throw e
            } catch (e: Exception) { mutable.value = State(error = DiagnosticRedactor.text(e.message ?: "Importen misslyckades.").take(500)) }
        }
    }
    fun cancel() { job?.cancel() }
}

class OfflineGameImportActivity : ComponentActivity() {
    private val model: OfflineGameImportViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.AI_ASSISTANT_ENABLED) { finish(); return }
        setContent {
            val state by model.state.collectAsState()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(model::import) }
            PluviaTheme {
                OfflineGameImportScreen(state, { picker.launch(null) }, model::cancel, { finish() }) { item ->
                    startActivity(Intent(this, GameAssistantActivity::class.java).putExtra("app_id", item.appId).putExtra("game_title", item.name))
                    finish()
                }
            }
        }
    }
}

@Composable
internal fun OfflineGameImportScreen(state: OfflineGameImportViewModel.State, pick: () -> Unit, cancel: () -> Unit, close: () -> Unit, open: (LibraryItem) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Installera offlinespel med Codex", style = MaterialTheme.typography.headlineSmall)
            Text("Välj hela installationsmappen med setup.exe och tillhörande .bin-filer, eller en färdig spelmapp. Den kopieras till GameNative och får en egen plats i biblioteket. Originalet behålls.")
            Text("Öppna sedan Codex och välj Hjälp med installation och startfil. Codex kan föreslå och starta installeraren i Wine efter din granskning. Slutför Windows-guiden själv; därefter kan Codex hitta och välja spelets EXE.")
            Text("Spelfilerna kan vara offline. Codex-samtalet kräver internet. ZIP/7z-arkiv behöver packas upp först. Ha plats för både kopian och installationen och håll appen öppen under kopieringen.", style = MaterialTheme.typography.bodySmall)
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress.ifBlank { "Kopierar den valda mappen…" })
                TextButton(onClick = cancel) { Text("Avbryt kopiering") }
            } else {
                state.item?.let { item ->
                    Text("${item.name} finns nu i biblioteket. Installation och spelstart är ännu inte verifierade.")
                    Button(onClick = { open(item) }, modifier = Modifier.testTag("offline-open-chat")) { Text("Fortsätt med Codex") }
                } ?: Button(onClick = pick, modifier = Modifier.testTag("offline-pick-folder")) { Text("Välj spelmapp") }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = close) { Text("Tillbaka") }
            }
        }
    }
}
