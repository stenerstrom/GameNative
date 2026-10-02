package app.gamenative.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ControllerLiveTransactionsTest {
    private class Port(override val token: String = "launch") : ControllerLiveTransactions.Port {
        var value = ControllerLiveTransactions.Options(ControllerInputApi.DINPUT, DirectInputMapper.STANDARD)
        var disconnected = false
        var reconnects = 0
        var failNext = false
        override fun read() = value
        override fun write(options: ControllerLiveTransactions.Options) {
            value = options
            if (failNext) { failNext = false; error("Partial write failure") }
        }
        override fun disconnect() { disconnected = true }
        override fun reconnect() { disconnected = false; reconnects++ }
    }
    private var port: Port? = Port()
    private val trials = ControllerLiveTransactions { game -> port.takeIf { game == "STEAM_42" } }
    private fun proposal(api: ControllerInputApi) = ConfigProposal(null, null, "Test", inputApi = api)
    private fun preview(api: ControllerInputApi) = requireNotNull(trials.preview("STEAM_42", proposal(api)))

    @Test fun repeatedTrialsKeepOriginalRuntimeUndoAndNeverRequireStopping() {
        val original = port!!.value
        trials.apply(preview(ControllerInputApi.XINPUT))
        assertEquals(ControllerInputApi.XINPUT, port!!.value.api)
        trials.apply(preview(ControllerInputApi.BOTH))
        assertEquals(original, trials.undo.value!!.before)
        trials.restore("STEAM_42")
        assertEquals(original, port!!.value)
        assertNull(trials.undo.value)
    }

    @Test fun onlyApiAndMapperChangesCanBeTriedLive() {
        assertNull(trials.preview("STEAM_99", proposal(ControllerInputApi.XINPUT)))
        assertNull(trials.preview("STEAM_42", proposal(ControllerInputApi.DINPUT)))
        assertNull(trials.preview("STEAM_42", ConfigProposal(null, null, "Test", settings = mapOf("inputApi" to "XINPUT", "sdlControllerAPI" to "true"))))
        assertNull(trials.preview("STEAM_42", ConfigProposal(30, null, "Test")))
        val mapper = ConfigProposal(null, null, "Test", directInputMapper = DirectInputMapper.XINPUT)
        trials.apply(requireNotNull(trials.preview("STEAM_42", mapper)))
        assertEquals(DirectInputMapper.XINPUT, port!!.value.mapper)
    }

    @Test fun staleLaunchAndRuntimeDriftAreRejectedWithoutOverwritingAnotherChoice() {
        val pending = preview(ControllerInputApi.XINPUT)
        port = Port("new-launch")
        assertTrue(runCatching { trials.apply(pending) }.isFailure)
        val current = preview(ControllerInputApi.XINPUT)
        port!!.value = port!!.value.copy(api = ControllerInputApi.AUTO)
        assertTrue(runCatching { trials.apply(current) }.isFailure)
        trials.apply(preview(ControllerInputApi.BOTH))
        port!!.value = port!!.value.copy(mapper = DirectInputMapper.XINPUT)
        assertTrue(runCatching { trials.restore("STEAM_42") }.isFailure)
        assertEquals(DirectInputMapper.XINPUT, port!!.value.mapper)
        assertNotNull(trials.undo.value)
    }

    @Test fun partialWriteFailureRollsBackAndDoesNotInventAnAppliedTrial() {
        val original = port!!.value
        port!!.failNext = true
        assertTrue(runCatching { trials.apply(preview(ControllerInputApi.XINPUT)) }.isFailure)
        assertEquals(original, port!!.value)
        assertNull(trials.undo.value)
    }

    @Test fun reconnectAlwaysLiftsTemporaryGateEvenOnCancellation() = runBlocking {
        val original = port!!.value
        try {
            trials.reconnect("STEAM_42") {
                assertTrue(port!!.disconnected)
                assertTrue(runCatching { trials.apply(preview(ControllerInputApi.XINPUT)) }.isFailure)
                throw CancellationException("user cancelled")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(port!!.disconnected)
        assertEquals(1, port!!.reconnects)
        assertEquals(original, port!!.value)
        assertNull(trials.undo.value)
    }

    @Test fun reconnectCleanupCannotActOnNewLaunchAndExitClearsUndo() = runBlocking {
        val oldPort = port!!
        trials.apply(preview(ControllerInputApi.XINPUT))
        trials.reconnect("STEAM_42") {
            port = Port("next-launch")
            trials.clear()
        }
        assertEquals(0, port!!.reconnects)
        assertFalse(port!!.disconnected)
        assertFalse("The old bridge must also release its temporary input gate", oldPort.disconnected)
        assertNull(trials.undo.value)
        trials.reconnect("STEAM_42") { }
        assertEquals(1, port!!.reconnects)
    }

    @Test fun reconnectCleanupReleasesOriginalBridgeWhenUiConnectionDisappears() = runBlocking {
        val original = port!!
        trials.reconnect("STEAM_42") { port = null }
        assertFalse("A missing UI connection must not leave input permanently disabled", original.disconnected)
        assertEquals(1, original.reconnects)
    }
}
