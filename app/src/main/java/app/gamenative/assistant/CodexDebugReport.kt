package app.gamenative.assistant

import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class DebugProblem(val label: String) {
    CRASH("Krasch / startfel"), PERFORMANCE("Hack / FPS"), INPUT("Kontroll"), OTHER("Annat");
}

/** Private, bounded, atomic reports. Neither this store nor its reader performs network requests. */
class CodexDebugReportStore(private val root: File, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val MAX_BYTES = 1_500_000
        const val MAX_LOG_CHARS = 160_000
        const val MAX_LINES = 1000
        const val MAX_SAMPLES = 600
        private val lock = Any()
        private val gamePattern = Regex("[A-Za-z0-9_-]{1,160}")
        private val idPattern = Regex("[a-f0-9-]{36}")

        fun safe(value: Any?): Any = when (value) {
            is JSONObject -> JSONObject().apply { value.keys().forEach { put(it, safe(value.get(it))) } }
            is JSONArray -> JSONArray((0 until value.length()).map { safe(value.get(it)) })
            is String -> DiagnosticRedactor.text(value)
            null -> JSONObject.NULL
            else -> value
        }

        fun configuration(raw: JSONObject): JSONObject = JSONObject().apply {
            val allowed = setOf("screenSize", "graphicsDriver", "graphicsDriverVersion", "dxwrapper", "dxwrapperConfig",
                "containerVariant", "wineVersion", "emulator", "box64Version", "box64Preset", "fexcoreVersion", "fexcorePreset",
                "inputType", "dinputMapperType", "sdlControllerAPI", "disableMouseInput", "audioDriver", "displayRendererMode", "rendererPresentMode")
            allowed.forEach { key -> if (raw.has(key)) put(key, safe(raw.get(key).toString().take(4000))) }
            GameSettingCatalog.settings.forEach { put(it.id, safe(it.current(raw))) }
            val extras = raw.optJSONObject("extraData") ?: JSONObject()
            listOf("lsfgEnabled", "appliedWineVersion", "appliedContainerVariant", "lastInstalledMainWrapper").forEach {
                if (extras.has(it)) put(it, safe(extras.get(it).toString().take(300)))
            }
        }

        fun summary(report: JSONObject): JSONObject = JSONObject(report.toString()).apply {
            remove("lines"); remove("samples"); remove("controller")
            put("logLines", report.optJSONArray("lines")?.length() ?: 0)
            put("performanceSamples", report.optJSONArray("samples")?.length() ?: 0)
            put("controllerAvailable", report.optJSONObject("controller")?.optBoolean("available", true) == true)
            put("evidenceNote", "One recorded launch. Saved launch settings are historical, not permission to overwrite current settings. Empty/truncated output is not proof of no fault. Overlapping render windows, diagnostic overhead and the open assistant can affect FPS; this is not a controlled benchmark. Controller writes do not prove receipt by the game.")
        }

        /** No model-supplied file paths/IDs. All reads use the frozen, explicitly selected report. */
        fun query(report: JSONObject, args: JSONObject): String {
            val keys = setOf("section", "query", "from_ms", "to_ms", "offset")
            require(args.keys().asSequence().toSet() == keys) { "Invalid report arguments" }
            require(args.get("section") is String && args.get("query") is String)
            listOf("from_ms", "to_ms", "offset").forEach { require(args.get(it) is Int || args.get(it) is Long) }
            val section = args.getString("section")
            val search = args.getString("query")
            val from = args.getLong("from_ms"); val to = args.getLong("to_ms"); val cursor = args.getLong("offset")
            require(search.length <= 120 && from >= 0 && (to == 0L || to >= from) && cursor in 0..MAX_LINES.toLong())
            val offset = cursor.toInt()
            val result = JSONObject().put("reportId", report.getString("id")).put("game", report.getString("game"))
                .put("launchId", report.getString("launchId")).put("section", section)
            if (section == "controller") return result.put("controller", report.opt("controller") ?: JSONObject.NULL)
                .put("note", "Recorded observation for this launch, not current input. No guest receipt acknowledgement.").toString()
            require(section in setOf("log", "performance"))
            val rows = report.optJSONArray(if (section == "log") "lines" else "samples") ?: JSONArray()
            val start = report.getLong("startedAtMs")
            val matches = (0 until rows.length()).map { rows.getJSONObject(it) }.filter {
                val elapsed = (it.optLong("timestampMs", start) - start).coerceAtLeast(0)
                elapsed >= from && (to == 0L || elapsed <= to) && (search.isEmpty() || it.toString().contains(search, ignoreCase = true))
            }
            val selected = JSONArray(); var chars = 0
            for (row in matches.drop(offset).take(60)) {
                val length = row.toString().length
                if (chars + length > 18_000) break
                selected.put(row); chars += length
            }
            return result.put("rows", selected).put("matchingRows", matches.size)
                .put("nextOffset", if (offset + selected.length() < matches.size) offset + selected.length() else JSONObject.NULL)
                .put("omittedLines", report.optInt("omittedLines"))
                .put("timeNote", "from_ms/to_ms are milliseconds since startedAtMs; to_ms=0 means no upper limit. Log timestamps are app receipt times. Samples overlap and may be historical/paused/chat-visible. Literal substring search, no regex or shell.").toString()
        }
    }

    private fun file(game: String, id: String): File {
        require(gamePattern.matches(game) && idPattern.matches(id)) { "Invalid report identity" }
        val parent = File(root, game)
        val file = File(parent, "$id.json")
        require(parent.canonicalFile == File(root.canonicalFile, game) && file.canonicalFile == File(parent.canonicalFile, "$id.json"))
        listOf(".bak", ".new").forEach { suffix ->
            require(File(parent, "$id.json$suffix").canonicalFile == File(parent.canonicalFile, "$id.json$suffix"))
        }
        return file
    }

    fun create(game: String, launch: String, startedAt: Long, problem: DebugProblem, setup: JSONObject, source: String): JSONObject = synchronized(lock) {
        val report = JSONObject().put("schema", 1).put("id", UUID.randomUUID().toString()).put("game", game)
            .put("launchId", launch).put("startedAtMs", startedAt).put("captureStartedAtMs", now())
            .put("updatedAtMs", now()).put("state", "recording").put("problem", problem.name).put("source", source)
            .put("setup", safe(setup)).put("lines", JSONArray()).put("samples", JSONArray()).put("markers", JSONArray())
            .put("omittedLines", 0)
        save(report); prune(); report
    }

    fun save(report: JSONObject) = synchronized(lock) {
        val output = file(report.getString("game"), report.getString("id"))
        val bytes = report.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Rapportens storleksgräns nåddes; tidigare kopia finns kvar." }
        output.parentFile!!.mkdirs()
        val atomic = AtomicFile(output)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    fun read(game: String, id: String): JSONObject = synchronized(lock) {
        val atomic = AtomicFile(file(game, id))
        val bytes = atomic.openRead().use { it.readBytesBounded(MAX_BYTES) }
        JSONObject(bytes.toString(Charsets.UTF_8)).also {
            require(it.getInt("schema") == 1 && it.getString("game") == game && it.getString("id") == id) { "Report identity mismatch" }
        }
    }

    fun list(game: String): List<JSONObject> = synchronized(lock) {
        require(gamePattern.matches(game))
        File(root, game).listFiles().orEmpty().map { it.name.removeSuffix(".bak").removeSuffix(".json") }.distinct()
            .filter { idPattern.matches(it) }.mapNotNull { runCatching { read(game, it) }.getOrNull() }
            .sortedByDescending { it.optLong("captureStartedAtMs") }.map(::summary)
    }

    fun recover(activeLaunch: String?) = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.isDirectory && gamePattern.matches(it.name) }.forEach { game ->
            list(game.name).filter { it.optString("state") == "recording" && it.optString("launchId") != activeLaunch }.forEach {
                val report = read(game.name, it.getString("id"))
                report.put("state", "interrupted").put("endedAtMs", report.optLong("updatedAtMs"))
                    .put("endReason", "Insamlingen avbröts. Detta är senaste sparade delen; slutorsaken är okänd.")
                save(report)
            }
        }
    }

    fun delete(game: String, id: String) = synchronized(lock) {
        check(read(game, id).optString("state") != "recording") { "Avsluta insamlingen först." }
        AtomicFile(file(game, id)).delete()
    }

    private fun prune() {
        val all = root.listFiles().orEmpty().filter { it.isDirectory && gamePattern.matches(it.name) }
            .flatMap { list(it.name) }.sortedByDescending { it.optLong("captureStartedAtMs") }
        val counts = mutableMapOf<String, Int>()
        all.forEachIndexed { index, report ->
            val game = report.getString("game"); val n = counts.getOrDefault(game, 0) + 1; counts[game] = n
            if ((n > 10 || index >= 40) && report.optString("state") != "recording") delete(game, report.getString("id"))
        }
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (n < 0) break
        output.write(buffer, 0, n)
    }
    require(output.size() <= limit) { "Report exceeds size limit" }
    return output.toByteArray()
}
