package app.gamenative.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.Locale

internal object GameOptimizationUi {
    var detailsGame by mutableStateOf<String?>(null)
    var overlayGame by mutableStateOf<String?>(null)
    fun clear(game: String) {
        if (detailsGame == game) detailsGame = null
        if (overlayGame == game) overlayGame = null
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GameOptimizationButton(model: GameAssistantViewModel, inGame: Boolean) {
    val state by model.state.collectAsState()
    val game = state.game
    TextButton(onClick = { GameOptimizationUi.detailsGame = game }, enabled = game.isNotBlank() && !state.busy,
        modifier = Modifier.testTag("optimization-open")) { Text("Optimera") }
    if (GameOptimizationUi.detailsGame != game || game.isBlank()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember(game) { mutableStateOf<JSONObject?>(null) }
    var target by remember(game) { mutableIntStateOf(30) }
    var scene by remember(game) { mutableStateOf("") }
    var localBusy by remember(game) { mutableStateOf(false) }
    var error by remember(game) { mutableStateOf<String?>(null) }
    suspend fun reload() {
        GameOptimizationSession.awaitSaved()
        data = withContext(Dispatchers.IO) { GameOptimizationSession.store(context, game).read() }
        error = GameOptimizationSession.saveError(game)
    }
    fun act(block: suspend () -> Unit) {
        if (localBusy || state.busy) return
        scope.launch {
            localBusy = true; error = null
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = DiagnosticRedactor.text(e.message ?: "Åtgärden misslyckades.").take(400) }
            finally { localBusy = false }
        }
    }
    LaunchedEffect(game) {
        try { reload(); target = data!!.getInt("targetFps"); scene = data!!.getString("scene") }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
    }
    ModalBottomSheet(onDismissRequest = { GameOptimizationUi.detailsGame = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Optimera ${state.gameTitle}", style = MaterialTheme.typography.titleLarge)
            Text("Välj mål → mät → prova en ändring → mät samma scen igen. Mål och resultat sparas separat för varje spel.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameOptimizationStore.targets.forEach { fps ->
                    FilterChip(selected = target == fps, onClick = { target = fps }, label = { Text("$fps FPS") }, enabled = data != null && !localBusy && !state.busy,
                        modifier = Modifier.testTag("optimization-target-$fps"))
                }
            }
            Text("Målet ändrar inga spelinställningar i sig.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = scene, onValueChange = { scene = it.take(120) }, label = { Text("Testscen / sparpunkt") },
                placeholder = { Text("Samma sparpunkt och runda vid varje test") }, singleLine = true,
                enabled = data != null && !localBusy && !state.busy, modifier = Modifier.fillMaxWidth().testTag("optimization-scene"))
            Button(onClick = { act {
                withContext(Dispatchers.IO) { GameOptimizationSession.store(context, game).goal(target, scene) }
                check(!model.state.value.busy)
                model.prompt("Optimera det här spelet för stabila $target FPS utifrån inställningarna, hårdvaran och mina sparade mätningar. Föreslå ett motiverat försök åt gången som går att ångra. Bevara fungerande kontroller. Om mätning saknas, hjälp mig ta en referens först.")
                GameOptimizationUi.detailsGame = null
                model.send()
            } }, enabled = !localBusy && !state.busy && data != null && state.includeDiagnostics && state.accounts.accounts.any { it.id == state.accounts.selected && it.planEnabled } && state.selectedModel.isNotBlank(),
                modifier = Modifier.testTag("optimization-analyze")) { Text("Be Codex optimera") }
            Text(if (state.includeDiagnostics) "Skickar en fråga med spelåtkomst. Förslag visas i chatten för tillämpning och ångra."
                else "Aktivera spelåtkomst i chatten för att låta Codex läsa inställningar och mätningar.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { act {
                withContext(Dispatchers.IO) { GameOptimizationSession.start(context, game, target, scene) }
                GameOptimizationUi.detailsGame = null
                GameOptimizationUi.overlayGame = game
                InGameAssistantUi.close(game)
            } }, enabled = inGame && !localBusy && !state.busy && data != null && scene.isNotBlank(),
                modifier = Modifier.testTag("optimization-measure")) { Text("Mät 60 sekunder") }
            Text(if (inGame) "Chatten stängs. Efter 5 sekunders nedräkning: spela samma runda i 60 sekunder. Behåll samma grafikval i spelet, moddar, ljusstyrka, laddning och temperatur. Ingen AI-användning under mätningen."
                else "Starta spelet och öppna Codex i spelet → Optimera för att mäta. Vanlig spelstart räcker.", style = MaterialTheme.typography.bodySmall)
            data?.let { profile ->
                val runs = profile.getJSONArray("runs")
                if (runs.length() == 0) Text("Ingen mätning ännu. Första fullständiga mätningen blir din referens.")
                else {
                    HorizontalDivider()
                    val latest = runs.getJSONObject(runs.length() - 1)
                    val baseline = (0 until runs.length()).map { runs.getJSONObject(it) }.firstOrNull { it.getString("id") == profile.optString("baselineId") }
                    Text("Mätningar för detta spel", style = MaterialTheme.typography.titleMedium)
                    OptimizationResult("Referens", baseline)
                    if (baseline?.optString("id") != latest.getString("id")) OptimizationResult("Senast", latest)
                    val comparison = GameOptimizationStore.comparison(profile)
                    if (comparison.getBoolean("eligible")) {
                        Text(String.format(Locale.ROOT, "Skillnad: %+.1f FPS · %+.1f ms bildtid p95 (fönstersnitt)",
                            comparison.getDouble("meanWindowFpsDelta"), comparison.getDouble("meanWindowP95MsDelta")))
                        Text("Jämför bara om du upprepade samma scen och förhållanden. Upprepa försöket innan du drar slutsatser.", style = MaterialTheme.typography.bodySmall)
                    } else Text(comparison.getJSONArray("issues").let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } }, style = MaterialTheme.typography.bodySmall)
                    if (latest.getBoolean("usable") && baseline?.optString("id") != latest.getString("id")) TextButton(onClick = { act {
                        withContext(Dispatchers.IO) { GameOptimizationSession.store(context, game).baseline(latest.getString("id")) }
                        reload()
                    } }, enabled = !localBusy, modifier = Modifier.testTag("optimization-baseline")) { Text("Använd senaste som ny referens") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { GameOptimizationUi.detailsGame = null }) { Text("Tillbaka till chatten") }
        }
    }
}

