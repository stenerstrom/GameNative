package app.gamenative.assistant

import app.gamenative.PluviaApp
import app.gamenative.service.SteamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Runtime-only experiments. No container writes, so existing durable undo is independent. */
class ControllerLiveTransactions(private val connection: (String) -> Port?) {
    data class Options(val api: ControllerInputApi, val mapper: DirectInputMapper) {
        fun describe() = "${api.label} · ${mapper.label}"
    }
    interface Port {
        val token: String
        fun read(): Options
        fun write(options: Options)
        fun disconnect()
        fun reconnect()
    }
    data class Preview(val game: String, val token: String, val before: Options, val after: Options)
    data class Undo(val game: String, val token: String, val before: Options, val after: Options)
    private val mutable = MutableStateFlow<Undo?>(null)
    val undo = mutable.asStateFlow()
    private var reconnecting: String? = null

    fun preview(game: String, proposal: ConfigProposal): Preview? {
        val patch = proposal.patch()
        if (patch.isEmpty() || patch.keys.any { it !in setOf("inputType", "dinputMapperType") }) return null
        val port = connection(game) ?: return null
        val before = port.read()
        val after = Options(
            patch["inputType"]?.let { value -> ControllerInputApi.entries.single { it.nativeApi.ordinal == value } } ?: before.api,
            patch["dinputMapperType"]?.let { value -> DirectInputMapper.entries.single { it.storedValue == value } } ?: before.mapper,
        )
        return Preview(game, port.token, before, after).takeIf { before != after }
    }

    fun apply(preview: Preview) {
        check(reconnecting == null) { "Vänta tills kontrollbryggan återanslutits." }
        val port = requirePort(preview.game, preview.token)
        check(port.read() == preview.before) { "Bryggan ändrades. Granska det aktuella läget igen." }
        val previous = mutable.value?.takeIf { it.game == preview.game && it.token == preview.token }
        check(previous == null || previous.after == preview.before) { "Bryggan ändrades utanför assistenten. Det tidigare ångraläget behålls." }
        writeVerified(port, preview.before, preview.after)
        mutable.value = Undo(preview.game, preview.token, previous?.before ?: preview.before, preview.after)
    }

    fun restore(game: String) {
        check(reconnecting == null) { "Vänta tills kontrollbryggan återanslutits." }
        val record = requireNotNull(mutable.value?.takeIf { it.game == game }) { "Inget liveförsök att ångra." }
        val port = requirePort(game, record.token)
        check(port.read() == record.after) { "Bryggan ändrades utanför assistenten. Ångra skriver inte över den ändringen." }
        writeVerified(port, record.after, record.before)
        mutable.value = null
    }

    private fun writeVerified(port: Port, before: Options, after: Options) {
        try {
            port.write(after)
            check(port.read() == after) { "Bryggan bekräftade inte det nya läget." }
        } catch (failure: Exception) {
            try { port.write(before) } catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    suspend fun reconnect(game: String, pause: suspend () -> Unit = { delay(350) }) {
        check(reconnecting == null) { "Återanslutning pågår redan." }
        val port = requireNotNull(connection(game)) { "Ingen aktiv kontrollbrygga för spelet." }
        reconnecting = port.token
        try {
            port.disconnect()
            pause()
        } finally {
            // Always lift the temporary gate, including cancellation; never touch a new launch.
            try {
                withContext(NonCancellable) {
                    connection(game)?.takeIf { it.token == port.token }?.reconnect()
                }
            } finally { if (reconnecting == port.token) reconnecting = null }
        }
    }

    fun clear() { mutable.value = null }
    private fun requirePort(game: String, token: String): Port = requireNotNull(connection(game)?.takeIf { it.token == token }) {
        "Spelomgången har avslutats eller bytts. Ett nytt försök krävs."
    }
}

/** All bridge operations run on Android's main thread, as the existing controller handlers do. */
object LiveControllerChanges {
    private fun port(game: String): ControllerLiveTransactions.Port? {
        if (!SteamService.keepAlive) return null
        val live = LiveGameSession.view(game) ?: return null
        val handler = PluviaApp.xServerView?.getxServer()?.winHandler ?: return null
        val status = handler.assistantControllerStatus
        if (handler.assistantSessionToken != live.token || !status.optBoolean("handlerRunning")) return null
        return object : ControllerLiveTransactions.Port {
            override val token = live.token
            override fun read(): ControllerLiveTransactions.Options {
                val current = handler.assistantControllerStatus
                return ControllerLiveTransactions.Options(ControllerInputApi.valueOf(current.getString("inputApi")),
                    DirectInputMapper.entries.single { it.storedValue == current.getInt("directInputMapper") })
            }
            override fun write(options: ControllerLiveTransactions.Options) {
                handler.setPreferredInputApi(options.api.nativeApi)
                handler.setDInputMapperType(options.mapper.storedValue.toByte())
            }
            override fun disconnect() = handler.beginAssistantControllerReconnect()
            override fun reconnect() = handler.finishAssistantControllerReconnect()
        }
    }
    internal val transactions = ControllerLiveTransactions(::port)
    val undo = transactions.undo
    fun preview(game: String, proposal: ConfigProposal) = runCatching { transactions.preview(game, proposal) }.getOrNull()
    suspend fun apply(preview: ControllerLiveTransactions.Preview) = withContext(Dispatchers.Main.immediate) { transactions.apply(preview) }
    suspend fun restore(game: String) = withContext(Dispatchers.Main.immediate) { transactions.restore(game) }
    suspend fun reconnect(game: String) = withContext(Dispatchers.Main.immediate) { transactions.reconnect(game) }
    fun clear() = transactions.clear()
    fun status(game: String): JSONObject {
        val active = port(game)
        val trial = undo.value?.takeIf { it.game == game && it.token == active?.token }
        return JSONObject().put("available", active != null)
            .put("runtimeOnly", true).put("undoAvailable", trial != null)
            .put("current", active?.read()?.describe() ?: JSONObject.NULL)
            .put("undoTarget", trial?.before?.describe() ?: JSONObject.NULL)
            .put("scope", "Prova bryggan live / Ångra liveförsök affect only API selection and mapper for subsequent legacy controller discovery. Återanslut kontrollbryggan requests virtual hotplug. Neither action changes saved settings, SDL startup environment, loaded drivers or cached PC-game objects. No guest acknowledgement or guaranteed in-game effect. Runtime trials expire at game exit.")
    }
}
