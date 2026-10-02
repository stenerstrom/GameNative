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
    suspend fun prepareEnvironment(context: Context, item: LibraryItem, create: () -> Unit = {
        app.gamenative.utils.ContainerUtils.getOrCreateContainer(context, item.appId)
    }) = withContext(Dispatchers.IO) {
        check(!SteamService.keepAlive && LiveGameSession.token() == null) { "Avsluta spelet innan spelmiljön förbereds." }
        require(item.appId.matches(Regex("CUSTOM_GAME_[1-9][0-9]*")))
        if (app.gamenative.utils.ContainerUtils.hasContainer(context, item.appId)) return@withContext
        check(GameFileRoots.discover(context, item.appId).any { it.id == "game" }) { "Spelmappen saknas." }
        val home = File(app.gamenative.mods.ModContainerResolver.getWinePrefix(context, item.appId)).parentFile!!
        check(!home.exists()) { "En ofullständig spelmiljö finns redan. Öppna spelets inställningar i biblioteket och granska den; befintliga filer har bevarats." }
        // The existing GameNative setup creates the Wine environment. Do not interrupt or
        // retry its orphan-directory cleanup on an existing directory containing user data.
        withContext(NonCancellable) { create(); check(app.gamenative.utils.ContainerUtils.hasContainer(context, item.appId)) }
    }
    suspend fun copy(context: Context, uri: Uri, progress: (CustomGameImporter.Progress) -> Unit): LibraryItem = withContext(Dispatchers.IO) {
        check(!SteamService.keepAlive) { "Avsluta spelet före import." }
        val root = File(CustomGameScanner.importRootPath).canonicalFile
        check(root.isDirectory || root.mkdirs())
        val staging = java.nio.file.Files.createTempDirectory(root.parentFile.toPath(), ".offline-import-").toFile()
        try {
            val copied = File(CustomGameImporter.importFromTreeUri(context, uri, false, staging, progress).getOrThrow())
            currentCoroutineContext().ensureActive()
            publish(context, copied, root)
        } finally { staging.deleteRecursively() }
    }
    suspend fun archive(context: Context, uri: Uri, progress: (CustomGameImporter.Progress) -> Unit): LibraryItem = withContext(Dispatchers.IO) {
        check(!SteamService.keepAlive) { "Avsluta spelet före import." }
        val root = File(CustomGameScanner.importRootPath).canonicalFile
        check(root.isDirectory || root.mkdirs())
        val staging = java.nio.file.Files.createTempDirectory(root.parentFile.toPath(), ".offline-archive-").toFile()
        try {
            val displayName = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
            val extension = displayName.substringAfterLast('.', "").lowercase()
            require(extension in setOf("zip", "7z")) { "Välj ett ZIP- eller 7z-arkiv. Delade eller krypterade arkiv behöver packas upp separat." }
            val packed = File(staging, "source.$extension")
            var copied = 0L
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                packed.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        copied += count
                        require(copied <= 20L * 1024 * 1024 * 1024) { "Arkivet överstiger 20 GiB." }
                        check(staging.usableSpace > count + 32_000_000) { "Lagringsutrymmet är slut. Originalet behålls." }
                        output.write(buffer, 0, count)
                        progress(CustomGameImporter.Progress(copied, "Kopierar arkiv"))
                    }
                }
            }
            val name = displayName.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
                .take(80).trim(' ', '.').ifBlank { "Offlinespel" }
            // A provider controls the display name. Keep extraction apart from the source archive,
            // even for names such as "...zip" or "source.zip.zip".
            val extracted = File(File(staging, "unpacked").apply { check(mkdir()) }, name)
            val coroutine = currentCoroutineContext()
            val result = app.gamenative.mods.ModArchiveExtractor.extract(packed, extracted, onProgress = {
                coroutine.ensureActive()
                check(staging.usableSpace > 32_000_000) { "För lite utrymme för uppackning." }
                progress(CustomGameImporter.Progress(copied + it.extractedBytes, "Packar upp: ${it.currentPath}"))
            })
            currentCoroutineContext().ensureActive()
            validateArchiveEntries(result.entries)
            packed.delete()
            // Keep the archive's tree intact; the executable scanner supports enclosing folders.
            publish(context, extracted, root)
        } finally { staging.deleteRecursively() }
    }

    internal fun validateArchiveEntries(entries: List<app.gamenative.mods.ModArchiveEntry>) {
        val seen = hashSetOf<String>()
        for (entry in entries) {
            val path = entry.path.replace('\\', '/')
            require(path.split('/').all { part -> part.isNotBlank() && part !in setOf(".", "..") &&
                part.none { it.code < 32 || it in ":<>|?*\"" } && !part.endsWith('.') && !part.endsWith(' ') }) { "Arkivet innehåller ett ogiltigt Windows-filnamn." }
            require(seen.add(path.lowercase(java.util.Locale.ROOT))) { "Arkivet har dubbla Windows-sökvägar: $path" }
        }
    }

    private suspend fun publish(context: Context, copied: File, root: File): LibraryItem {
        currentCoroutineContext().ensureActive()
        check(!SteamService.keepAlive) { "Avsluta spelet före import." }
        // The copy receives its own library identity and must not become a Steam import.
            val oldMetadata = File(copied, ".gamenative")
            check(!oldMetadata.exists() || oldMetadata.delete()) { "Kunde inte skapa en separat spelidentitet." }
            File(copied, ".gamenative-offline").writeText("1\n")
            val executable = copied.walkTopDown().maxDepth(12).take(20_000).firstOrNull {
                it.isFile && it.extension.equals("exe", true) && OfflineGameTools.peArchitecture(it) != null
            }
            require(executable != null) { "Ingen Windows EXE hittades. Välj hela installationsmappen med setup.exe och dess .bin-filer, eller en uppackad spelmapp. Fristående MSI behöver förberedas separat." }
            // No cancellable gap between publishing the directory and registering it.
            return withContext(NonCancellable) {
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
    }
}

