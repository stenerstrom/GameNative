package app.gamenative.assistant

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.gamenative.BuildConfig
import app.gamenative.updates.AiDevUpdateActivity
import app.gamenative.mods.LocalModSourceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(model: GameAssistantViewModel, onClose: () -> Unit, openBrowser: (String) -> Unit, inGame: Boolean = false) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val selected = state.accounts.accounts.firstOrNull { it.id == state.accounts.selected }
    var menu by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(false) }
    var modLibrary by remember { mutableStateOf(false) }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.importMod(LocalModSourceType.ARCHIVE, listOf(it)) } }
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) model.importMod(LocalModSourceType.FILES, uris) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { model.importMod(LocalModSourceType.FOLDER, listOf(it)) } }
    var careSheet by rememberSaveable { mutableStateOf(false) }
    var imagePreview by remember { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var keepConfirmation by remember { mutableStateOf(false) }
    var newChatConfirmation by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    LaunchedEffect(state.history.size, state.busy, state.proposal, state.fileProposal, state.modProposal, state.offlineProposal, state.careProposal, state.restoreRequested) {
        withFrameNanos { }
        scroll.animateScrollToItem((scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, if (inGame) "Tillbaka till spelet" else "Tillbaka") }
                Column(Modifier.weight(1f)) {
                    Text(state.gameTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text("${if (inGame) "Codex i spelet" else "Spelassistent"} · ${state.models.firstOrNull { it.slug == state.selectedModel }?.name ?: "ChatGPT"}",
                        style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Alternativ") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Konto och modell") }, onClick = { menu = false; sheet = "account" })
                        DropdownMenuItem(text = { Text("Spelåtkomst och verktyg") }, onClick = { menu = false; sheet = "access" })
                        DropdownMenuItem(text = { Text("Modbibliotek och Nexus") }, enabled = !state.busy && !inGame, onClick = { menu = false; modLibrary = true })
                        DropdownMenuItem(text = { Text("Installera offlinespel med Codex") }, enabled = !state.busy && !inGame, onClick = {
                            menu = false; context.startActivity(Intent(context, OfflineGameImportActivity::class.java))
                        })
                        DropdownMenuItem(text = { Text("Ny chatt") }, enabled = !state.busy, onClick = { menu = false; newChatConfirmation = true })
                        DropdownMenuItem(text = { Text("Appuppdateringar") }, enabled = !state.busy && !inGame, onClick = {
                            menu = false; context.startActivity(Intent(context, AiDevUpdateActivity::class.java))
                        })
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { careSheet = true }, enabled = !state.busy, modifier = Modifier.testTag("care-open")) { Text("Spelverktyg") }
                GameOptimizationButton(model, inGame)
                if (inGame) TextButton(onClick = model::captureScreenshot, enabled = !state.busy, modifier = Modifier.testTag("screenshot-capture")) { Text("Bifoga spelbild") }
            }
            if (inGame) LiveSessionBanner(state.game, model)
            LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.history.isEmpty()) item {
                    Column(Modifier.widthIn(max = 800.dp).fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Vad vill du få ordning på?", style = MaterialTheme.typography.headlineSmall)
                        Text(if (inGame) "Beskriv vad som händer just nu. Jag kan läsa mätvärden och tillgänglig logg medan spelet körs. Stäng panelen för att fortsätta spela." else "Beskriv problemet. Jag kan undersöka spelet och förbereda ändringar som du godkänner här.")
                        listOf("Hjälp mig optimera prestandan", "Min handkontroll fungerar inte", "Spelet startar inte", "Granska mina moddar").forEach { prompt ->
                            OutlinedButton(onClick = { model.prompt(prompt) }, enabled = !state.busy) { Text(prompt) }
                        }
                    }
                }
                state.history.forEach { turn ->
                    item {
                        Row(Modifier.widthIn(max = 800.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.widthIn(max = 620.dp)) {
                                SelectionContainer { Text(turn.user, Modifier.padding(14.dp)) }
                            }
                        }
                    }
                    item { Column(Modifier.widthIn(max = 800.dp).fillMaxWidth()) { AssistantText(turn.displayText) } }
                }
                if (state.busy) item {
                    Column(Modifier.widthIn(max = 800.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.prompt.isNotBlank()) Text(state.prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.activities.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(state.status, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = model::cancel) { Text("Stoppa") }
                        }
                    }
                }
                if (!state.busy && state.status.isNotBlank()) item {
                    Text(state.status, Modifier.widthIn(max = 800.dp).fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                }
                if (state.game.startsWith("CUSTOM_GAME_") && !inGame) item {
                    Column(Modifier.widthIn(max = 800.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = model::offlineHelp, enabled = !state.busy, modifier = Modifier.testTag("offline-help")) { Text("Hjälp med installation och startfil") }
                        Text("Låter Codex undersöka detta lokala spels EXE-filer och föreslå nästa steg. Kräver ChatGPT-anslutning.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.offlineProposal != null || state.offlineUndo) item { OfflineGameCard(model, state, inGame) }
                if (inGame && (state.proposal != null || state.fileProposal != null || state.modProposal != null || state.backup)) item {
                    Text("Permanenta inställnings-, fil- och modändringar kräver stoppat spel. Stödda försök i kontrollbryggan kan provas live nedan.", style = MaterialTheme.typography.bodySmall)
                }
                state.proposal?.let { proposal -> item {
                    Card(Modifier.widthIn(max = 800.dp).fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Föreslagna ändringar", style = MaterialTheme.typography.titleMedium)
                            (state.proposalChanges.ifEmpty { proposal.changes() }).forEach { Text(it) }
                            AssistantText(proposal.reason)
                            if (inGame) LiveControllerProposal(model, proposal)
                            Text(if (inGame) "För att spara inställningar till framtida spelstarter: stäng spelet och granska förslaget från biblioteket. Liveförsöket ovan sparar inget."
                                else "Stäng spelet först. En säkerhetskopia skapas innan inställningarna sparas. Testa vid nästa spelstart.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = model::apply, enabled = !inGame && !state.busy && !state.backup) { Text("Tillämpa") }
                                TextButton(onClick = model::dismissProposal, enabled = !state.busy) { Text("Avstå") }
                            }
                        }
                    }
                } }
                state.fileProposal?.let { proposal -> item {
                    Card(Modifier.widthIn(max = 800.dp).fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Föreslagen filändring", style = MaterialTheme.typography.titleMedium)
                            Text(proposal.path, style = MaterialTheme.typography.labelLarge)
                            AssistantText(proposal.reason)
                            Text("− tas bort · + läggs till", style = MaterialTheme.typography.bodySmall)
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                                SelectionContainer {
                                    Text(proposal.diff, Modifier.fillMaxWidth().heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState()).padding(12.dp), fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text("Stäng spelet först. Originalfilen säkerhetskopieras innan ändringen sparas. Testa vid nästa spelstart.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = model::applyFile, enabled = !inGame && !state.busy && !state.backup) { Text("Tillämpa filändring") }
                                TextButton(onClick = model::dismissProposal, enabled = !state.busy) { Text("Avstå") }
                            }
                        }
                    }
                } }
                state.modProposal?.let { proposal -> item {
                    var loaderApproved by remember(proposal) { mutableStateOf(false) }
                    Card(Modifier.widthIn(max = 800.dp).fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(proposal.title, style = MaterialTheme.typography.titleMedium)
                            AssistantText(proposal.reason)
                            Text("${proposal.fileCount} filer · original säkerhetskopieras vid ersättning", style = MaterialTheme.typography.bodySmall)
                            Text(proposal.files.joinToString("\n"), Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            proposal.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (proposal.needsLoaderApproval) Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = loaderApproved, onCheckedChange = { loaderApproved = it }, enabled = !state.busy,
                                    modifier = Modifier.testTag("mod-loader-approval"))
                                Text("Jag godkänner paketets DLL/laddarfiler. De kan köras när spelet startar.", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Stäng spelet först. Ångra ändring finns kvar efter omstart. Spelets kompatibilitet är inte verifierad.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { model.applyMod(loaderApproved) }, enabled = !inGame && !state.busy && !state.backup && (!proposal.needsLoaderApproval || loaderApproved)) { Text("Tillämpa modändring") }
                                TextButton(onClick = model::dismissProposal, enabled = !state.busy) { Text("Avstå") }
                            }
                        }
                    }
                } }
                if (state.careProposal != null || state.careUndo) item { GameCareReview(model, state, inGame) }
                if (state.backup && !state.careUndo) item {
                    Card(Modifier.widthIn(max = 800.dp).fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (state.restoreRequested) "Återställ senaste ändringen?" else if (inGame) "Sparad ändring med säkerhetskopia" else "Senaste ändringen kan ångras", style = MaterialTheme.typography.titleMedium)
                            Text(if (inGame) "Den här återställningen kräver stoppat spel. Du kan ändå göra ett separat liveförsök i kontrollbryggan."
                                else "Testa i spelet. Ångra om det blir sämre, eller behåll ändringen innan nästa försök.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = model::restore, enabled = !state.busy && !inGame) { Text("Ångra ändring") }
                                TextButton(onClick = { keepConfirmation = true }, enabled = !state.busy && !inGame) { Text("Behåll") }
                            }
                        }
                    }
                }
            }
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (selected?.planEnabled != true) {
                        Button(onClick = { model.connect(false, openBrowser) }, enabled = !state.busy) { Text("Continue with ChatGPT") }
                        Text("Använder ditt ChatGPT-abonnemang.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(Modifier.widthIn(max = 800.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { sheet = "access" }, enabled = !state.busy) {
                                Text(if (state.includeDiagnostics && state.modAccess) "Spel- och modverktyg på" else if (state.includeDiagnostics && state.fileAccess) "Spel- och filåtkomst på" else if (state.includeDiagnostics) "Spelåtkomst på" else "Ge spelåtkomst")
                            }
                            if (!state.includeDiagnostics) Text("eller chatta utan verktyg", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    state.screenshot?.let { shot ->
                        Row(Modifier.widthIn(max = 800.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Image(shot.bitmap.asImageBitmap(), "Förhandsvisa bifogad spelbild", Modifier.size(100.dp, 65.dp).clickable { imagePreview = true })
                            Text("Skickas med nästa meddelande. Granska att inga privata uppgifter syns; bilder filtreras inte automatiskt.", Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = model::removeScreenshot, enabled = !state.busy) { Text("Ta bort") }
                        }
                    }
                    Row(Modifier.widthIn(max = 800.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!inGame) Box(Modifier.padding(bottom = 4.dp)) {
                            IconButton(onClick = { if (state.includeDiagnostics && state.modAccess) attachments = true else sheet = "access" }, enabled = !state.busy) {
                                Icon(Icons.Default.Add, "Lägg till mod")
                            }
                            DropdownMenu(expanded = attachments, onDismissRequest = { attachments = false }) {
                                DropdownMenuItem(text = { Text("Modarkiv (ZIP, 7z, RAR)") }, onClick = { attachments = false; archivePicker.launch(arrayOf("*/*")) })
                                DropdownMenuItem(text = { Text("Modfiler (DLL och andra filer)") }, onClick = { attachments = false; filesPicker.launch(arrayOf("*/*")) })
                                DropdownMenuItem(text = { Text("Modmapp") }, onClick = { attachments = false; folderPicker.launch(null) })
                                DropdownMenuItem(text = { Text("Modbibliotek och Nexus") }, onClick = { attachments = false; modLibrary = true })
                            }
                        }
                        OutlinedTextField(value = state.prompt, onValueChange = model::prompt, enabled = !state.busy,
                            modifier = Modifier.weight(1f), placeholder = { Text("Skriv ett meddelande…") }, maxLines = 4,
                            shape = MaterialTheme.shapes.large)
                        FilledIconButton(onClick = model::send, enabled = state.sendBlockReason == null, modifier = Modifier.padding(bottom = 4.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Skicka")
                        }
                    }
                    if (selected?.planEnabled == true && state.selectedModel.isBlank() && !state.busy) {
                        TextButton(onClick = { sheet = "account" }) { Text("Välj eller hämta modell") }
                    }
                }
            }
        }
    }
    if (careSheet) GameCareSheet(model, inGame) { careSheet = false }
    if (imagePreview) state.screenshot?.let { shot ->
        AlertDialog(onDismissRequest = { imagePreview = false }, title = { Text("Spelbild före sändning") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(shot.bitmap.asImageBitmap(), "Förhandsvisad spelbild", Modifier.fillMaxWidth().heightIn(max = 360.dp))
                Text("Bara denna bild skickas när du trycker Skicka i chatten. Ta bort bilden om privata uppgifter syns.")
            }
        }, confirmButton = { TextButton(onClick = { imagePreview = false }) { Text("Stäng förhandsvisning") } },
            dismissButton = { TextButton(onClick = { model.removeScreenshot(); imagePreview = false }, enabled = !state.busy) { Text("Ta bort bilden") } })
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (sheet == "account") {
                Text("Konto och modell", style = MaterialTheme.typography.titleLarge)
                Text("GameNative AI Dev ${BuildConfig.VERSION_NAME} · ChatGPT-abonnemang", style = MaterialTheme.typography.bodySmall)
                state.accounts.accounts.forEach { account ->
                    TextButton(onClick = { model.select(account.id) }, enabled = !state.busy) { Text("${if (account.id == selected?.id) "✓ " else ""}${account.label}") }
                }
                if (selected?.planEnabled != true) Button(onClick = { model.connect(false, openBrowser) }, enabled = !state.busy) { Text("Continue with ChatGPT") }
                TextButton(onClick = { model.connect(true, openBrowser) }, enabled = !state.busy) { Text("Lägg till konto") }
                state.models.forEach { available ->
                    TextButton(onClick = { model.model(available.slug) }, enabled = !state.busy) { Text("${if (state.selectedModel == available.slug) "✓ " else ""}${available.name}") }
                }
                Text("Modellerna hämtas från OpenAI för denna anslutning.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = model::refreshModels, enabled = !state.busy && selected?.planEnabled == true) { Text("Uppdatera modellistan") }
                TextButton(onClick = { openBrowser(ChatGptProvider.USAGE_URL) }) { Text("Hantera användning och appåtkomst") }
                if (selected?.connected == true) TextButton(onClick = model::disconnect, enabled = !state.busy) { Text("Logga ut") }
                Text("Om inloggningsfliken ligger kvar efter godkännande: använd Androids Tillbaka och kontrollera anslutningen här.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Spelåtkomst", style = MaterialTheme.typography.titleLarge)
                Text("Låt assistenten själv läsa detta spels inställningar, tillgängliga logg och prestandadata (även från pågående spelomgång) samt upptäckta handkontroller när den behöver dem. Informationen filtreras och skickas till OpenAI med din fråga.")
                Text("Alla ändringar visas för godkännande. Ingen debug run behövs för att börja. Hemlighetsfiltreringen kan inte hitta varje känslig detalj.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tillåt spelverktyg", Modifier.weight(1f))
                    Switch(checked = state.includeDiagnostics, onCheckedChange = model::attachDiagnostics, enabled = !state.busy,
                        modifier = Modifier.testTag("game-access"))
                }
                Text("Kan ändra ${GameSettingCatalog.settings.size} spelinställningar", style = MaterialTheme.typography.titleMedium)
                Text("Kontroller och touch, FPS-gräns och upplösning, ljud, Box64-profil, skärmläge och pausinställningar.")
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tillåt spelfiler", Modifier.weight(1f))
                    Switch(checked = state.fileAccess, onCheckedChange = model::allowFiles, enabled = !state.busy && state.includeDiagnostics,
                        modifier = Modifier.testTag("file-access"))
                }
                Text("Läser och föreslår ändringar i befintliga INI-, CFG-, CONF-, JSON-, XML-, TOML- och PROPERTIES-filer i spelets mapp och privata Wine-användarmappar. Filinnehåll filtreras och skickas till OpenAI. Varje ändring visas med diff och kräver Tillämpa; originalfilen kan återställas.", style = MaterialTheme.typography.bodySmall)
                Text("Textredigering: högst 128 KiB/48 000 tecken per fil. Modpaket och DLL:er hanteras via modverktygen nedan. Godtycklig körning av skript/EXE ingår inte.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tillåt modhantering", Modifier.weight(1f))
                    Switch(checked = state.modAccess, onCheckedChange = model::allowMods, enabled = !state.busy && state.includeDiagnostics,
                        modifier = Modifier.testTag("mod-access"))
                }
                Text("Läser modbiblioteket, paketens filnamn och valda instruktioner till OpenAI. Agenten kan föreslå installation, återaktivering och inaktivering med filgranskning och ångra. Lägg till arkiv, filer eller mappar med + vid skrivfältet. Nexus, FOMOD och gemensam laddordning finns under ⋮ → Modbibliotek och Nexus.", style = MaterialTheme.typography.bodySmall)
                Text("De senaste åtta utbytena sparas krypterat per spel och konto på denna enhet. Ny chatt rensar dem. Råloggar och väntande ändringar sparas inte i chatthistoriken, men svar kan innehålla uppgifter från tidigare diagnostik.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { sheet = null }, modifier = Modifier.fillMaxWidth()) { Text("Klart") }
        }
    }
    if (modLibrary) AssistantModLibrary(state.game, state.gameTitle) { modLibrary = false; model.modLibraryClosed() }
    if (keepConfirmation) AlertDialog(onDismissRequest = { keepConfirmation = false }, title = { Text("Behåll ändringarna?") },
        text = { Text("Den senaste återställningspunkten tas bort. Därefter kan assistenten förbereda ett nytt försök med en ny säkerhetskopia.") },
        confirmButton = { TextButton(onClick = { keepConfirmation = false; model.keepChanges() }) { Text("Behåll") } },
        dismissButton = { TextButton(onClick = { keepConfirmation = false }) { Text("Avbryt") } })
    if (newChatConfirmation) AlertDialog(onDismissRequest = { newChatConfirmation = false }, title = { Text("Starta ny chatt?") },
        text = { Text("Den sparade konversationen för detta spel och konto rensas. Spelinställningar och återställningspunkten behålls.") },
        confirmButton = { TextButton(onClick = { newChatConfirmation = false; model.clearChat() }) { Text("Ny chatt") } },
        dismissButton = { TextButton(onClick = { newChatConfirmation = false }) { Text("Avbryt") } })
}

@Composable
internal fun AssistantText(text: String) {
    // Safe inline formatting: no HTML/WebView and no automatic execution or opening model links.
    val formatted = remember(text) { buildAnnotatedString {
        val pattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
        var cursor = 0
        pattern.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            if (match.groups[1] != null) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
            else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[2]) }
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    } }
    SelectionContainer { Text(formatted, style = MaterialTheme.typography.bodyLarge) }
}
