package app.gamenative.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Local observations only. A missing DLL in a log is evidence; a name alone is not a diagnosis. */
object GamePreflight {
    data class Finding(val severity: String, val title: String, val detail: String, val next: String)
    data class Report(val findings: List<Finding>, val checkedAt: Long = System.currentTimeMillis()) {
        fun json() = JSONObject().put("checkedAtMs", checkedAt).put("findings", JSONArray(findings.map {
            JSONObject().put("severity", it.severity).put("title", it.title).put("detail", it.detail).put("next", it.next)
        })).put("limits", "Local inspection, not a game launch or proof of compatibility. Logs may be historical. Runtime presence is not verified by filenames. Only known configuration contradictions and bounded local files are inspected.")
    }

    fun inspect(config: JSONObject, root: File?, prefix: File?, log: String): Report {
        val findings = mutableListOf<Finding>()
        fun add(severity: String, title: String, detail: String, next: String) {
            findings += Finding(severity, title, DiagnosticRedactor.text(detail).take(1000), next)
        }
        if (root?.isDirectory != true) add("warning", "Spelmappen saknas", "Appen kunde inte hitta en tillgänglig spelmapp.", "Kontrollera installationen eller importera hela spelmappen igen.")
        val launch = config.optString("executablePath")
        if (launch.isNotBlank()) {
            val executable = resolveExecutable(launch, root, prefix)
            when {
                executable == null -> add("unknown", "Startfilen kunde inte kontrolleras", "Startvalet ligger utanför de mappar denna kontroll kan läsa.", "Kontrollera startfilen i spelinställningarna.")
                !executable.isFile -> add("error", "Startfilen saknas", launch.replace('\\', '/').substringAfterLast('/'), "Be Codex hitta och välja spelets EXE.")
                OfflineGameTools.peArchitecture(executable) == null -> add("warning", "Startfilen är inte en stödd Windows-EXE", executable.name, "Välj spelets x86- eller x64-EXE.")
                else -> add("ok", "Startfilen finns", "${executable.name} · ${OfflineGameTools.peArchitecture(executable)}", "Spelstart behöver fortfarande provas.")
            }
        } else add("unknown", "Startfilen väljs vid spelstart", "Ingen egen EXE är sparad i konfigurationen.", "Butikens vanliga startval används; detta är inte ett konstaterat fel.")
        val extra = config.optJSONObject("extraData") ?: JSONObject()
        val target = extra.optString("fpsLimiterTarget").toDoubleOrNull()
        if (extra.optString("fpsLimiterEnabled").toBoolean() && (target == null || target <= 0 || !target.isFinite()))
            add("warning", "Ogiltig FPS-begränsare", "Begränsaren är på men målet saknas eller är ogiltigt.", "Välj ett giltigt FPS-mål och granska ändringen.")
        val missing = missingLibraries(log)
        missing.forEach { dll ->
            add("warning", "Loggen rapporterar en saknad DLL", dll, runtimeAdvice(dll))
        }
        if (missing.isEmpty()) add("unknown", "Inga kända DLL-fel i tillgänglig logg", "En tyst eller äldre logg kan inte bekräfta att alla komponenter finns.", "Vid en felruta: bifoga en spelbild eller granska loggen från samma start.")
        if (root?.isDirectory == true) {
            val files = root.walkTopDown().maxDepth(3).onEnter { !Files.isSymbolicLink(it.toPath()) }.take(4000).filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.toList()
            val runtimes = files.filter { Regex("(?i)^(vc_?redist.*|dxsetup)\\.exe$").matches(it.name) }
            if (runtimes.isNotEmpty()) add("info", "Lokala komponentinstallerare finns", runtimes.take(20).joinToString { it.relativeTo(root).invariantSeparatorsPath },
                "Codex kan granska en medföljande installerare i ett lokalt spel. Filnamnet bevisar inte version eller kompatibilitet. Windows-guiden slutförs av dig.")
            val gaps = missingParts(files.map { it.relativeTo(root).invariantSeparatorsPath })
            if (gaps.isNotEmpty()) add("warning", "En installationsserie har luckor", gaps.joinToString(), "Kopiera hela installationsmappen igen. Appen kan inte veta om den sista delen saknas utan ett manifest.")
            if (root.usableSpace in 1..1_000_000_000) add("warning", "Lite ledigt lagringsutrymme", "Mindre än 1 GB är ledigt där spelet ligger.", "Frigör utrymme före installation eller stora modändringar.")
        }
        return Report(findings)
    }

    internal fun resolveExecutable(path: String, root: File?, prefix: File?): File? {
        val normalized = path.replace('\\', '/')
        val base = when {
            normalized.startsWith("C:/", true) -> prefix?.let { File(it, "drive_c") }
            normalized.startsWith("A:/", true) -> root
            ':' !in normalized && !normalized.startsWith('/') -> root
            else -> null
        } ?: return null
        val relative = if (normalized.length > 2 && normalized[1] == ':') normalized.drop(3) else normalized
        if (relative.split('/').any { it == ".." || it == "." }) return null
        val result = File(base, relative)
        if (!result.canonicalFile.toPath().startsWith(base.canonicalFile.toPath())) return null
        var part: File? = result
        while (part != null && part != base) { if (Files.isSymbolicLink(part.toPath())) return null; part = part.parentFile }
        return result
    }
    internal fun missingLibraries(log: String): List<String> = log.takeLast(24_000).lineSequence()
        .filter { Regex("(?i)(not found|failed to load|cannot (?:find|load)|c0000135|saknas)").containsMatchIn(it) }
        .flatMap { Regex("(?i)\\b[a-z0-9_-]{1,80}\\.dll\\b").findAll(it).map { m -> m.value.lowercase() } }.distinct().take(20).toList()
    internal fun runtimeAdvice(dll: String): String = when {
        Regex("(?i)(msvc[pr]|vcruntime|concrt|ucrtbase)").containsMatchIn(dll) -> "Undersök spelets medföljande Visual C++-installerare och rätt x86/x64-variant. Byt inte slumpmässigt DLL-filer."
        Regex("(?i)(d3dx|d3dcompiler|xinput1_[1-3]|xaudio2_7|xactengine)").containsMatchIn(dll) -> "Undersök spelets medföljande DirectX-komponenter. DXVK löser inte automatiskt en saknad legacy-DLL."
        else -> "Kontrollera spelets installerade filer och dokumentation; DLL-namnet räcker inte för att välja en installerare."
    }
    internal fun missingParts(paths: List<String>): List<String> = paths.mapNotNull { path ->
        Regex("(?i)^(.+?)[-_.](\\d{1,3})\\.bin$").matchEntire(path)?.let { it.groupValues[1] to it.groupValues[2].toInt() }
    }.groupBy({ it.first }, { it.second }).flatMap { (name, numbers) ->
        if (numbers.size < 2) emptyList() else (1..numbers.max()).filter { it !in numbers }.take(20).map { "$name: del $it" }
    }.take(20)
}
