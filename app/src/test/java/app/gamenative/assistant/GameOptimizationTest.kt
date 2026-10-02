package app.gamenative.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameOptimizationTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = 0L
    private val capture = OptimizationCapture({ now }, { 100_000 + now })
    private fun start(game: String = "STEAM_42", scene: String = "Courtyard", environment: String = "same", target: Int = 30) {
        capture.reset(); now = 0
        capture.start(game, "launch", target, scene, "configuration", "{}", environment)
    }
    private fun full(game: String = "STEAM_42", scene: String = "Courtyard", environment: String = "same", target: Int = 30, fps: Float = 30f): JSONObject {
        start(game, scene, environment, target)
        repeat(135) {
            now = it * 500L
            capture.sample("launch", liveTestMetrics(100_000 + now).copy(fps = fps), true, 1)
        }
        assertTrue(capture.due())
        return JSONObject(capture.finish("completed")!!)
    }
    @Test fun countdownAndOverlappingWindowsAreExcludedAndOnlyOneLaunchCanContribute() {
        start()
        repeat(135) {
            now = it * 500L
            capture.sample("wrong", liveTestMetrics(100_000 + now).copy(fps = 999f), true, 1)
            capture.sample("launch", liveTestMetrics(100_000 + now).copy(fps = if (now < 7000) 500f else 30f), true, 1)
        }
        val result = JSONObject(capture.finish("completed")!!)
        assertTrue(result.getBoolean("usable"))
        assertEquals(60_000, result.getInt("durationMs"))
        val summary = result.getJSONObject("summary")
        assertEquals(30, summary.getInt("windows"))
        assertEquals(60, summary.getInt("observedWindowSeconds"))
        assertEquals(30.0, summary.getDouble("meanWindowFps"), 0.01)
        assertEquals(41.0, summary.getDouble("meanWindowP95Ms"), 0.01)
        assertTrue(summary.isNull("meanGpuPercent"))
        assertTrue(result.getString("method").contains("not whole-run p95"))
        assertNull(capture.finish("completed"))
    }
    @Test fun partialPausedMissingAndGeneratedFrameRunsAreRetainedButNeverUsable() {
        start()
        repeat(90) { now = it * 500L; capture.sample("launch", liveTestMetrics(100_000 + now), true, 1) }
        assertFalse(JSONObject(capture.finish("Spelet pausades.")!!).getBoolean("usable"))
        start()
        repeat(135) { now = it * 500L; capture.sample("launch", liveTestMetrics(100_000 + now), false, 1) }
        val missing = JSONObject(capture.finish("completed")!!)
        assertFalse(missing.getBoolean("usable")); assertTrue(missing.getJSONObject("summary").isNull("meanWindowFps"))
        start()
        repeat(135) { now = it * 500L; capture.sample("launch", liveTestMetrics(100_000 + now), true, 2) }
        val generated = JSONObject(capture.finish("completed")!!)
        assertFalse(generated.getBoolean("usable")); assertTrue(generated.toString().contains("stride 1"))
    }
    @Test fun staleInvalidAndRepeatedSourceTimestampsNeverBecomeValidWindows() {
        start()
        repeat(135) {
            now = it * 500L
            capture.sample("launch", liveTestMetrics(100_000 + now - 5000), true, 1)
            capture.sample("launch", liveTestMetrics(100_000 + now).copy(fps = Float.NaN), true, 1)
            capture.sample("launch", liveTestMetrics(100_000 + now).copy(frameTimeP95Ms = Float.POSITIVE_INFINITY), true, 1)
        }
        val result = JSONObject(capture.finish("completed")!!)
        assertFalse(result.getBoolean("usable")); assertFalse(result.toString().contains("NaN"))
        assertEquals(0, result.getJSONObject("summary").getInt("windows"))
    }
    @Test fun goalsAndPinnedHistoryPersistPerGameAndRemainBounded() {
        val store = GameOptimizationStore(folder.root, "STEAM_42")
        store.goal(60, "Courtyard")
        val first = full()
        store.add(first)
        repeat(15) { i -> store.add(full().put("recordedAtMs", 200_000 + i)) }
        val reopened = GameOptimizationStore(folder.root, "STEAM_42").read()
        assertEquals(60, reopened.getInt("targetFps"))
        assertEquals(8, reopened.getJSONArray("runs").length())
        assertEquals(first.getString("id"), reopened.getString("baselineId"))
        assertEquals(first.getString("id"), reopened.getJSONArray("runs").getJSONObject(0).getString("id"))
        val other = GameOptimizationStore(folder.root, "GOG_42").read()
        assertEquals(30, other.getInt("targetFps")); assertEquals(0, other.getJSONArray("runs").length())
        assertTrue(runCatching { store.add(full(game = "GOG_42")) }.isFailure)
        assertTrue(runCatching { GameOptimizationStore(folder.root, "../other") }.isFailure)
        val last = reopened.getJSONArray("runs").getJSONObject(7).getString("id")
        store.baseline(last)
        assertEquals(last, store.read().getString("baselineId"))
    }
    @Test fun comparisonRequiresCompleteRunsSameSceneGoalEnvironmentAndSimilarAvailableTemperatures() {
        val store = GameOptimizationStore(folder.root, "STEAM_42")
        store.add(full())
        fun add(run: JSONObject): JSONObject {
            val last = store.read().getJSONArray("runs")
            run.put("recordedAtMs", 100_001L + last.length())
            store.add(run)
            return GameOptimizationStore.comparison(store.read())
        }
        assertFalse(GameOptimizationStore.comparison(store.read()).getBoolean("eligible"))
        assertFalse(add(full(scene = "Another area")).getBoolean("eligible"))
        assertFalse(add(full(environment = "charging changed")).getBoolean("eligible"))
        assertFalse(add(full(target = 60)).getBoolean("eligible"))
        assertFalse(add(full().put("usable", false)).getBoolean("eligible"))
        val compared = add(full(fps = 35f))
        assertTrue(compared.getBoolean("eligible"))
        assertEquals(5.0, compared.getDouble("meanWindowFpsDelta"), 0.01)
        assertFalse(compared.getBoolean("configurationChanged")) // Repeating a run is not proof a change caused improvement.
        assertTrue(compared.getString("limits").contains("not proof"))
        val before = full().also { it.getJSONObject("summary").put("startCpuTempC", 30) }
        store.add(before.put("recordedAtMs", 300_000)); store.baseline(before.getString("id"))
        val hot = full().also { it.getJSONObject("summary").put("startCpuTempC", 45) }
        store.add(hot.put("recordedAtMs", 310_000))
        assertFalse(GameOptimizationStore.comparison(store.read()).getBoolean("eligible"))
    }
    @Test fun configurationFingerprintIgnoresKeyOrderAndSessionBookkeepingButIncludesPrivateSettings() {
        val a = JSONObject("""{"screenSize":"1280x720","envVars":"SECRET_TOKEN=one","extraData":{"b":2,"a":1},"sessionMetadata":{"fps":30}}""")
        val b = JSONObject("""{"extraData":{"a":1,"b":2},"sessionMetadata":{"fps":50},"envVars":"SECRET_TOKEN=one","screenSize":"1280x720"}""")
        val first = GameOptimizationStore.fingerprint(a)
        assertEquals(first, GameOptimizationStore.fingerprint(b))
        b.put("envVars", "SECRET_TOKEN=two")
        assertNotEquals(first, GameOptimizationStore.fingerprint(b))
        assertEquals(64, first.length); assertFalse(first.contains("SECRET"))
    }
    @Test fun corruptHistoryIsNotOverwrittenWhenSavingAGoal() {
        val file = java.io.File(folder.root, "STEAM_42.json").apply { writeText("broken") }
        assertTrue(runCatching { GameOptimizationStore(folder.root, "STEAM_42").goal(30, "Area") }.isFailure)
        assertEquals("broken", file.readText())
    }
}
