package app.gamenative.assistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.gamenative.api.DebugReportApi
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CodexDebugReportTest {
    private val root = Files.createTempDirectory("codex-reports").toFile()
    private var clock = 100_000L
    private val store = CodexDebugReportStore(root) { clock }
    private val game = "STEAM_42"
    private fun create(launch: String = "one", problem: DebugProblem = DebugProblem.CRASH) = store.create(game, launch, clock, problem,
        JSONObject().put("configuration", CodexDebugReportStore.configuration(JSONObject().put("screenSize", "1280x720")
            .put("envVars", "SECRET_TOKEN=do-not-send").put("password", "private")))
            .put("note", "mail user@example.com\nAuthorization: Bearer top-secret"), "test")
    private fun query(section: String = "log", text: String = "", from: Long = 0, to: Long = 0, offset: Int = 0) =
        JSONObject().put("section", section).put("query", text).put("from_ms", from).put("to_ms", to).put("offset", offset)

    @Test fun emptyLogStillSurvivesReopeningAndRecoveryDoesNotLoseItsSetup() {
        val recording = create()
        val id = recording.getString("id")
        assertEquals(0, store.list(game).single().getInt("logLines"))
        store.recover("one")
        assertEquals("recording", store.read(game, id).getString("state"))
        CodexDebugReportStore(root).recover(null)
        val recovered = store.read(game, id)
        assertEquals("interrupted", recovered.getString("state"))
        assertEquals("1280x720", recovered.getJSONObject("setup").getJSONObject("configuration").getString("screenSize"))
        assertFalse(recovered.toString().contains("do-not-send"))
        assertFalse(recovered.toString().contains("user@example.com"))
        assertFalse(recovered.toString().contains("top-secret"))
        assertTrue(store.list(game).single().getString("evidenceNote").contains("not a controlled benchmark"))
    }

    @Test fun failedAtomicWriteRetainsPriorSnapshotAndRecoveryHandlesLegacyBackup() {
        val report = create(); val id = report.getString("id")
        val file = File(root, "$game/$id.json")
        val prior = file.readText()
        report.put("huge", "x".repeat(CodexDebugReportStore.MAX_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { store.save(report) }
        assertEquals(prior, file.readText())
        val backup = File(file.path + ".bak"); backup.writeText(prior); file.writeText("broken-write")
        assertEquals(id, store.read(game, id).getString("id"))
        assertFalse(backup.exists())
    }

    @Test fun gameReportIdentityAndSymlinksCannotEscapePrivateStorage() {
        val report = create(); val id = report.getString("id")
        assertThrows(Exception::class.java) { store.read("STEAM_99", id) }
        assertThrows(IllegalArgumentException::class.java) { store.read("../outside", id) }
        assertThrows(IllegalArgumentException::class.java) { store.read(game, "../outside") }
        val file = File(root, "$game/$id.json")
        val other = File(root, "private.txt").apply { writeText("hidden") }
        Files.createSymbolicLink(File(file.path + ".bak").toPath(), other.toPath())
        assertThrows(IllegalArgumentException::class.java) { store.read(game, id) }
        assertEquals("hidden", other.readText())
    }

    @Test fun queryIsLiteralBoundedPaginatedAndPinnedToOneLaunch() {
        val report = create()
        report.put("lines", JSONArray((0..129).map { JSONObject().put("timestampMs", clock + it * 100).put("text", "line $it: [fault].* literal") }))
        val first = JSONObject(CodexDebugReportStore.query(report, query()))
        assertEquals(60, first.getJSONArray("rows").length())
        assertEquals(60, first.getInt("nextOffset"))
        val next = JSONObject(CodexDebugReportStore.query(report, query(offset = 60)))
        assertTrue(next.getJSONArray("rows").getJSONObject(0).getString("text").startsWith("line 60:"))
        val window = JSONObject(CodexDebugReportStore.query(report, query(text = "[fault].*", from = 500, to = 900)))
        assertEquals(5, window.getJSONArray("rows").length())
        assertEquals(0, JSONObject(CodexDebugReportStore.query(report, query(text = "[fault].+"))).getInt("matchingRows"))
        create("newer")
        assertEquals("one", window.getString("launchId"))
        assertThrows(IllegalArgumentException::class.java) { CodexDebugReportStore.query(report, query().put("path", "/secret")) }
        assertThrows(IllegalArgumentException::class.java) { CodexDebugReportStore.query(report, query(from = -1)) }
        assertThrows(IllegalArgumentException::class.java) { CodexDebugReportStore.query(report, query().put("offset", 0.2)) }
    }

    @Test fun retentionKeepsActiveReportsAndOnlyRemovesOldReportsNotGameFiles() {
        val active = create()
        repeat(15) {
            clock++
            val done = create("launch-$it").put("state", "ready")
            store.save(done)
        }
        assertEquals("recording", store.read(game, active.getString("id")).getString("state"))
        assertTrue(store.list(game).size <= 11) // ten newest plus a still-recording launch
        assertThrows(IllegalStateException::class.java) { store.delete(game, active.getString("id")) }
        store.recover(null)
        store.delete(game, active.getString("id"))
        assertTrue(store.list(game).none { it.getString("id") == active.getString("id") })
    }

    @Test fun aiDevDiscordSubmitIsBlockedBeforeAuthFileOrNetworkAccess() = runBlocking {
        val result = DebugReportApi.submit(JSONObject(), File("/file-that-does-not-exist"), "never-send")
        assertEquals("Debugrapporter analyseras i Codex-chatten i denna app.", (result as DebugReportApi.SubmitResult.Failure).message)
    }
}
