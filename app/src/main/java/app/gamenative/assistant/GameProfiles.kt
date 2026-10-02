package app.gamenative.assistant

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CarePreview(val token: String, val title: String, val reason: String, val changes: List<String>, val changesMods: Boolean = false)

/** Named snapshots and a separate durable undo; payloads never come from model-written JSON. */
class GameProfiles(private val directory: File, private val game: String, private val backend: Backend) {
    interface Backend {
        suspend fun capture(): JSONObject
        suspend fun validate(target: JSONObject)
        suspend fun write(target: JSONObject)
        suspend fun guard(shape: JSONObject): JSONObject = JSONObject()
        fun checkStopped()
    }
    private data class Pending(val preview: CarePreview, val before: JSONObject, val after: JSONObject, val saveKind: String? = null, val name: String = "", val guardShape: JSONObject = JSONObject(), val beforeGuard: JSONObject = JSONObject())
    private var pending: Pending? = null
    private val profiles get() = File(directory, "profiles.json")
    private val undo get() = File(directory, "undo.json")
    init { require(game.matches(Regex("[A-Za-z0-9_-]{1,160}"))) }
    fun beginTurn() { pending = null }
    fun hasUndo() = undo.isFile
    fun inventory(): JSONArray = JSONArray(records().map { r -> JSONObject().put("profile_id", r.getString("id"))
        .put("name", r.getString("name")).put("kind", r.getString("kind")).put("createdAtMs", r.getLong("createdAtMs"))
        .put("appVersion", r.optString("appVersion")).put("scope", scope(r.getJSONObject("payload"))) })

    suspend fun prepareSave(kind: String, name: String, payload: JSONObject, reason: String): CarePreview = lock.withLock {
        backend.checkStopped()
        require(kind in setOf("game", "controls", "mods") && name.isNotBlank() && name.length <= 80)
        val clean = DiagnosticRedactor.text(name).trim()
        val all = records()
        check(all.size < 20) { "Högst 20 profiler per spel. Ta bort en gammal profil först." }
        check(all.none { it.getString("name").equals(clean, true) }) { "Namnet används redan. Välj ett annat." }
        val before = subset(backend.capture(), payload)
        preview("Spara $clean", reason, listOf(scope(payload), "Sparar ett namngivet läge; aktiva inställningar ändras inte."), false, before, payload, kind, clean)
    }

