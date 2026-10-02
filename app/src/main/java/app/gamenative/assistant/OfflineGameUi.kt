package app.gamenative.assistant

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.gamenative.BuildConfig
import app.gamenative.MainActivity

internal object OfflineGameLaunch {
    fun intent(context: Context, game: String): Intent {
        require(game.matches(Regex("CUSTOM_GAME_[1-9][0-9]*")))
        val id = game.removePrefix("CUSTOM_GAME_").toInt()
        return Intent(context, MainActivity::class.java).setAction("${BuildConfig.APPLICATION_ID}.LAUNCH_GAME")
            .putExtra("app_id", id).putExtra("game_source", "CUSTOM_GAME")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

@Composable
internal fun OfflineGameCard(model: GameAssistantViewModel, state: AssistantUiState, inGame: Boolean) {
    state.offlineProposal?.let { proposal ->
        Card(Modifier.widthIn(max = 800.dp).fillMaxWidth().testTag("offline-proposal")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (proposal.action == "run_installer") "Installera lokalt spel" else "Välj spelets startfil", style = MaterialTheme.typography.titleMedium)
                Text(proposal.path)
                AssistantText(proposal.reason)
                Text(if (proposal.action == "run_installer") "Öppnar EXE-filen i spelets Wine-miljö. Slutför Windows-guiden och välj gärna C:\\Games eller A:\\Installed som installationsmapp. Stäng sedan installeraren och be Codex hitta spelets startfil."
                    else "Sparar vald EXE som startfil för detta spel. Spelet startas från biblioteket.", style = MaterialTheme.typography.bodySmall)
                Text("Befintliga startargument töms. Ångra startfil återställer startval och argument; installerade filer och registerändringar omfattas inte.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = model::applyOffline, enabled = !inGame && !state.busy && state.includeDiagnostics,
                    modifier = Modifier.testTag("offline-apply")) {
                    Text(if (proposal.action == "run_installer") "Starta installeraren" else "Använd som startfil")
                }
                TextButton(onClick = model::dismissProposal, enabled = !state.busy) { Text("Avstå") }
            }
        }
    }
    if (state.offlineUndo) OutlinedButton(onClick = model::restoreOffline, enabled = !inGame && !state.busy,
        modifier = Modifier.testTag("offline-undo")) { Text("Ångra startfil") }
}
