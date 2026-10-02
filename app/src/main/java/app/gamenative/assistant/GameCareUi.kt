@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.gamenative.assistant

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameCareSheet(model: GameAssistantViewModel, inGame: Boolean, close: () -> Unit) {
    val state by model.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf("Kontroll före start") }
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("game") }
    var enabledMods by remember { mutableStateOf<List<String>?>(null) }
    var delete by remember { mutableStateOf<JSONObject?>(null) }
    val inventory = remember(state.careInventory) { JSONObject(state.careInventory) }
    val control = remember(state.controlInventory) { JSONObject(state.controlInventory) }
    val mods = inventory.optJSONObject("mods")?.optJSONArray("mods")?.objects().orEmpty()
    val profiles = inventory.optJSONArray("profiles")?.objects().orEmpty()
    LaunchedEffect(Unit) { model.loadCare() }
    fun proposal(action: String, selectedKind: String, id: String = "", profileName: String = name, selectedMods: List<String> = emptyList()) {
        model.prepareCareLocal("propose_game_profile", JSONObject().put("action", action).put("kind", selectedKind)
            .put("name", if (action == "save") profileName else "").put("profile_id", id).put("mod_ids", JSONArray(selectedMods))
            .put("reason", if (action == "save") "Spara detta läge som en namngiven profil för spelet." else "Återgå till det valda sparade läget för detta spel."))
        close()
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(horizontal = 16.dp)) {
            Text("Spelverktyg", style = MaterialTheme.typography.titleLarge)
            Text(state.gameTitle, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Kontroll före start", "Återställningsprofiler", "Kontroller", "Modprofiler").forEach { label ->
                    FilterChip(selected = tab == label, onClick = { tab = label }, label = { Text(label) }, modifier = Modifier.testTag("care-tab-$label"))
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall)
                if (inGame) Text("Undersök här medan spelet körs. Stäng spelet innan profiler sparas eller tillämpas.", style = MaterialTheme.typography.bodySmall)
                when (tab) {
                    "Kontroll före start" -> {
                        Text("Lokal kontroll utan AI-anrop. Kontrollerar kända fel; en godkänd kontroll garanterar inte att spelet fungerar.")
                        val findings = remember(state.preflight) { JSONObject(state.preflight).optJSONArray("findings")?.objects().orEmpty() }
                        findings.forEach { finding ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(finding.getString("title"), style = MaterialTheme.typography.titleSmall)
                                Text(finding.getString("detail"), style = MaterialTheme.typography.bodySmall)
                                Text(finding.getString("next"), style = MaterialTheme.typography.bodyMedium)
                            } }
                        }
                        OutlinedButton(onClick = model::loadCare, enabled = !state.busy) { Text("Kontrollera igen") }
                        Button(onClick = { close(); model.askCare("Granska kontrollen före start, min tillgängliga logg och eventuella modkonflikter. Hjälp mig lösa det tydligaste problemet, ett steg i taget.", true) }, enabled = !state.busy) { Text("Be Codex felsöka starten") }
                        OutlinedButton(onClick = { close(); model.askCare("Undersök varför spelet hackar utifrån bildtider, belastning, temperatur och mina jämförbara mätningar. Skilj observation från misstänkt orsak och föreslå ett riktat försök.") }, enabled = !state.busy) { Text("Be Codex undersöka hackandet") }
                    }
                    "Återställningsprofiler" -> {
                        Text("Spara ett läge när du har provat att spelet fungerar. Grafik- och runtimeval, kontroller och modval ingår. Spelfiler, sparfiler och installerade runtimepaket kopieras inte.")
                        OutlinedTextField(name, { name = it.take(80) }, label = { Text("Namn, till exempel Fungerande kontroll") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("care-profile-name"), enabled = !state.busy)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(kind == "game", { kind = "game" }, label = { Text("Hela spelprofilen") })
                            FilterChip(kind == "controls", { kind = "controls" }, label = { Text("Bara kontroller") })
                        }
                        Button(onClick = { proposal("save", kind) }, enabled = name.isNotBlank() && !state.busy && !inGame, modifier = Modifier.testTag("care-save-profile")) { Text("Granska och spara") }
                        ProfileRows(profiles.filter { it.getString("kind") != "mods" }, !state.busy && !inGame,
                            { proposal("restore", it.getString("kind"), it.getString("profile_id")) }, { delete = it })
                    }
                    "Kontroller" -> {
                        if (control.optJSONArray("library")?.length() == 0) Text(control.optString("note"))
                        Text("Kontrollbiblioteket kopieras till det här spelet. Du kan även be Codex ändra en knapp och spara resultatet som en egen kontrollprofil.")
                        Button(onClick = { close(); model.askCare("Läs min kontrollprofil, spelarplats och den tillgängliga signalvägen. Förklara om kontrollerna skickar gamepad- eller tangentinput och hjälp mig välja rätt profil för detta spel.") }, enabled = !state.busy) { Text("Undersök kontroll och spelarplats") }
                        control.optJSONArray("library")?.objects().orEmpty().forEach { profile ->
                            OutlinedButton(onClick = {
                                model.prepareCareLocal("propose_control_profile", JSONObject().put("profile_id", profile.getString("profile_id")).put("reason", "Använd ${profile.getString("name")} i detta spel.")); close()
                            }, enabled = !state.busy && !inGame, modifier = Modifier.fillMaxWidth()) {
                                Text("${profile.getString("name")} · ${profile.getInt("physicalBindings")} mappningar · ${profile.getInt("onScreenControls")} skärmknappar")
                            }
                        }
                        Text("Exempel i chatten: Byt A till B i det här spelets kontrollprofil. Sticks och Home-knappen bevaras. Spelarplats ändras i GameNatives vanliga Controller-meny.", style = MaterialTheme.typography.bodySmall)
                    }
                    "Modprofiler" -> {
                        Text("Välj moddar och spara exempelvis Original, Grafik eller Gameplay. Listan byggs av granskade filplaceringar. Senare val i listan får högre filprioritet.")
                        OutlinedTextField(name, { name = it.take(80) }, label = { Text("Profilnamn") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                        val chosen = enabledMods ?: mods.filter { it.getBoolean("enabled") }.sortedBy { it.getInt("priority") }.map { it.getString("mod_id") }
                        mods.forEach { mod ->
                            val id = mod.getString("mod_id")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(id in chosen, { yes -> enabledMods = if (yes) chosen + id else chosen - id }, enabled = !state.busy && mod.getInt("trackedFiles") > 0)
                                Text("${mod.getString("name")} ${if (id in chosen) "· prioritet ${chosen.indexOf(id) + 1}" else ""}", Modifier.weight(1f))
                            }
                        }
                        TextButton(onClick = { enabledMods = emptyList(); name = "Original" }, enabled = !state.busy) { Text("Välj Original (inga moddar)") }
                        Button(onClick = { proposal("save", "mods", selectedMods = chosen) }, enabled = name.isNotBlank() && !state.busy && !inGame) { Text("Granska modprofil") }
                        val conflicts = inventory.optJSONObject("mods")?.optJSONArray("conflicts")?.objects().orEmpty()
                        Text("${conflicts.size} delade filsökvägar", style = MaterialTheme.typography.titleMedium)
                        conflicts.forEach { c -> Text("${c.getString("path")}\n${c.getJSONArray("mods").let { a -> (0 until a.length()).joinToString { a.getString(it) } }}\nAktiv vinnare: ${if (c.isNull("activeWinner")) "ingen" else c.getString("activeWinner")}", style = MaterialTheme.typography.bodySmall) }
                        ProfileRows(profiles.filter { it.getString("kind") == "mods" }, !state.busy && !inGame,
                            { proposal("restore", "mods", it.getString("profile_id")) }, { delete = it })
                        Text("Delade filer visar möjlig konflikt, inte att två moddar är inkompatibla. Plugin-laddordning och avancerade FOMOD-val hanteras i Modbibliotek och Nexus.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = close) { Text("Tillbaka till chatten") }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    delete?.let { selected -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Ta bort ${selected.getString("name") }?") },
        text = { Text("Tar bort det sparade profilvalet. Aktiva spelinställningar och spel-/modfiler ändras inte.") },
        confirmButton = { TextButton(onClick = { model.deleteCare(selected.getString("profile_id")); delete = null }) { Text("Ta bort") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Avbryt") } }) }
}

@Composable
private fun ProfileRows(profiles: List<JSONObject>, enabled: Boolean, restore: (JSONObject) -> Unit, delete: (JSONObject) -> Unit) {
    if (profiles.isEmpty()) Text("Ingen sparad profil av denna typ ännu.", style = MaterialTheme.typography.bodySmall)
    profiles.forEach { profile -> Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(profile.getString("name"), style = MaterialTheme.typography.titleMedium)
            Text(profile.getString("scope"), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { restore(profile) }, enabled = enabled, modifier = Modifier.testTag("care-restore-${profile.getString("profile_id")}")) { Text("Granska återställning") }
                TextButton(onClick = { delete(profile) }, enabled = enabled) { Text("Ta bort") }
            }
        }
    } }
}

