package app.gamenative.assistant

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gamenative.BuildConfig
import app.gamenative.data.ModInstallStatus
import app.gamenative.mods.ModContainerResolver
import app.gamenative.mods.ModOwnershipStore
import app.gamenative.mods.NexusModManager
import app.gamenative.utils.ContainerUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Fast local gate for the normal library Play button. Does not consume subscription usage. */
internal suspend fun preflightBeforePlay(context: android.content.Context, game: String): GamePreflight.Report = withContext(Dispatchers.IO) {
    // First launch creates its container in the existing start flow; absence is not a failure.
    val config = if (ContainerUtils.hasContainer(context, game)) JSONObject(ContainerUtils.getContainer(context, game).configFile.readText()) else JSONObject()
    val root = GameFileRoots.discover(context, game).firstOrNull { it.id == "game" }?.directory
    // No old log is interpreted as a current blocker on every launch; chat can inspect historical logs explicitly.
    val report = GamePreflight.inspect(config, root, File(ModContainerResolver.getWinePrefix(context, game)), "")
    val findings = report.findings.toMutableList()
    val cache = NexusModManager.cacheRoot(context, game)
    var checked = 0
    val all = NexusModManager.dao(context).getInstallsForApp(game)
    all.filter { it.status == ModInstallStatus.APPLIED.name }.take(150).forEach { mod ->
        ModOwnershipStore.read(cache, mod.installId)?.files?.filter { it.active }?.take((3000 - checked).coerceAtLeast(0))?.forEach { file ->
            checked++
            if (root != null && File(file.targetPath).canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) && !File(file.targetPath).isFile && findings.size < 30)
                findings += GamePreflight.Finding("warning", "En spårad modfil saknas", DiagnosticRedactor.text("${mod.modName}: ${file.targetRelativePath}"), "Öppna Spelverktyg eller Modbibliotek och granska installationen.")
        }
    }
    val undo = File(context.noBackupFilesDir, "assistant/profiles/$game/undo.json")
    if (undo.isFile && undo.length() < 12_000_000 && !JSONObject(undo.readText()).optBoolean("committed"))
        findings += GamePreflight.Finding("warning", "En profiländring är ofullständig", "Återställningspunkten är bevarad.", "Öppna assistenten och välj Ångra profil före nya ändringar.")
    GamePreflight.Report(findings)
}

@Composable
internal fun rememberPreflightStart(start: (String, Boolean) -> Unit): (String, String, Boolean) -> Unit {
    data class Request(val game: String, val title: String, val boot: Boolean)
    val context = LocalContext.current
    val currentStart by rememberUpdatedState(start)
    var request by remember { mutableStateOf<Request?>(null) }
    var report by remember { mutableStateOf<GamePreflight.Report?>(null) }
    val selected = request
    LaunchedEffect(selected) {
        if (selected == null) return@LaunchedEffect
        report = null
        val result = try { preflightBeforePlay(context, selected.game) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { GamePreflight.Report(listOf(GamePreflight.Finding("warning", "Startkontrollen kunde inte slutföras", "Inga inställningar ändrades.", "Du kan granska spelet med Codex eller fortsätta med den vanliga starten."))) }
        if (result.findings.none { it.severity in setOf("warning", "error") }) {
            request = null; currentStart(selected.game, selected.boot)
        } else report = result
    }
    if (selected != null) AlertDialog(onDismissRequest = { request = null }, title = { Text("Kontroll före start") },
        text = {
            if (report == null) Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Kontrollerar ${selected.title} lokalt…") }
            else Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                report!!.findings.filter { it.severity in setOf("warning", "error") }.forEach { finding ->
                    Text(finding.title, style = MaterialTheme.typography.titleSmall)
                    Text("${finding.detail}\n${finding.next}")
                }
            }
        }, confirmButton = { TextButton(onClick = { request = null; currentStart(selected.game, selected.boot) }, enabled = report != null) { Text("Starta ändå") } },
        dismissButton = { TextButton(onClick = {
            request = null
            context.startActivity(Intent(context, GameAssistantActivity::class.java).putExtra("app_id", selected.game).putExtra("game_title", selected.title))
        }) { Text("Öppna Codex") } })
    return { game, title, boot ->
        if (!BuildConfig.AI_ASSISTANT_ENABLED) currentStart(game, boot) else { report = null; request = Request(game, title, boot) }
    }
}