class OfflineGameImportViewModel(application: Application) : AndroidViewModel(application) {
    data class State(val busy: Boolean = false, val progress: String = "", val item: LibraryItem? = null, val error: String? = null, val preparing: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun import(uri: Uri, archive: Boolean = false) {
        if (state.value.busy) return
        mutable.value = State(busy = true)
        job = viewModelScope.launch {
            try {
                var shown = 0L
                val report: (CustomGameImporter.Progress) -> Unit = {
                    if (it.copiedBytes - shown >= 8_000_000) {
                        shown = it.copiedBytes
                        mutable.value = State(busy = true, progress = "${shown / 1_000_000} MB kopierat · ${DiagnosticRedactor.text(it.currentFile).take(90)}")
                    }
                }
                val item = if (archive) OfflineGameImport.archive(getApplication(), uri, report) else OfflineGameImport.copy(getApplication(), uri, report)
                mutable.value = State(item = item)
            } catch (e: CancellationException) {
                mutable.value = State(error = "Importen avbröts. Originalfilerna finns kvar."); throw e
            } catch (e: Exception) { mutable.value = State(error = DiagnosticRedactor.text(e.message ?: "Importen misslyckades.").take(500)) }
        }
    }
    fun cancel() { job?.cancel() }
    fun open(item: LibraryItem, ready: () -> Unit) {
        if (state.value.busy) return
        mutable.value = state.value.copy(busy = true, preparing = true, progress = "Förbereder spelets Wine-miljö…", error = null)
        job = viewModelScope.launch {
            try { OfflineGameImport.prepareEnvironment(getApplication(), item); ready() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(error = DiagnosticRedactor.text(e.message ?: "Spelmiljön kunde inte förberedas.").take(500)) }
            finally { mutable.value = mutable.value.copy(busy = false, preparing = false) }
        }
    }
}

class OfflineGameImportActivity : ComponentActivity() {
    private val model: OfflineGameImportViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.AI_ASSISTANT_ENABLED) { finish(); return }
        setContent {
            val state by model.state.collectAsState()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { model.import(it) } }
            val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.import(it, true) } }
            PluviaTheme {
                OfflineGameImportScreen(state, { picker.launch(null) }, model::cancel, { finish() }, pickArchive = { archivePicker.launch(arrayOf("*/*")) }) { item ->
                    model.open(item) {
                        startActivity(Intent(this, GameAssistantActivity::class.java).putExtra("app_id", item.appId).putExtra("game_title", item.name))
                        finish()
                    }
                }
            }
        }
    }
}

@Composable
internal fun OfflineGameImportScreen(state: OfflineGameImportViewModel.State, pick: () -> Unit, cancel: () -> Unit, close: () -> Unit, pickArchive: (() -> Unit)? = null, open: (LibraryItem) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Installera offlinespel med Codex", style = MaterialTheme.typography.headlineSmall)
            Text("Välj hela installationsmappen med setup.exe och tillhörande .bin-filer, eller en färdig spelmapp. Den kopieras till GameNative och får en egen plats i biblioteket. Originalet behålls.")
            Text("Öppna sedan Codex och välj Hjälp med installation och startfil. Codex kan föreslå och starta installeraren i Wine efter din granskning. Slutför Windows-guiden själv; därefter kan Codex hitta och välja spelets EXE.")
            Text("Spelfilerna kan vara offline. Codex-samtalet kräver internet. ZIP/7z kan väljas direkt (högst 20 GiB uppackat och 50 000 poster; ej lösenord eller delade arkiv). Ha plats för både kopian och installationen och håll appen öppen under kopieringen.", style = MaterialTheme.typography.bodySmall)
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress.ifBlank { "Kopierar den valda mappen…" })
                if (!state.preparing) TextButton(onClick = cancel) { Text("Avbryt kopiering") }
            } else {
                state.item?.let { item ->
                    Text("${item.name} finns nu i biblioteket. Installation och spelstart är ännu inte verifierade.")
                    Button(onClick = { open(item) }, modifier = Modifier.testTag("offline-open-chat")) { Text("Fortsätt med Codex") }
                } ?: Button(onClick = pick, modifier = Modifier.testTag("offline-pick-folder")) { Text("Välj spelmapp") }
                if (state.item == null && pickArchive != null) OutlinedButton(onClick = pickArchive, modifier = Modifier.testTag("offline-pick-archive")) { Text("Välj ZIP eller 7z") }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = close) { Text("Tillbaka") }
            }
        }
    }
}
