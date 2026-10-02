package app.gamenative.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControllerTraceBufferTest {
    private var now = 0L
    private val buffer = ControllerTraceBuffer { now }
    private fun event(token: String = "launch", value: Float = 1f, stage: ControllerTraceBuffer.Stage = ControllerTraceBuffer.Stage.ANDROID) =
        buffer.record(token, stage, 9876, 1, mapOf("BUTTON_A" to value), "gameplay_dispatch")

    @Test fun nothingIsRecordedWithoutAnExplicitTestAndOnlyMatchingGameAndLaunchCanReadOrWrite() {
        event(); assertNull(buffer.view("STEAM_42"))
        buffer.start("STEAM_42", "launch")
        event("old-launch")
        assertEquals(0, buffer.view("STEAM_42")!!.androidSamples)
        event(); assertEquals(1, buffer.view("STEAM_42")!!.androidSamples)
        assertNull(buffer.view("STEAM_99")); assertFalse(buffer.accepts("old-launch"))
        assertEquals(1, buffer.controllerNumber("STEAM_42", 9876))
        assertFalse(buffer.view("STEAM_42")!!.json.contains("9876"))
        buffer.start("STEAM_42", "next-launch"); event()
        assertEquals(0, buffer.view("STEAM_42")!!.androidSamples)
        buffer.reset(); assertNull(buffer.view("STEAM_42"))
    }

    @Test fun capturesPressReleaseAxesAndDistinctRoutingEvidenceWithoutClaimingGameResponse() {
        buffer.start("STEAM_42", "launch")
        event(value = 1f); now += 200; event(value = 0f)
        event(stage = ControllerTraceBuffer.Stage.PROFILE_BINDING)
        event(stage = ControllerTraceBuffer.Stage.WINE_BUFFER_UNAVAILABLE)
        event(stage = ControllerTraceBuffer.Stage.WINE_BUFFER)
        val view = buffer.view("STEAM_42")!!
        assertEquals(2, view.androidSamples); assertEquals(1, view.mappedSamples); assertEquals(1, view.wineSamples)
        val data = JSONObject(view.json)
        val range = data.getJSONArray("ranges").getJSONObject(0)
        assertEquals(0.0, range.getDouble("min"), 0.01)
        assertEquals(1.0, range.getDouble("max"), 0.01)
        assertEquals(0.0, range.getDouble("last"), 0.01)
        assertTrue(data.getString("evidenceLimit").contains("NOT proof the game read it"))
        assertEquals(1, data.getJSONObject("counts").getInt("WINE_BUFFER_UNAVAILABLE"))
    }

    @Test fun expiryStopsEvenWhenNoUiIsPollingAndBackgroundOrStopRetainsHistoricalResult() {
        buffer.start("STEAM_42", "launch"); event()
        now = 20_001; event()
        assertFalse(buffer.accepts("launch"))
        assertEquals(1, buffer.view("STEAM_42")!!.androidSamples)
        assertEquals("duration_complete", JSONObject(buffer.view("STEAM_42")!!.json).getString("finishedReason"))
        assertEquals(20_000, JSONObject(buffer.view("STEAM_42")!!.json).getInt("durationMs"))
        buffer.start("STEAM_42", "next"); event("next")
        buffer.finish("old", "background"); assertTrue(buffer.accepts("next"))
        buffer.finish("next", "app_backgrounded"); event("next")
        assertEquals(1, buffer.view("STEAM_42")!!.androidSamples)
        assertFalse(buffer.view("STEAM_42")!!.active)
    }

    @Test fun boundedRangesAndHistoryKeepExtremesAndNeverEmitNonFiniteValues() {
        buffer.start("STEAM_42", "launch")
        repeat(2000) { now += 1; event(value = if (it % 2 == 0) -0.8f else 0.9f) }
        event(value = Float.NaN); event(value = Float.POSITIVE_INFINITY)
        buffer.record("launch", ControllerTraceBuffer.Stage.ANDROID, 2, 1, mapOf("text with secrets" to 1f), "gameplay_dispatch")
        val data = JSONObject(buffer.view("STEAM_42")!!.json)
        assertEquals(2000, buffer.view("STEAM_42")!!.androidSamples)
        assertEquals(100, data.getJSONArray("recentEvents").length())
        assertTrue(data.getInt("omitted") > 0)
        assertEquals(-0.8, data.getJSONArray("ranges").getJSONObject(0).getDouble("min"), 0.001)
        assertTrue(data.toString().length < 80_000)
        assertFalse(data.toString().contains("NaN"))
    }

    @Test fun liveObservationOutlastsShortTestButStopsAtFiveMinutesAndKeepsFreshness() {
        buffer.start("STEAM_42", "launch", ControllerTraceBuffer.Mode.LIVE)
        now = 21_000; event()
        assertTrue(buffer.view("STEAM_42")!!.active)
        now = 24_000
        val live = JSONObject(buffer.view("STEAM_42")!!.json)
        assertEquals(3000L, live.getJSONArray("latestStages").getJSONObject(0).getLong("ageMs"))
        now = 300_001; event()
        assertFalse(buffer.view("STEAM_42")!!.active)
        assertEquals(1, buffer.view("STEAM_42")!!.androidSamples)
        assertEquals(300_000, JSONObject(buffer.view("STEAM_42")!!.json).getInt("durationMs"))
        now = 301_000
        assertEquals(1000, JSONObject(buffer.view("STEAM_42")!!.json).getInt("finishedAgeMs"))
    }

    @Test fun legacyObservationsAreOptInBoundedAndDoNotCarryIdentityAcrossReusedPorts() {
        buffer.legacy("launch", 10001, 42, "XInput", true, true)
        assertNull(buffer.view("STEAM_42"))
        buffer.start("STEAM_42", "launch", ControllerTraceBuffer.Mode.LIVE)
        buffer.legacy("old", 10001, 42, "XInput", true, true)
        assertTrue(buffer.view("STEAM_42")!!.clients.isEmpty())
        buffer.legacy("launch", 10001, 42, "XInput", false, true)
        buffer.legacy("launch", 10001, null, null, false, false)
        val client = buffer.view("STEAM_42")!!.clients.single()
        assertEquals(42, client.processId); assertFalse(client.allowed)
        assertEquals(0, client.statePackets); assertEquals(1, client.sendFailures)
        buffer.legacy("launch", 10001, 99, "DirectInput", true, true)
        assertEquals(0, buffer.view("STEAM_42")!!.clients.single().sendFailures)
        assertEquals(99, buffer.view("STEAM_42")!!.clients.single().processId)
        repeat(40) { now++; buffer.legacy("launch", 11000 + it, 50 + it, "XInput", true, true) }
        assertEquals(16, buffer.view("STEAM_42")!!.clients.size)
        assertFalse(buffer.view("STEAM_42")!!.clients.any { it.port == 10001 })
        buffer.finish("launch", "app_backgrounded")
        buffer.legacy("launch", 20000, 200, "XInput", true, true)
        assertFalse(buffer.view("STEAM_42")!!.clients.any { it.port == 20000 })
    }
}
