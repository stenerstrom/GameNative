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
import org.json.JSONObject

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
    var compact by remember(game) { mutableStateOf(false) }
    LaunchedEffect(game) { while (isActive) { probe = ControllerInputTrace.summary(game); delay(250) } }
    val result = probe ?: return
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.TopEnd) {
        Surface(Modifier.widthIn(max = 320.dp).testTag("controller-test-overlay"), shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), tonalElevation = 6.dp) {
            Column(Modifier.padding(12.dp)) {
                val live = result.mode == ControllerTraceBuffer.Mode.LIVE
                Text(if (result.active) "${if (live) "Input live" else "Kontrolltest"} · ${result.secondsLeft} s"
                    else if (live) "Inputvisning avslutad" else "Kontrolltest klart", style = MaterialTheme.typography.titleSmall)
                if (!compact && live) ControllerSignalRows(result)
                else Text("Android: ${result.androidSamples} prover · Wine-brygga: ${result.wineSamples}", style = MaterialTheme.typography.bodySmall)
                if (!compact) Text(if (live) "Visar appens signaler. Spelets mottagning är inte bekräftad."
                    else "Tryck knappar, styrkors, spakar och triggers. Kontrollera om spelet reagerar.", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { compact = !compact }, modifier = Modifier.testTag("controller-overlay-size")) {
                        Text(if (compact) "Visa mer" else "Minimera")
                    }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ControllerTestButton(model: GameAssistantViewModel) {
    val state by model.state.collectAsState()
    val game = state.game
    TextButton(onClick = { ControllerTestUi.detailsGame = game }, enabled = game.isNotBlank() && !state.busy,
        modifier = Modifier.testTag("controller-test-open")) { Text("Kontroll och input") }
    if (ControllerTestUi.detailsGame != game || game.isBlank()) return
    var error by remember { mutableStateOf<String?>(null) }
    var showDestinations by remember { mutableStateOf(false) }
    val result = ControllerInputTrace.summary(game)
    val route = remember(game) { ControllerInputRoute.snapshot(game) }
    fun start(mode: ControllerTraceBuffer.Mode) {
        runCatching { ControllerInputTrace.start(game, mode) }.onSuccess {
            ControllerTestUi.detailsGame = null
            ControllerTestUi.overlayGame = game
            InGameAssistantUi.close(game)
        }.onFailure { error = it.message ?: "Inputvisningen kunde inte startas." }
    }
    ModalBottomSheet(
        onDismissRequest = { ControllerTestUi.detailsGame = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Kontroll och input", style = MaterialTheme.typography.titleLarge)
            Text("Se signalerna medan du spelar. Chatten stängs och en liten panel visar Android → mappning → kontrollbrygga. Stoppar senast efter 5 minuter.")
            Button(onClick = { start(ControllerTraceBuffer.Mode.LIVE) }, enabled = !state.busy,
                modifier = Modifier.testTag("controller-live-start")) { Text("Visa input live") }
            Text("Endast avläsning, ingen simulerad input. Signalerna stannar på enheten tills du begär analys med spelåtkomst. Inga skrivna texter registreras.", style = MaterialTheme.typography.bodySmall)
            if (result != null) {
                Text("Senaste observationen", style = MaterialTheme.typography.titleMedium)
                ControllerSignalRows(result)
                Text("Android: ${result.androidSamples} prover\nValda profilmappningar: ${result.mappedSamples}\nSkrivningar till Wine-bryggan: ${result.wineSamples}")
            }
            if (!state.includeDiagnostics) Text("Aktivera Spelåtkomst vid skrivfältet för att låta Codex läsa input och inställningar.")
            Button(onClick = {
                model.prompt("Ingen input når spelet. Använd read_input_route för att läsa hela signalvägen, inklusive aktuell konfiguration och min senaste observation om den finns. Förklara var signalen tar stopp enligt bevisen och hur färska de är. Föreslå nästa relevanta steg och vid behov en ändring som går att ångra. Skilj appens utdata från bekräftad mottagning i spelet.")
                ControllerTestUi.detailsGame = null
                model.send()
            }, enabled = !state.busy && state.includeDiagnostics && result?.active != true,
                modifier = Modifier.testTag("controller-test-analyze")) { Text("Analysera input med Codex") }
            Text("Skickar en fråga via din ChatGPT-anslutning. Eventuella ändringar visas för granskning i chatten.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showDestinations = !showDestinations }, modifier = Modifier.testTag("controller-route-details")) {
                Text(if (showDestinations) "Dölj destinationer" else "Visa destinationer och API")
            }
            if (showDestinations) ControllerDestinations(route, result)
            HorizontalDivider()
            Text("Kort test: tryck A/B, styrkors, spakar och triggers. Kontrollera samtidigt om spelet reagerar.")
            OutlinedButton(onClick = {
                start(ControllerTraceBuffer.Mode.TEST)
            }, enabled = !state.busy, modifier = Modifier.testTag("controller-test-start")) {
                Text(if (result == null) "Starta kontrolltest · 20 s" else "Gör ett nytt test · 20 s")
            }
            OutlinedButton(onClick = {
                ControllerTestUi.detailsGame = null
                model.reconnectController()
            }, enabled = !state.busy, modifier = Modifier.testTag("controller-reconnect")) { Text("Återanslut kontrollbryggan") }
            Text("Återanslutning skickar en kort frånkoppling och anslutning. Inga sparade inställningar ändras. Spelet måste stödja återanslutning; SDL:s API-val ändras inte.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { ControllerTestUi.detailsGame = null }) { Text("Tillbaka till chatten") }
        }
    }
}

@Composable
private fun ControllerSignalRows(result: ControllerTraceBuffer.View) {
    listOf(ControllerTraceBuffer.Stage.ANDROID, ControllerTraceBuffer.Stage.PROFILE_BINDING,
        ControllerTraceBuffer.Stage.WINE_BUFFER, ControllerTraceBuffer.Stage.NATIVE_WAKE).forEach { stage ->
        val sample = result.latestStages.firstOrNull { it.stage == stage }
        Column(Modifier.padding(vertical = 3.dp)) {
            Text(ControllerInputRoute.stageTitle(stage) + (sample?.let { " · ${ControllerInputRoute.age(result.elapsedMs - it.atMs)}" } ?: ""),
                style = MaterialTheme.typography.labelMedium)
            Text(sample?.let(ControllerInputRoute::describe) ?: if (stage == ControllerTraceBuffer.Stage.PROFILE_BINDING)
                "Ingen vald profilmappning observerad" else "Ingen signal observerad", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (result.latestStages.any { it.stage == ControllerTraceBuffer.Stage.WINE_BUFFER_UNAVAILABLE }) {
        Text("En skrivning saknade målbuffer. Se destinationerna.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ControllerDestinations(route: JSONObject, result: ControllerTraceBuffer.View?) {
    val bridge = route.optJSONObject("runtimeBridge")
    Text("Brygga vid öppning av denna vy", style = MaterialTheme.typography.titleSmall)
    if (bridge == null) Text("Ingen aktiv kontrollbrygga för detta spel.")
    else {
        Text("API: ${bridge.optString("inputApi", "okänt")} · äldre klienter: ${bridge.optInt("legacyGamepadClientCount")}")
        val buffers = bridge.optJSONArray("buffers")
        repeat(buffers?.length() ?: 0) { i ->
            val slot = buffers!!.getJSONObject(i)
            val state = when {
                !slot.optBoolean("bufferReady") -> "saknas"
                !slot.optBoolean("slotEnabled") -> "spelarplats avstängd"
                !slot.optBoolean("connected") -> "frånkopplad"
                else -> "ansluten"
            }
            val wake = when (slot.opt("nativeWakeReady")) { true -> "OK"; false -> "fel"; else -> "ej testad" }
            Text("P${slot.getInt("player")} → ${slot.optString("destination")}\n$state · native-signal: $wake", style = MaterialTheme.typography.bodySmall)
            if (slot.optBoolean("bufferReady")) {
                val axes = slot.getJSONObject("encodedAxes")
                val buttons = slot.getJSONArray("pressedButtons")
                Text("Knappar: ${(0 until buttons.length()).joinToString { buttons.getString(it) }.ifEmpty { "inga" }}\n" +
                    "Kodade axlar: ${listOf("LX", "LY", "RX", "RY", "LT", "RT").joinToString { "$it ${axes.getInt(it)}" }}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Text("Observerade äldre Wine-klienter", style = MaterialTheme.typography.titleSmall)
    if (result?.clients.isNullOrEmpty()) Text("Ingen begäran i observationen. SDL kan läsa delat minne utan dessa anrop.", style = MaterialTheme.typography.bodySmall)
    result?.clients?.forEach { client ->
        Text("${client.api} · rapporterat PID ${client.processId ?: "?"} → lokal UDP-port ${client.port}\n${client.statePackets} tillståndspaket skickade · ${client.sendFailures} sändningsfel · ${ControllerInputRoute.age(result.elapsedMs - client.lastAtMs)}",
            style = MaterialTheme.typography.bodySmall)
    }
    Text("PID är klientens uppgift och är inte verifierat som spelets process. Skrivningar, sändningar och native-signaler bevisar inte att spelet tog emot input. Avläsningarna är separata och kan komma från olika knapptryckningar.", style = MaterialTheme.typography.bodySmall)
}
