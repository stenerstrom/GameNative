package app.gamenative.assistant

import android.app.Application
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

/** Only opening/closing the panel invalidates XServerScreen; metrics stay in the small panel. */
object InGameAssistantUi {
    private var game by mutableStateOf<String?>(null)
    // Static hooks avoid extra receiver temporaries in the very large XServerScreen method.
    @JvmStatic fun isOpenFor(appId: String) = game == appId
    @JvmStatic fun open(appId: String) { if (BuildConfig.AI_ASSISTANT_ENABLED) game = appId }
    @JvmStatic fun close(appId: String) { if (game == appId) game = null }
}

@Composable
fun AssistantQuickMenuButton(game: String?, onDismissMenu: () -> Unit) {
    if (!BuildConfig.AI_ASSISTANT_ENABLED || game == null) return
    FilledTonalButton(onClick = {
        // Set this before dismissing: even manual-resume mode must resume for live investigation.
        InGameAssistantUi.open(game)
        onDismissMenu()
    }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("open-in-game-assistant")) {
        Text("Codex i spelet")
    }
}

/** Composed beside QuickMenu's animation, never inside it or in a separate Android activity. */
@Composable
fun InGameAssistantHost(game: String?) {
    if (!BuildConfig.AI_ASSISTANT_ENABLED || game == null) return
    DisposableEffect(game) { onDispose {
        InGameAssistantUi.close(game); ControllerTestUi.clear(game); GameOptimizationUi.clear(game)
        GameOptimizationSession.stop(game, "Spelvyn stängdes.")
    } }
    ControllerTestOverlay(game)
    GameOptimizationOverlay(game)
    CodexDebugOverlay(game)
    if (!InGameAssistantUi.isOpenFor(game)) return
    val context = LocalContext.current
    val model: GameAssistantViewModel = viewModel(key = "in-game-assistant:$game",
        factory = ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))
    LaunchedEffect(game) {
        val title = withContext(Dispatchers.IO) { runCatching { ContainerUtils.resolveGameName(game) }.getOrDefault(game) }
        model.initialize(game, title)
    }
    InGameAssistantPanel(model, { InGameAssistantUi.close(game) }) { url ->
        CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
    }
}

@Composable
internal fun InGameAssistantPanel(model: GameAssistantViewModel, onClose: () -> Unit, openBrowser: (String) -> Unit) {
    val state by model.state.collectAsState()
    DisposableEffect(state.game) {
        ControllerInputTrace.finish(state.game)
        if (ControllerTestUi.overlayGame == state.game) ControllerTestUi.overlayGame = null
        LiveGameSession.assistant(state.game, true)
        onDispose { LiveGameSession.assistant(state.game, false) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false,
    )) {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("in-game-assistant-panel"), contentAlignment = Alignment.CenterEnd) {
            val panelWidth = if (maxWidth >= 840.dp) (maxWidth * 0.6f).coerceAtMost(720.dp) else maxWidth
            Surface(Modifier.width(panelWidth).fillMaxHeight(), tonalElevation = 6.dp, shadowElevation = 12.dp) {
                AssistantScreen(model, onClose, openBrowser, inGame = true)
            }
        }
    }
}

@Composable
internal fun LiveSessionBanner(game: String, model: GameAssistantViewModel) {
    var live by remember(game) { mutableStateOf(LiveGameSession.view(game)) }
    LaunchedEffect(game) {
        while (isActive) {
            live = LiveGameSession.view(game)
            delay(1000) // Local UI only. No model calls and no new metrics collector.
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val current = live?.current
            val status = when (live?.status) {
                "live" -> String.format(Locale.ROOT, "Live · %.1f FPS · bildtid p95 %.1f ms", current?.fps, current?.frameTimeP95Ms)
                "paused" -> "Spelet är pausat · inga aktuella FPS"
                "background" -> "Mätningen är pausad i bakgrunden"
                "warming_up" -> "Samlar nya bildtider efter start/paus…"
                "stale" -> "Mätvärdena är inte längre aktuella"
                "no_frames" -> "Inga nya bildrutor registrerade"
                else -> "Väntar på mätdata från spelet…"
            }
            Text(status, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("live-session-status"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Analys när du frågar med spelåtkomst.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                ControllerTestButton(model)
            }
            LiveControllerUndo(model)
            if (live?.paused == true) TextButton(onClick = {
                if (LiveGameSession.view(game)?.paused == true && SteamService.keepAlive && PluviaApp.isOverlayPaused) {
                    PluviaApp.xEnvironment?.let {
                        it.onResume()
                        PluviaApp.isOverlayPaused = false
                        PluviaApp.inputControlsView?.setGyroGameplayActive(true)
                    }
                }
            }) { Text("Återuppta spelet") }
        }
    }
}
