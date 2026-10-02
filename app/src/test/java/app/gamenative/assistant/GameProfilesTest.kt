package app.gamenative.assistant

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameProfilesTest {
    @get:Rule val dir = TemporaryFolder()
    private val state = JSONObject("""{"settings":{"fps":30,"unrelated":"keep"},"controls":{"a":"A"},"mods":{}}""")
    private var running = false
    private var fail = false
    private var writes = 0
    private val backend = object : GameProfiles.Backend {
        override suspend fun capture() = GameProfiles.copy(state)
        override suspend fun validate(target: JSONObject) { checkStopped() }
        override fun checkStopped() { check(!running) }
        override suspend fun write(target: JSONObject) {
            writes++
            target.keys().forEach { key ->
                if (key == "settings") target.getJSONObject(key).keys().forEach { k -> state.getJSONObject(key).put(k, target.getJSONObject(key).get(k)) }
                else state.put(key, target.get(key))
                if (fail) error("simulated write failure")
            }
        }
    }
    private fun store(game: String = "STEAM_42") = GameProfiles(File(dir.root, game), game, backend)
    @Test fun namedProfileAndUndoSurviveReopenAndPreserveUnrelatedFields() = runBlocking {
        val tools = store()
        val save = tools.prepareSave("game", "Working", JSONObject("""{"settings":{"fps":30},"controls":{"a":"A"}}"""), "test")
        assertEquals(0, tools.inventory().length()); assertEquals(0, writes)
        tools.apply(save, "v1"); assertEquals(0, writes)
        val id = store().inventory().getJSONObject(0).getString("profile_id")
        state.getJSONObject("settings").put("fps", 120)
        state.getJSONObject("controls").put("a", "B")
        val restored = store()
        val preview = restored.prepareRestore(id, "restore")
        assertEquals(120, state.getJSONObject("settings").getInt("fps"))
        restored.apply(preview, "v2")
        assertEquals(30, state.getJSONObject("settings").getInt("fps")); assertTrue(store().hasUndo())
        state.getJSONObject("settings").put("unrelated", "manual edit")
        store().restore()
        assertEquals(120, state.getJSONObject("settings").getInt("fps"))
        assertEquals("B", state.getJSONObject("controls").getString("a"))
        assertEquals("manual edit", state.getJSONObject("settings").getString("unrelated")); assertFalse(store().hasUndo())
    }
    @Test fun changedStateOrNewLaunchRejectsStagedWriteAndDoesNotCreateUndo() = runBlocking {
        val tools = store()
        val p = tools.prepareChange("cap", "test", JSONObject("""{"settings":{"fps":60}}"""))
        running = true; assertTrue(runCatching { tools.apply(p, "v1") }.isFailure); running = false
        state.getJSONObject("settings").put("fps", 45)
        assertTrue(runCatching { tools.apply(p, "v1") }.isFailure)
        assertFalse(tools.hasUndo()); assertEquals(0, writes)
    }
    @Test fun failedPartialWriteRetainsDurableUndoAndCanRecoverAfterReopen() = runBlocking {
        val tools = store()
        val before = state.toString()
        val p = tools.prepareChange("both", "test", JSONObject("""{"settings":{"fps":60},"controls":{"a":"B"}}"""))
        fail = true
        assertTrue(runCatching { tools.apply(p, "v1") }.isFailure); assertTrue(tools.hasUndo())
        fail = false; store().restore()
        assertTrue(GameProfiles.same(JSONObject(before), state)); assertFalse(tools.hasUndo())
    }
    @Test fun externalEditBlocksUndoAndKeepAndRetainsBackup() = runBlocking {
        val tools = store(); val p = tools.prepareChange("test", "test", JSONObject("""{"settings":{"fps":60}}"""))
        tools.apply(p, "v1")
        state.getJSONObject("settings").put("fps", 90)
        assertTrue(runCatching { store().restore() }.isFailure)
        assertTrue(runCatching { store().keep() }.isFailure); assertTrue(tools.hasUndo())
        assertEquals(90, state.getJSONObject("settings").getInt("fps"))
    }
    @Test fun profilesArePerGameNamesUniqueAndDeletionDoesNotChangeGame() = runBlocking {
        val tools = store()
        tools.apply(tools.prepareSave("controls", "Control", JSONObject().put("controls", state.getJSONObject("controls")), "test"), "v1")
        assertTrue(runCatching { tools.prepareSave("controls", "CONTROL", state, "test") }.isFailure)
        assertEquals(0, store("STEAM_99").inventory().length())
        val id = tools.inventory().getJSONObject(0).getString("profile_id")
        assertTrue(runCatching { store("STEAM_99").prepareRestore(id, "test") }.isFailure)
        tools.delete(id); assertEquals(0, tools.inventory().length()); assertEquals(0, writes)
    }
    @Test fun oldReviewTokenAndOutstandingUndoCannotOverwriteEachOther() = runBlocking {
        val tools = store()
        val old = tools.prepareChange("one", "test", JSONObject("""{"settings":{"fps":60}}"""))
        val newer = tools.prepareChange("two", "test", JSONObject("""{"settings":{"fps":90}}"""))
        assertTrue(runCatching { tools.apply(old, "v1") }.isFailure)
        tools.apply(newer, "v1")
        assertTrue(runCatching { tools.prepareChange("three", "test", JSONObject("""{"settings":{"fps":120}}""")) }.isFailure)
        tools.keep(); assertFalse(tools.hasUndo()); assertEquals(90, state.getJSONObject("settings").getInt("fps"))
    }
}
