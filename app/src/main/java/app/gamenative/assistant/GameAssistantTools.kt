package app.gamenative.assistant

import android.content.Context
import android.os.Build
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.DebugReportUtils
import app.gamenative.utils.DiagnosticsLog
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

/** The game ID is fixed by the Android screen, never supplied by the model. */
class GameAssistantTools(private val context: Context, private val appId: String) {
    init { require(appId.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "Invalid game ID" } }
    data class Snapshot(val hash: String, val text: String)

    fun readDiagnostics(): Snapshot {
        val container = ContainerUtils.getContainer(context, appId)
        val bytes = container.configFile.readBytes()
        val config = JSONObject(bytes.toString(Charsets.UTF_8))
        val safeConfig = JSONObject()
        listOf("screenSize", "graphicsDriver", "graphicsDriverVersion", "dxwrapper", "dxwrapperConfig", "displayRendererMode",
            "rendererPresentMode", "containerVariant", "wineVersion", "emulator", "box64Version", "box64Preset", "fexcoreVersion").forEach {
            if (config.has(it)) safeConfig.put(it, DiagnosticRedactor.text(config.get(it).toString()).take(1000))
        }
        val extras = config.optJSONObject("extraData")
        listOf("fpsLimiterEnabled", "fpsLimiterTarget", "lsfgEnabled").forEach {
            if (extras?.has(it) == true) safeConfig.put(it, extras.get(it).toString().take(30))
        }
        val report = DebugReportUtils.reportsDir(context).listFiles()?.filter { it.isDirectory && it.name.startsWith("${appId}_") }
            ?.sortedByDescending { it.lastModified() }?.firstOrNull {
                runCatching { JSONObject(readSmall(File(it, "header.json"), 2_000_000)).optString("appId") == appId }.getOrDefault(false)
            }
        val candidates = listOfNotNull(DebugReportUtils.wineLogFile(context, appId), DiagnosticsLog.file(context, appId),
            report?.let { DebugReportUtils.logFile(it) }).filter { it.isFile && it.length() > 0 }
        val log = candidates.maxByOrNull { it.lastModified() }
        val logText = if (log == null) "No game-specific log available. Chat and configuration analysis are still available. An optional AI debug run can collect a log if needed." else runCatching {
            val raw = if (log.extension == "gz") GZIPInputStream(log.inputStream()).use { readBounded(it, 8 * 1024 * 1024 + 1024) }
                else readSmall(log, 8 * 1024 * 1024 + 1024)
            // Sanitize complete bounded input before taking the tail; never cut a credential before filtering it.
            DiagnosticRedactor.text(raw).takeLast(24_000)
        }.getOrElse { "Log unavailable or exceeds the 8 MiB safety limit. Capture a shorter run." }
        val result = JSONObject().put("game", appId).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE).put("configuration", safeConfig)
            .put("logModifiedAtMs", log?.lastModified() ?: JSONObject.NULL).put("log", logText)
        val session = config.optJSONObject("sessionMetadata")
        result.put("lastSessionAverageFps", session?.optString("avg_fps")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        result.put("lastSessionSeconds", session?.optString("session_length_sec")?.toIntOrNull() ?: JSONObject.NULL)
        result.put("measurementNote", "Last-session metadata and older logs may come from different runs/settings. These are not a before/after benchmark.")
        result.put("performance", report?.let { readPerformance(it) } ?: "No matching saved debug performance report")
        return Snapshot(AssistantProtocol.sha256(bytes), result.toString(2))
    }

    private val backupFile: File get() = File(context.noBackupFilesDir, "assistant/undo/$appId.json")
    fun hasBackup(): Boolean = backupFile.isFile
    fun applyValidated(proposal: ConfigProposal, snapshotHash: String) {
        checkStopped()
        transaction().apply(snapshotHash, proposal)
    }
    fun restore() {
        checkStopped()
        transaction().restore()
    }
    private fun checkStopped() {
        check(!SteamService.keepAlive) { "Stop the game/container before changing or restoring settings" }
    }
    private fun transaction(): ConfigTransaction = ConfigTransaction(ContainerUtils.getContainer(context, appId).configFile,
        backupFile)

    private fun readPerformance(report: File): Any = runCatching {
        val source = JSONObject(readSmall(DebugReportUtils.perfFile(report), 2_000_000))
        val safe = JSONObject().put("reportModifiedAtMs", report.lastModified())
        listOf("intervalMs", "runLengthSec", "refreshRateHz", "totalMemMb").forEach { key ->
            (source.opt(key) as? Number)?.let { safe.put(key, it) }
        }
        val samples = source.optJSONArray("samples") ?: JSONArray()
        val selected = JSONArray()
        for (i in (samples.length() - 20).coerceAtLeast(0) until samples.length()) {
            val sample = samples.getJSONObject(i)
            val fields = JSONObject()
            listOf("t", "fps", "cpu", "iow", "gpu", "gfreq", "tc", "tb", "th", "hr").forEach { key ->
                (sample.opt(key) as? Number)?.let { fields.put(key, it) }
            }
            sample.optJSONArray("ft")?.let { times ->
                if (times.length() == 3 && (0..2).all { times.opt(it) is Number }) fields.put("frameTimeP50P99MaxMs", times)
            }
            selected.put(fields)
        }
        safe.put("last20Samples", selected)
    }.getOrElse { "Performance report unavailable or too large" }

    private fun readSmall(file: File, limit: Int): String {
        require(file.length() <= limit) { "File too large" }
        return file.inputStream().use { readBounded(it, limit) }
    }
    private fun readBounded(input: InputStream, limit: Int): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit + 1) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= limit) { "File too large" }
        return bytes.toString(Charsets.UTF_8)
    }
}