@Composable
private fun OptimizationResult(label: String, run: JSONObject?) {
    if (run == null) { Text("$label: saknas"); return }
    val summary = run.getJSONObject("summary")
    Text("$label · ${run.getString("scene")} · mål ${run.getInt("targetFps")} FPS", style = MaterialTheme.typography.titleSmall)
    if (!summary.isNull("meanWindowFps")) Text(String.format(Locale.ROOT,
        "%.1f FPS · bildtid p95 %.1f ms (fönstersnitt)\n%d av 60 sekunder med giltiga data", summary.getDouble("meanWindowFps"),
        summary.getDouble("meanWindowP95Ms"), summary.getInt("observedWindowSeconds")))
    if (!run.getBoolean("usable")) Text(run.getJSONArray("issues").let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } },
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun GameOptimizationOverlay(game: String) {
    if (GameOptimizationUi.overlayGame != game) return
    var capture by remember(game) { mutableStateOf(GameOptimizationSession.capture.view(game)) }
    LaunchedEffect(game) { while (isActive) { GameOptimizationSession.poll(); capture = GameOptimizationSession.capture.view(game); delay(500) } }
    val run = capture ?: return
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.TopEnd) {
        Surface(Modifier.widthIn(max = 280.dp).testTag("optimization-overlay"), shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
            Column(Modifier.padding(12.dp)) {
                Text(when {
                    !run.active -> "Mätningen avslutad"
                    run.countdown > 0 -> "Börjar om ${run.countdown} s"
                    run.secondsLeft == 0 -> "Slutför mätningen…"
                    else -> "Mäter · ${run.secondsLeft} s kvar"
                }, style = MaterialTheme.typography.titleSmall)
                Text("Spela din valda scen. Ingen inställning ändras.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    GameOptimizationSession.stop(game, "Mätningen avbröts av användaren.")
                    GameOptimizationUi.overlayGame = null
                    GameOptimizationUi.detailsGame = game
                    InGameAssistantUi.open(game)
                }, modifier = Modifier.testTag("optimization-review")) { Text(if (run.active) "Avbryt" else "Granska mätning") }
            }
        }
    }
}
