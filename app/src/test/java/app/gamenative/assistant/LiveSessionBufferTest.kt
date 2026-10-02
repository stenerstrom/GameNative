package app.gamenative.assistant

import app.gamenative.powercontrol.metrics.CpuUsageSource
import app.gamenative.powercontrol.metrics.MetricsSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal fun liveTestMetrics(at: Long) = MetricsSnapshot(at, 29.5f, 33f, 41f, 58f, 2, 60,
    null, CpuUsageSource.UNAVAILABLE, null, null, null)

class LiveSessionBufferTest {
    private var elapsed = 0L
    private var wall = 100_000L
    private val buffer = LiveSessionBuffer({ wall }, { elapsed })
    private fun advance(ms: Long) { elapsed += ms; wall += ms }
    private fun sample(token: String) = buffer.metrics(token, liveTestMetrics(wall))

    @Test fun dataIsScopedToTheCurrentLaunchAndOldProducerTokensCannotPolluteAnotherGame() {
        val old = buffer.begin("STEAM_42")
        advance(2100); sample(old); buffer.line(old, "old game line")
        assertEquals("live", buffer.view("STEAM_42")!!.status)
        assertNull(buffer.view("GOG_42"))
        val next = buffer.begin("GOG_42")
        sample(old); buffer.line(old, "late output"); buffer.paused(old, true)
        assertNull(buffer.view("STEAM_42"))
        assertTrue(buffer.view("GOG_42")!!.lines.isEmpty())
        assertTrue(buffer.view("GOG_42")!!.points.isEmpty())
        assertFalse(buffer.view("GOG_42")!!.paused)
        assertNotEquals(old, next)
        buffer.end(); sample(next); buffer.line(next, "after stop")
        assertNull(buffer.token()); assertNull(buffer.view("GOG_42"))
    }

    @Test fun pausedBackgroundWarmupStaleAndNoFramesNeverExposeCurrentGameplayFps() {
        val token = buffer.begin("CUSTOM_GAME_1")
        assertEquals("waiting", buffer.view("CUSTOM_GAME_1")!!.status)
        sample(token)
        assertEquals("warming_up", buffer.view("CUSTOM_GAME_1")!!.status)
        advance(2100); sample(token)
        assertEquals(29.5f, buffer.view("CUSTOM_GAME_1")!!.current!!.fps)
        buffer.paused(token, true); sample(token)
        assertEquals("paused", buffer.view("CUSTOM_GAME_1")!!.status)
        assertTrue(JSONObject(buffer.view("CUSTOM_GAME_1")!!.json()).isNull("current"))
        buffer.paused(token, false); advance(2100)
        // A pre-resume sample never becomes live merely because two seconds pass.
        assertNotEquals("live", buffer.view("CUSTOM_GAME_1")!!.status)
        sample(token); buffer.collecting(token, false)
        assertEquals("background", buffer.view("CUSTOM_GAME_1")!!.status)
        buffer.collecting(token, true); advance(2100); sample(token)
        advance(2501)
        assertEquals("stale", buffer.view("CUSTOM_GAME_1")!!.status)
        assertNull(buffer.view("CUSTOM_GAME_1")!!.current)
        buffer.metrics(token, liveTestMetrics(wall).copy(totalFrameCount = 0, fps = 0f))
        assertEquals("no_frames", buffer.view("CUSTOM_GAME_1")!!.status)
        assertNull(buffer.view("CUSTOM_GAME_1")!!.current)
    }

    @Test fun ringIsBoundedRetainsOverlayAndStrideMetadataAndRejectsDelayedOrInvalidSamples() {
        val token = buffer.begin("STEAM_42", debugRun = true)
        buffer.assistant("STEAM_42", true)
        repeat(100) { advance(500); buffer.metrics(token, liveTestMetrics(wall), 2) }
        val live = buffer.view("STEAM_42")!!
        assertEquals(60, live.points.size)
        assertTrue(live.points.all { it.assistantVisible && it.frameSampleStride == 2 })
        val data = JSONObject(live.json())
        assertTrue(data.getJSONObject("current").isNull("gpuUsagePercent"))
        assertTrue(data.getJSONObject("log").getBoolean("debugRun"))
        buffer.metrics(token, liveTestMetrics(wall - 10_000))
        buffer.metrics(token, liveTestMetrics(wall + 10_000))
        assertEquals(live.points, buffer.view("STEAM_42")!!.points)
        buffer.metrics(token, liveTestMetrics(wall).copy(fps = Float.NaN, gpuUsagePercent = Float.POSITIVE_INFINITY))
        assertNull(buffer.view("STEAM_42")!!.current)
        assertFalse(buffer.view("STEAM_42")!!.json().contains("NaN"))
        advance(31_000)
        assertTrue(buffer.view("STEAM_42")!!.points.isEmpty())
    }

    @Test fun logRedactsSecretsBeforeEvictionAndBoundsBurstsLongLinesAndTotalMemory() {
        val token = buffer.begin("STEAM_42")
        buffer.line(token, "mail person@example.com Authorization: Bearer private-access")
        buffer.line(token, "password=super-private-password")
        assertFalse(buffer.view("STEAM_42")!!.json().contains("private-access"))
        assertFalse(buffer.view("STEAM_42")!!.json().contains("super-private-password"))
        assertFalse(buffer.view("STEAM_42")!!.json().contains("person@example.com"))
        repeat(300) { buffer.line(token, "normal line $it") }
        buffer.line(token, "-----BEGIN PRIVATE KEY-----") // Even while rate limited.
        advance(1100)
        repeat(200) { buffer.line(token, "private-key-body-$it") }
        buffer.line(token, "-----END PRIVATE KEY-----")
        buffer.line(token, "X".repeat(4097))
        repeat(300) { advance(20); buffer.line(token, "valid-line-$it " + "v".repeat(400)) }
        val view = buffer.view("STEAM_42")!!
        assertTrue(view.lines.size <= 160)
        assertTrue(view.lines.sumOf { it.text.length } <= 24_000)
        assertTrue(view.omittedLines > 300)
        assertFalse(view.json().contains("private-key-body"))
        assertFalse(view.json().contains("XXXXX"))
        assertTrue(view.lines.last().text.contains("valid-line-299"))
    }
}