    suspend fun prepareRestore(id: String, reason: String): CarePreview {
        val record = records().singleOrNull { it.getString("id") == id } ?: error("Profilen tillhör inte detta spel.")
        return prepareChange("Återställ ${record.getString("name")}", reason, record.getJSONObject("payload"))
    }
    suspend fun prepareChange(title: String, reason: String, after: JSONObject): CarePreview = lock.withLock {
        backend.checkStopped()
        check(!hasUndo()) { "Ångra eller behåll den tidigare profiländringen först." }
        backend.validate(after)
        val before = subset(backend.capture(), after)
        check(!same(before, after)) { "Detta läge är redan aktivt." }
        val changesMods = after.has("mods") && !same(before.opt("mods"), after.opt("mods"))
        val shape = if (changesMods) JSONObject().put("mods", after.getJSONObject("mods")) else JSONObject()
        val guard = backend.guard(shape)
        preview(title, reason, differences(before, after), changesMods, before, after).also {
            pending = pending!!.copy(guardShape = shape, beforeGuard = guard)
        }
    }
    private fun preview(title: String, reason: String, changes: List<String>, mods: Boolean, before: JSONObject, after: JSONObject, kind: String? = null, name: String = ""): CarePreview {
        require(reason.isNotBlank() && reason.length <= 2000)
        val preview = CarePreview(UUID.randomUUID().toString(), title, DiagnosticRedactor.text(reason), changes, mods)
        pending = Pending(preview, copy(before), copy(after), kind, name)
        return preview
    }
    suspend fun apply(preview: CarePreview, version: String): String = lock.withLock {
        backend.checkStopped()
        val p = requireNotNull(pending).also { check(it.preview == preview) { "Granska ett nytt förslag." } }
        check(same(subset(backend.capture(), p.before), p.before)) { "Läget ändrades efter granskningen. Läs om och försök igen." }
        if (p.saveKind != null) {
            val all = records()
            check(all.size < 20 && all.none { it.getString("name").equals(p.name, true) })
            val record = JSONObject().put("id", UUID.randomUUID().toString()).put("name", p.name).put("kind", p.saveKind)
                .put("createdAtMs", System.currentTimeMillis()).put("appVersion", version).put("payload", p.after)
            save(profiles, envelope().put("profiles", JSONArray(all + record)))
            pending = null
            return@withLock "Profilen ${p.name} har sparats lokalt för detta spel. Den är ett användarsparat läge, inte ett automatiskt prestandatest."
        }
        check(!hasUndo()) { "En återställningspunkt finns redan." }
        backend.validate(p.after)
        check(same(subset(backend.capture(), p.before), p.before)) { "Läget ändrades under kontrollen. Granska ett nytt förslag." }
        check(same(backend.guard(p.guardShape), p.beforeGuard)) { "Berörda modfiler ändrades efter granskningen. Läs om och granska igen." }
        val record = envelope().put("before", p.before).put("after", p.after).put("committed", false)
            .put("guardShape", p.guardShape).put("beforeGuard", p.beforeGuard)
        // From the durable journal through completion there is no coroutine cancellation gap.
        withContext(NonCancellable) {
            save(undo, record)
            backend.checkStopped()
            backend.write(p.after)
            check(same(subset(backend.capture(), p.after), p.after)) { "Alla profiländringar kunde inte verifieras. Återställningspunkten finns kvar." }
            save(undo, record.put("afterGuard", backend.guard(p.guardShape)).put("committed", true))
        }
        pending = null
        "${p.preview.title} har tillämpats. Ångra profil finns kvar efter appomstart. Prova spelet innan du bedömer resultatet."
    }

