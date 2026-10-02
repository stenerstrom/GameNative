package app.gamenative.assistant

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** One outstanding undo per game. Backup is durable before the config is replaced. */
class ConfigTransaction(private val config: File, private val backup: File) {
    fun hasBackup(): Boolean = backup.exists()

    fun preview(expectedHash: String, proposal: ConfigProposal): List<String> = synchronized(lock) {
        val bytes = config.readBytes()
        check(AssistantProtocol.sha256(bytes) == expectedHash) { "Configuration changed. Read it again." }
        val current = JSONObject(bytes.toString(Charsets.UTF_8))
        val changes = proposal.patch().filter { (key, next) -> value(current, key)?.toString() != next.toString() }
        check(changes.isNotEmpty()) { "These settings are already active" }
        changes.map { (path, next) ->
            val setting = GameSettingCatalog.settings.first { it.path == path }
            val target = setting.choices.firstOrNull { setting.decode(it).toString() == next.toString() } ?: next.toString()
            "${setting.label}: ${setting.display(setting.current(current))} → ${setting.display(target)}"
        }
    }

    /** Explicitly accepted by the user, never called by the model. */
    fun keep() = synchronized(lock) {
        val record = JSONObject(backup.readText())
        check(record.getInt("schema") == 1)
        val current = JSONObject(config.readText())
        val after = record.getJSONObject("after")
        val keys = record.getJSONArray("keys")
        for (i in 0 until keys.length()) {
            val key = keys.getString(i)
            require(key in allowedKeys)
            check(value(current, key) == value(after, key)) { "Configuration changed elsewhere. The undo backup is retained." }
        }
        check(backup.delete()) { "Could not clear the undo record" }
    }

    fun apply(expectedHash: String, proposal: ConfigProposal) = synchronized(lock) {
        check(!backup.exists()) { "Restore the previous experiment first" }
        val original = config.readBytes()
        check(AssistantProtocol.sha256(original) == expectedHash) { "Configuration changed. Read diagnostics and analyze again." }
        val before = JSONObject(original.toString(Charsets.UTF_8))
        val after = JSONObject(before.toString())
        val changes = proposal.patch().filter { (key, next) -> value(before, key)?.toString() != next.toString() }
        check(changes.isNotEmpty()) { "These settings are already active" }
        changes.forEach { (key, value) -> set(after, key, value) }
        val record = JSONObject().put("schema", 1).put("before", before).put("after", after)
            .put("keys", org.json.JSONArray(changes.keys.toList()))
        atomicWrite(backup, record.toString().toByteArray())
        atomicWrite(config, after.toString().toByteArray())
    }

    fun restore() = synchronized(lock) {
        val record = JSONObject(backup.readText())
        check(record.getInt("schema") == 1) { "Unsupported backup" }
        val current = JSONObject(config.readText())
        val before = record.getJSONObject("before")
        val after = record.getJSONObject("after")
        val keys = record.getJSONArray("keys")
        for (i in 0 until keys.length()) {
            val key = keys.getString(i)
            require(key in allowedKeys) { "Invalid backup field" }
            check(value(current, key) == value(after, key) || value(current, key) == value(before, key)) {
                "An affected setting was changed elsewhere. Restore it manually or undo that edit first; the backup is retained."
            }
        }
        for (i in 0 until keys.length()) {
            val key = keys.getString(i)
            set(current, key, value(before, key))
        }
        // Preserve the original absence/null of extraData when our patch was its only content.
        if (current.optJSONObject("extraData")?.length() == 0 && before.optJSONObject("extraData") == null) {
            if (before.has("extraData")) current.put("extraData", JSONObject.NULL) else current.remove("extraData")
        }
        atomicWrite(config, current.toString().toByteArray())
        check(backup.delete()) { "Settings restored; undo record could not be removed. Retrying restore is safe." }
    }

    companion object {
        private val lock = Any()
        private val allowedKeys = GameSettingCatalog.paths
        private fun value(json: JSONObject, key: String): Any? = if (key.startsWith("extraData.")) {
            json.optJSONObject("extraData")?.opt(key.substringAfter('.'))
        } else json.opt(key)
        private fun set(json: JSONObject, key: String, value: Any?) {
            if (key.startsWith("extraData.")) {
                val extra = json.optJSONObject("extraData") ?: JSONObject().also { json.put("extraData", it) }
                extra.put(key.substringAfter('.'), value)
            } else json.put(key, value)
        }
        fun atomicWrite(file: File, bytes: ByteArray) {
            val parent = requireNotNull(file.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create storage directory" }
            val temp = File.createTempFile(".assistant-", ".tmp", parent)
            try {
                FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        }
    }
}
