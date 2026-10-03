package app.gamenative.assistant

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject

private fun reportLabel(report: JSONObject): String {
    val at = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(report.optLong("captureStartedAtMs", report.optLong("startedAtMs"))))
    val status = when (report.optString("state")) { "recording" -> "samlar"; "interrupted" -> "avbruten"; else -> "sparad" }
    val problem = DebugProblem.entries.firstOrNull { it.name == report.optString("problem") }?.label ?: "Felsökning"
    return "$at · $problem · $status"
}

@Composable
internal fun CodexDebugCard(model: GameAssistantViewModel, state: AssistantUiState) {
    val report = state.debugReport ?: return
    val summary = CodexDebugReportStore.summary(report)
    val status by CodexDebugSession.status.collectAsState()
    Card(Modifier.widthIn(max = 800.dp).fillMaxWidth().testTag("debug-report-card")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (state.reportAttached) "Rapport bifogad till chatten" else "Felsökningsrapport", style = MaterialTheme.typography.titleSmall)
            Text(reportLabel(report), style = MaterialTheme.typography.bodySmall)
            Text("${summary.optInt("logLines")} loggrader · ${summary.optInt("performanceSamples")} mätprover · ${report.optJSONArray("markers")?.length() ?: 0} markeringar",
                style = MaterialTheme.typography.bodySmall)
            if (report.optString("state") == "interrupted") Text("Senaste sparade delen efter avbrott. Slutet kan saknas.", style = MaterialTheme.typography.bodySmall)
            if (report.optString("state") == "recording") Text("Underlaget uppdateras före analys.", style = MaterialTheme.typography.bodySmall)
            status?.error?.takeIf { status?.game == state.game }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Button(onClick = model::analyzeDebug, enabled = !state.busy, modifier = Modifier.testTag("debug-analyze")) { Text("Analysera med Codex") }
                TextButton(onClick = model::openDebug, enabled = !state.busy) { Text("Granska") }
                if (state.reportAttached) TextButton(onClick = model::detachDebug, enabled = !state.busy) { Text("Koppla loss") }
            }
            if (!state.reportAttached) Text("Skickas via din ChatGPT-anslutning när du begär analys. Inga ändringar görs automatiskt.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CodexDebugSheet(model: GameAssistantViewModel, inGame: Boolean) {
    val state by model.state.collectAsState()
    if (!state.debugSheet) return
    var problem by remember { mutableStateOf(DebugProblem.OTHER) }
    var review by remember { mutableStateOf(false) }
    var newCapture by remember { mutableStateOf(state.debugReport == null) }
    var history by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf("summary") }
    var offset by remember(state.debugReportId, section) { mutableIntStateOf(0) }
    val report = state.debugReport
    val status by CodexDebugSession.status.collectAsState()
    val recording = status?.let { it.game == state.game && it.recording } == true
    ModalBottomSheet(onDismissRequest = model::closeDebug, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Felsök med Codex", style = MaterialTheme.typography.titleLarge)
            Text("Samla underlag lokalt, markera felet och analysera i den här chatten.")
            if (report != null && !recording) TextButton(onClick = { newCapture = !newCapture }) { Text(if (newCapture) "Dölj startval" else "Ny insamling") }
            if (!recording && newCapture) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DebugProblem.entries.forEach { choice -> FilterChip(selected = problem == choice, onClick = { problem = choice }, label = { Text(choice.label) }) }
                }
                Text(when {
                    inGame && problem == DebugProblem.CRASH -> "Läser tillgänglig utdata från denna körning. Utförlig Wine-logg kräver en ny spelstart; inga loggar kan återskapas i efterhand."
                    problem == DebugProblem.CRASH -> "Aktiverar extra Wine-loggning bara under nästa körning. Det kan påverka prestandan."
                    problem == DebugProblem.INPUT -> "Använder appens befintliga inputtest. Tryck knappar och spakar och kontrollera om spelet reagerar. Ingen input eller inställning ändras av insamlingen."
                    else -> "Läser befintliga mätvärden och tillgänglig processlogg. Inga inställningar ändras."
                }, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { model.startDebug(problem) }, enabled = !state.busy, modifier = Modifier.testTag("debug-start")) {
                    Text(if (inGame) "Samla från pågående spel" else "Starta spel och samla")
                }
            } else if (recording) {
                Text("Insamling pågår · ${status?.markers ?: 0} markeringar")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = model::markDebug, enabled = !state.busy) { Text("Problemet händer nu") }
                    OutlinedButton(onClick = model::stopDebug, enabled = !state.busy) { Text("Avsluta insamling") }
                }
            }
            if (state.status.isNotBlank() && !state.busy) Text(state.status, style = MaterialTheme.typography.bodySmall)
            if (report != null) {
                HorizontalDivider()
                Text(reportLabel(report), style = MaterialTheme.typography.titleSmall)
                if (state.debugReports.size > 1) TextButton(onClick = { history = !history }) { Text(if (history) "Dölj tidigare körningar" else "Byt rapport (${state.debugReports.size})") }
                if (history) state.debugReports.forEach { item -> TextButton(onClick = { model.selectDebugReport(item.getString("id")); history = false }, enabled = !state.busy) {
                    Text("${if (state.debugReportId == item.optString("id")) "✓ " else ""}${reportLabel(item)}")
                } }
                OutlinedTextField(value = state.prompt, onValueChange = model::prompt, label = { Text("Vad hände? (valfritt)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                Button(onClick = model::analyzeDebug, enabled = !state.busy) { Text("Analysera med Codex") }
                TextButton(onClick = { review = !review }, modifier = Modifier.testTag("debug-review")) { Text(if (review) "Dölj underlag" else "Visa underlag före analys") }
                if (review) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        mapOf("summary" to "Översikt", "log" to "Logg", "performance" to "Mätningar", "controller" to "Input").forEach { (key, label) ->
                            FilterChip(selected = section == key, onClick = { section = key }, label = { Text(label) })
                        }
                    }
                    val data = remember(report, section, offset) {
                        if (section == "summary") CodexDebugReportStore.summary(report) else JSONObject(CodexDebugReportStore.query(report,
                            JSONObject().put("section", section).put("query", "").put("from_ms", 0).put("to_ms", 0).put("offset", offset)))
                    }
                    SelectionContainer { Text(data.toString(2), Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
                    Row {
                        TextButton(onClick = { offset = 0 }, enabled = offset > 0) { Text("Början") }
                        if (!data.isNull("nextOffset")) TextButton(onClick = { offset = data.getInt("nextOffset") }) { Text("Nästa del") }
                    }
                }
                Text("Endast filtrerat underlag för vald körning blir tillgängligt för Codex när du begär analys. Bilder bifogas separat. Saknade mätvärden visas som okända.", style = MaterialTheme.typography.bodySmall)
                if (report.optString("state") != "recording") TextButton(onClick = model::deleteDebug, enabled = !state.busy) { Text("Ta bort lokal rapport") }
            }
            HorizontalDivider()
            TextButton(onClick = model::importOldDebug, enabled = !state.busy) { Text("Importera äldre lokal debugrapport") }
            Text("Högst 10 rapporter per spel och 40 totalt. Äldre rapporter gallras vid ny insamling. Chattsvar och säkerhetskopior är separata.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = model::closeDebug) { Text("Tillbaka till chatten") }
        }
    }
}

/** Small touch targets only; no key/gamepad listeners or routing changes. */
@Composable
internal fun CodexDebugOverlay(game: String) {
    val status by CodexDebugSession.status.collectAsState()
    val current = status?.takeIf { it.game == game && it.recording } ?: return
    if (InGameAssistantUi.isOpenFor(game)) return
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current.reportId) {
        if (CodexDebugSession.claimInputTest(game, current.reportId) && ControllerInputTrace.summary(game)?.active != true) {
            delay(750)
            if (CodexDebugSession.status.value?.reportId != current.reportId || CodexDebugSession.status.value?.recording != true) return@LaunchedEffect
            runCatching {
                ControllerInputTrace.start(game, ControllerTraceBuffer.Mode.TEST)
                ControllerTestUi.overlayGame = game
            }.onFailure { error = "Öppna Codex → Kontroll och input för att starta inputtestet när spelet är redo." }
        }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp), contentAlignment = Alignment.TopStart) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
            Column(Modifier.padding(horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Felsökning · ${current.markers}", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { scope.launch { runCatching { CodexDebugSession.mark(game) }.onFailure { error = it.message } } }, modifier = Modifier.focusProperties { canFocus = false }.testTag("debug-mark-live")) { Text("Markera fel") }
                    TextButton(onClick = { InGameAssistantUi.open(game) }, modifier = Modifier.focusProperties { canFocus = false }) { Text("Codex") }
                }
                (error ?: current.error)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
