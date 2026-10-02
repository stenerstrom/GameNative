package app.gamenative.assistant

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.gamenative.data.LibraryItem
import app.gamenative.mods.ModContainerResolver
import app.gamenative.ui.component.dialog.NexusModsDialog
import app.gamenative.ui.util.LocalSnackbarHostController
import app.gamenative.ui.util.SnackbarHostController
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps existing Nexus auth, NXM downloads, FOMOD and profiles available without leaving the app. */
@Composable
fun AssistantModLibrary(game: String, title: String, close: () -> Unit) {
    val context = LocalContext.current
    var loaded by remember(game) { mutableStateOf(false) }
    var root by remember(game) { mutableStateOf<File?>(null) }
    var prefix by remember(game) { mutableStateOf("") }
    var error by remember(game) { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostController() }
    LaunchedEffect(game) {
        try {
            val paths = withContext(Dispatchers.IO) {
                GameFileRoots.discover(context, game).firstOrNull { it.id == "game" }?.directory to
                    ModContainerResolver.getWinePrefix(context, game)
            }
            root = paths.first
            prefix = paths.second
            loaded = true
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            error = DiagnosticRedactor.text(failure.message ?: "Modbiblioteket kunde inte öppnas")
        }
    }
    LaunchedEffect(snackbar) { SnackbarManager.messages.collect { snackbar.hostState.showSnackbar(it) } }
    if (loaded) CompositionLocalProvider(LocalSnackbarHostController provides snackbar) {
        NexusModsDialog(true, LibraryItem(appId = game, name = title, gameSource = ContainerUtils.extractGameSourceFromContainerId(game)),
            root, prefix, close)
    } else AlertDialog(onDismissRequest = close, title = { Text("Modbibliotek") },
        text = { error?.let { Text(it) } ?: CircularProgressIndicator() },
        confirmButton = { TextButton(onClick = close) { Text("Stäng") } })
}
