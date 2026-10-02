package app.gamenative.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

interface ConversationStore {
    data class Saved(val history: List<AssistantProtocol.ChatTurn> = emptyList(), val gameAccess: Boolean = false)
    fun load(game: String, account: String): Saved
    fun save(game: String, account: String, saved: Saved)
}

/** Per-game, per-connection transcript. No raw logs, tool output, pending approvals or OAuth tokens. */
class AssistantConversations(context: Context) : ConversationStore {
    private val root = File(context.noBackupFilesDir, "assistant/conversations")
    private val alias = "gamenative.assistant.conversations.v1"
    private fun file(game: String, account: String) = File(root, AssistantProtocol.sha256("$account\u0000$game".toByteArray()) + ".enc")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    override fun load(game: String, account: String): ConversationStore.Saved {
        val file = file(game, account)
        if (!file.isFile) return ConversationStore.Saved()
        require(file.length() in 29..1_100_000) { "Invalid saved conversation" }
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        val turns = json.getJSONArray("turns")
        val history = ((turns.length() - AssistantProtocol.HISTORY_TURNS).coerceAtLeast(0) until turns.length()).map {
            val turn = turns.getJSONObject(it)
            AssistantProtocol.ChatTurn(DiagnosticRedactor.text(turn.getString("user")).take(4000),
                DiagnosticRedactor.text(turn.getString("assistant")).take(AssistantProtocol.HISTORY_REPLY_CHARS),
                DiagnosticRedactor.text(turn.optString("displayText", turn.getString("assistant"))).take(AssistantProtocol.HISTORY_REPLY_CHARS))
        }
        return ConversationStore.Saved(history, json.optBoolean("gameAccess", false))
    }
    override fun save(game: String, account: String, saved: ConversationStore.Saved) {
        val turns = JSONArray()
        saved.history.takeLast(AssistantProtocol.HISTORY_TURNS).forEach { turns.put(JSONObject()
            .put("user", DiagnosticRedactor.text(it.user).take(4000))
            .put("assistant", DiagnosticRedactor.text(it.assistant).take(AssistantProtocol.HISTORY_REPLY_CHARS))
            .put("displayText", DiagnosticRedactor.text(it.displayText).take(AssistantProtocol.HISTORY_REPLY_CHARS))) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val json = JSONObject().put("turns", turns).put("gameAccess", saved.gameAccess)
        ConfigTransaction.atomicWrite(file(game, account), cipher.iv + cipher.doFinal(json.toString().toByteArray()))
    }
}
