package app.gamenative.updates

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.BuildConfig
import app.gamenative.ui.theme.PluviaTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiDevUpdateActivity : ComponentActivity() {
    private val model: AiDevUpdateViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.AI_ASSISTANT_ENABLED) { finish(); return }
        model.initialize()
        setContent {
            val state by model.state.collectAsState()
            PluviaTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("GameNative AI Dev updates", style = MaterialTheme.typography.headlineSmall)
                        Text("Installed: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        Text("Updates come from your GameNative fork. Install over this app to keep games, settings and sign-ins. Do not uninstall or clear app data.")
                        OutlinedButton(onClick = model::check, enabled = !state.busy) { Text("Check for updates") }
                        SelectionContainer { Text(state.status) }
                        state.update?.let { update ->
                            if (update.versionCode > BuildConfig.VERSION_CODE) {
                                Text("Available: ${update.versionName} · ${update.size / (1024 * 1024)} MiB")
                                if (update.notes.isNotBlank()) Text(update.notes)
                                if (state.ready) Button(onClick = { model.install(::startActivity) }, enabled = !state.busy) { Text("Install update") }
                                else Button(onClick = model::download, enabled = !state.busy) { Text("Download update") }
                            }
                        }
                        if (state.busy) {
                            state.progress?.let { LinearProgressIndicator(progress = { it }) } ?: LinearProgressIndicator()
                            TextButton(onClick = model::cancel) { Text("Cancel") }
                        }
                        Text("Android asks you to approve installation. Close running games first. If installation permission is needed, allow it for GameNative AI Dev, return here, then tap Install update again.")
                        TextButton(onClick = { finish() }) { Text("Back") }
                    }
                }
            }
        }
    }
}

data class AiDevUpdateState(val busy: Boolean = false, val status: String = "", val update: AiDevUpdate? = null, val progress: Float? = null, val ready: Boolean = false)

class AiDevUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val updater = AiDevUpdater(application)
    private val mutable = MutableStateFlow(AiDevUpdateState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var file: File? = null
    private var initialized = false

    fun initialize() { if (!initialized) { initialized = true; check() } }
    fun cancel() { job?.cancel() }
    fun check() = action("Checking the AI Dev update channel…") {
        val update = updater.check()
        file = null
        mutable.update { it.copy(update = update, ready = false, status = if (update.versionCode > BuildConfig.VERSION_CODE) "An update is available." else "GameNative AI Dev is up to date.") }
    }
    fun download() = action("Downloading update…") {
        val update = requireNotNull(state.value.update)
        check(update.versionCode > BuildConfig.VERSION_CODE)
        mutable.update { it.copy(ready = false, progress = 0f) }
        file = updater.download(update) { progress -> mutable.update { it.copy(progress = progress) } }
        mutable.update { it.copy(ready = true, status = "Download checked: package, version, checksum and signing certificate match. Ready to install.") }
    }
    fun install(launch: (Intent) -> Unit) = action("Preparing Android installation…") {
        if (!updater.canInstall()) {
            mutable.update { it.copy(status = "Allow installation from GameNative AI Dev, then return and tap Install update again.") }
            launch(updater.permissionIntent())
        } else {
            val intent = updater.installIntent(requireNotNull(file), requireNotNull(state.value.update))
            launch(intent)
            mutable.update { it.copy(status = "Android installer opened. Confirm Update there; no uninstall is needed.") }
        }
    }
    private fun action(message: String, block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, status = message, progress = null) }
        job = viewModelScope.launch {
            try { block() } catch (e: CancellationException) {
                mutable.update { it.copy(status = "Cancelled. The installed app is unchanged.") }
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(status = e.message ?: "Update failed. Keep the installed app and try again.") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