@Composable
internal fun GameCareReview(model: GameAssistantViewModel, state: AssistantUiState, inGame: Boolean) {
    state.careProposal?.let { preview ->
        var modsApproved by remember(preview) { mutableStateOf(false) }
        Card(Modifier.widthIn(max = 800.dp).fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(preview.title, style = MaterialTheme.typography.titleMedium)
            Text(preview.reason)
            Text(preview.changes.joinToString("\n"), Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
            if (preview.changesMods) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(modsApproved, { modsApproved = it }, modifier = Modifier.testTag("care-mod-approval"), enabled = !state.busy)
                Text("Jag har granskat modval och filprioritet. Vid tillämpning kan moddens DLL/laddarfiler köras nästa spelstart.", style = MaterialTheme.typography.bodySmall)
            }
            if (inGame) Text("Stäng spelet före denna profilåtgärd.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.applyCare(modsApproved) }, enabled = !state.busy && !inGame && (!preview.changesMods || modsApproved), modifier = Modifier.testTag("care-apply")) { Text("Tillämpa granskad profil") }
                TextButton(onClick = model::dismissProposal, enabled = !state.busy) { Text("Avstå") }
            }
        } }
    }
    if (state.careUndo) Card(Modifier.widthIn(max = 800.dp).fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text("Återställningspunkt för profilbytet", style = MaterialTheme.typography.titleMedium)
        Text("Tidigare inställningar, kontrollmappning och berörda modval kan återställas. Säkerhetskopian finns kvar efter omstart.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = model::restoreCare, enabled = !inGame && !state.busy, modifier = Modifier.testTag("care-undo")) { Text("Ångra profil") }
            TextButton(onClick = model::keepCare, enabled = !inGame && !state.busy) { Text("Behåll profil") }
        }
    } }
}
