package app.gamenative.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal object ControllerTestUi {
    var detailsGame by mutableStateOf<String?>(null)
    var overlayGame by mutableStateOf<String?>(null)
    fun clear(game: String) {
        if (detailsGame == game) detailsGame = null
        if (overlayGame == game) overlayGame = null
    }
}

@Composable
internal fun ControllerTestOverlay(game: String) {
    if (ControllerTestUi.overlayGame != game) return
    var probe by remember(game) { mutableStateOf(ControllerInputTrace.summary(game)) }
    LaunchedEffect(game) { while (isActive) { probe = ControllerInputTrace.summary(game); delay(250) } }
    val result = probe ?: return
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.TopEnd) {
        Surface(Modifier.widthIn(max = 320.dp).testTag("controller-test-overlay"), shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), tonalElevation = 6.dp) {
            Column(Modifier.padding(12.dp)) {
                Text(if (result.active) "Kontrolltest · ${result.secondsLeft} s" else "Kontrolltest klart", style = MaterialTheme.typography.titleSmall)
                Text("Android: ${result.androidSamples} prover · Wine-brygga: ${result.wineSamples}", style = MaterialTheme.typography.bodySmall)
                Text("Tryck knappar, styrkors, spakar och triggers. Kontrollera om spelet reagerar.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    ControllerInputTrace.finish(game)
                    ControllerTestUi.overlayGame = null
                    ControllerTestUi.detailsGame = game
                    InGameAssistantUi.open(game)
                }) { Text(if (result.active) "Avsluta och granska" else "Granska testet") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ControllerTestButton(model: GameAssistantViewModel) {
    val state by model.state.collectAsState()
    val game = state.game
    TextButton(onClick = { ControllerTestUi.detailsGame = game }, enabled = game.isNotBlank() && !state.busy,
        modifier = Modifier.testTag("controller-test-open")) { Text("Kontrolltest") }
    if (ControllerTestUi.detailsGame != game || game.isBlank()) return
    var error by remember { mutableStateOf<String?>(null) }
    val result = ControllerInputTrace.summary(game)
    ModalBottomSheet(
        onDismissRequest = { ControllerTestUi.detailsGame = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Felsök kontrollen", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = {
                ControllerTestUi.detailsGame = null
                model.reconnectController()
            }, enabled = !state.busy, modifier = Modifier.testTag("controller-reconnect")) {
                Text("Återanslut kontrollbryggan")
            }
            Text("Prova utan att starta om: bryggan kopplas från kort och ansluts igen. Ändrar inga sparade inställningar. Spelet måste stödja återanslutning; SDL:s API-val ändras inte.", style = MaterialTheme.typography.bodySmall)
            if (result != null) {
                Text("Senaste testet", style = MaterialTheme.typography.titleMedium)
                Text("Android: ${result.androidSamples} prover\nValda profilmappningar: ${result.mappedSamples}\nSkrivningar till Wine-bryggan: ${result.wineSamples}")
                Text("Det visar hur långt signalen har nått i appen. En skrivning till Wine-bryggan bevisar inte att spelet läste den.", style = MaterialTheme.typography.bodySmall)
                if (!state.includeDiagnostics) Text("Aktivera Spelåtkomst vid skrivfältet för att låta assistenten läsa testet.")
                Button(onClick = {
                    model.prompt("Ingen input når spelet. Läs mitt senaste kontrolltest, kontrollens spelarplats, aktiva mappningar och körningens kontroll-API. Förklara var signalen tar stopp enligt bevisen. Föreslå en relevant ändring med möjlighet att ångra; skilj appens utdata från vad spelet faktiskt läser.")
                    ControllerTestUi.detailsGame = null
                    model.send()
                }, enabled = !state.busy && state.includeDiagnostics && !result.active,
                    modifier = Modifier.testTag("controller-test-analyze")) { Text("Analysera testet") }
                Text("Skickar en fråga om utebliven input med spelåtkomst via din ChatGPT-anslutning. Förslaget visas i chatten; inga inställningar ändras automatiskt.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
            Text("Chatten stängs och testet samlar kontrollsignaler i 20 sekunder. Tryck A/B och styrkorset, rör båda spakarna och tryck in triggers. Kontrollera om spelet reagerar. Öppna sedan resultatet med Granska testet.")
            Text("Testet registrerar bara kontrollsignaler och appens mappning/utdata, aldrig skriven text. Resultatet stannar på enheten tills du begär analys med spelåtkomst.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                runCatching { ControllerInputTrace.start(game) }.onSuccess {
                    ControllerTestUi.detailsGame = null
                    ControllerTestUi.overlayGame = game
                    InGameAssistantUi.close(game)
                }.onFailure { error = it.message ?: "Kontrolltestet kunde inte startas." }
            }, enabled = !state.busy, modifier = Modifier.testTag("controller-test-start")) {
                Text(if (result == null) "Starta kontrolltest · 20 s" else "Gör ett nytt test · 20 s")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { ControllerTestUi.detailsGame = null }) { Text("Tillbaka till chatten") }
        }
    }
}