    suspend fun restore() = lock.withLock {
        backend.checkStopped()
        val record = read(undo)
        val before = record.getJSONObject("before"); val after = record.getJSONObject("after")
        val current = subset(backend.capture(), before)
        check(compatiblePartial(current, before, after)) { "Berörda inställningar ändrades utanför profilverktyget. Återställningspunkten behålls." }
        val guardShape = record.optJSONObject("guardShape") ?: JSONObject()
        val expectedGuard = record.optJSONObject("afterGuard") ?: record.optJSONObject("beforeGuard") ?: JSONObject()
        check(same(backend.guard(guardShape), expectedGuard)) { "Berörda modfiler ändrades eller modväxlingen avbröts. Återställningspunkten bevaras; granska native-återställningen i Modbibliotek och Nexus." }
        backend.validate(before)
        withContext(NonCancellable) {
            backend.write(before)
            check(same(subset(backend.capture(), before), before)) { "Återställningen kunde inte verifieras; säkerhetskopian behålls." }
            check(same(backend.guard(guardShape), record.optJSONObject("beforeGuard") ?: JSONObject())) { "Modfilerna kunde inte återställas exakt. Säkerhetskopian behålls." }
            check(undo.delete())
        }
        pending = null
    }
    suspend fun keep() = lock.withLock {
        backend.checkStopped()
        val record = read(undo)
        check(record.getBoolean("committed") && same(subset(backend.capture(), record.getJSONObject("after")), record.getJSONObject("after"))) { "Ändringen är ofullständig eller har ändrats. Återställningspunkten behålls." }
        check(same(backend.guard(record.optJSONObject("guardShape") ?: JSONObject()), record.optJSONObject("afterGuard") ?: JSONObject())) { "Modfilerna ändrades efter profilbytet. Återställningspunkten behålls." }
        check(undo.delete())
    }
    suspend fun delete(id: String) = lock.withLock {
        backend.checkStopped()
        val all = records()
        check(all.any { it.getString("id") == id })
        save(profiles, envelope().put("profiles", JSONArray(all.filterNot { it.getString("id") == id })))
        pending = null
    }
    private fun records(): List<JSONObject> {
        if (!profiles.exists()) return emptyList()
        val array = read(profiles).getJSONArray("profiles")
        require(array.length() <= 20)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun envelope() = JSONObject().put("schema", 1).put("game", game)
    private fun read(file: File): JSONObject {
        require(file.length() in 1..12_000_000) { "Ogiltig profilfil; den har bevarats." }
        return JSONObject(file.readText()).also { require(it.getInt("schema") == 1 && it.getString("game") == game) }
    }
    private fun save(file: File, json: JSONObject) {
        val bytes = json.toString().toByteArray()
        require(bytes.size <= 12_000_000) { "Profilerna är för stora." }
        ConfigTransaction.atomicWrite(file, bytes)
    }
    companion object {
        private val lock = Mutex()
        fun copy(json: JSONObject) = JSONObject(json.toString())
        fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { "${JSONObject.quote(it)}:${canonical(value.get(it))}" }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            null, JSONObject.NULL -> "null"
            else -> value.toString()
        }
        fun same(a: Any?, b: Any?) = canonical(a) == canonical(b)
        fun subset(current: JSONObject, shape: JSONObject): JSONObject = JSONObject().apply {
            shape.keys().forEach { key ->
                val value = shape.opt(key)
                put(key, if (value is JSONObject && current.opt(key) is JSONObject) subset(current.getJSONObject(key), value) else current.opt(key) ?: JSONObject.NULL)
            }
        }
        private fun compatiblePartial(current: Any?, before: Any?, after: Any?): Boolean {
            if (same(current, before) || same(current, after)) return true
            if (current is JSONObject && before is JSONObject && after is JSONObject)
                return before.keys().asSequence().all { compatiblePartial(current.opt(it), before.opt(it), after.opt(it)) }
            return false
        }
        private fun scope(payload: JSONObject) = buildList {
            if (payload.has("settings")) add("${payload.getJSONObject("settings").length()} inställningar")
            if (payload.has("controls")) add("kontrollmappning och skärmkontroller")
            if (payload.has("mods")) add("modval och filordning")
        }.joinToString(" · ") + ". Inga sparfiler, spelinstallationer eller runtime-paket kopieras."
        private fun differences(before: JSONObject, after: JSONObject): List<String> = buildList {
            after.optJSONObject("settings")?.let { settings -> settings.keys().forEach { key ->
                if (!same(before.optJSONObject("settings")?.opt(key), settings.opt(key))) {
                    val label = GameSettingCatalog.settings.firstOrNull { it.path == key }?.label ?: key.substringAfter('.')
                    add("$label: ${displaySetting(key, before.getJSONObject("settings").opt(key))} → ${displaySetting(key, settings.opt(key))}")
                }
            } }
            if (after.has("controls") && !same(before.opt("controls"), after.opt("controls"))) add("Kontrollprofilen ersätts för endast detta spel. Andra spels och bibliotekets profiler lämnas kvar.")
            if (after.has("mods") && !same(before.opt("mods"), after.opt("mods"))) {
                val mods = after.getJSONObject("mods")
                mods.keys().forEach { id -> val m = mods.getJSONObject(id)
                    add("${DiagnosticRedactor.text(m.optString("name")).take(120)}: ${if (m.optBoolean("enabled")) "På · prioritet ${m.optInt("priority")}" else "Av"}")
                }
                add("Modfiler byts enligt tidigare granskade placeringar. DLL-filer kan köras vid spelstart.")
            }
        }
        private fun displaySetting(key: String, value: Any?): String {
            val raw = if (value is JSONObject && value.has("present")) {
                if (value.optBoolean("present")) value.opt("value").toString() else "Standardvärde"
            } else value.toString()
            val setting = GameSettingCatalog.settings.firstOrNull { it.path == key }
            val choice = setting?.choices?.firstOrNull { setting.decode(it).toString() == raw }
            return DiagnosticRedactor.text(if (choice != null) setting.display(choice) else raw).take(120)
        }
    }
}
