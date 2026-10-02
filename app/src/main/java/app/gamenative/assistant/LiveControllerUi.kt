package app.gamenative.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun LiveControllerProposal(model: GameAssistantViewModel, proposal: ConfigProposal) {
    val state by model.state.collectAsState()
    val undo by LiveControllerChanges.undo.collectAsState()
    val preview = remember(state.game, proposal, undo, state.busy) { LiveControllerChanges.preview(state.game, proposal) }
    if (preview == null) {
        if (proposal.patch().keys.any { it in setOf("inputType", "dinputMapperType", "sdlControllerAPI", "extraData.useSteamInput") }) {
            Text("Liveförsök kräver ett separat förslag med bara kontroll-API/DirectInput-mappare och en aktiv brygga med ett annat läge. SDL och Steam Input läses vid spelstart.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Prova utan att starta om", style = MaterialTheme.typography.titleSmall)
            Text("Bryggan: ${preview.before.describe()} → ${preview.after.describe()}", style = MaterialTheme.typography.bodySmall)
            Text("Gäller bryggans nästa kontrollförfrågan och kan ångras direkt. Sparas inte. SDL:s startval och kontrollenheter som spelet redan öppnat påverkas inte.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { model.applyLive(preview) }, enabled = !state.busy && state.includeDiagnostics,
                modifier = Modifier.testTag("controller-try-live")) { Text("Prova bryggan live") }
        }
    }
}

@Composable
internal fun LiveControllerUndo(model: GameAssistantViewModel) {
    val state by model.state.collectAsState()
    val undo by LiveControllerChanges.undo.collectAsState()
    val trial = undo?.takeIf { it.game == state.game && it.token == LiveGameSession.token() } ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Liveförsök i bryggan: ${trial.after.describe()}\nGäller bara denna omgång.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = model::restoreLive, enabled = !state.busy,
            modifier = Modifier.testTag("controller-undo-live")) { Text("Ångra liveförsök") }
    }
}
