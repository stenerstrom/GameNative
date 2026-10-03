package app.gamenative.assistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.PluviaApp
import app.gamenative.service.SteamService
import app.gamenative.utils.DebugReportUtils
import app.gamenative.utils.IntentLaunchManager
import com.winlator.container.Container
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CodexDebugSessionTest {
    private val game = "STEAM_42"
    private lateinit var app: Application
    private lateinit var container: Container
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); PrefManager.init(app); SteamService.keepAlive = false
        container = Container(game).apply {
            setRootDir(File(app.filesDir, "container-test").apply { mkdirs() }); screenSize = "1280x720"; envVars = "password=private"
            saveDataChecked()
        }
        LiveGameSession.begin(game, false)
        CodexDebugSession.begin(app, game, container, false)
    }
    @After fun stop() = runBlocking { LiveGameSession.end(); CodexDebugSession.finish(game); CodexDebugSession.cancelRequest(game); SteamService.keepAlive = false }

    @Test fun checkpointsRedactKeepSameLaunchAndFinalFlushPreservesCrashTailWithoutAFileLog() = runBlocking {
        val before = container.configFile.readBytes()
        val id = CodexDebugSession.start(app, game, DebugProblem.OTHER)
        val token = LiveGameSession.token()
        LiveGameSession.line(token, "first event")
        LiveGameSession.line(token, "Authorization: Bearer private-secret")
        LiveGameSession.line(token, "-----BEGIN PRIVATE KEY-----")
        LiveGameSession.line(token, "key-body")
        LiveGameSession.line(token, "-----END PRIVATE KEY-----")
        LiveGameSession.metrics(token, liveTestMetrics(System.currentTimeMillis()), 1)
        CodexDebugSession.mark(game)
        val initial = CodexDebugSession.frozen(app, game, id)
        LiveGameSession.line(token, "late crash: missing DLL")
        LiveGameSession.end()
        assertEquals(id, CodexDebugSession.finish(game))
        val done = CodexDebugSession.store(app).read(game, id)
        assertEquals("ready", done.getString("state"))
        assertTrue(done.toString().contains("late crash"))
        assertFalse(initial.toString().contains("late crash"))
        assertFalse(done.toString().contains("private-secret"))
        assertFalse(done.toString().contains("key-body"))
        assertEquals(1, done.getJSONArray("markers").length())
        assertEquals(1, done.getJSONArray("samples").length())
        assertEquals(token, done.getString("launchId"))
        assertFalse(DebugReportUtils.logFile(File(app.filesDir, id)).exists())
        assertArrayEquals(before, container.configFile.readBytes())
    }

    @Test fun noControllerDataFromAnotherLaunchAndInputTestIsClaimedAtMostOnce() = runBlocking {
        val id = CodexDebugSession.start(app, game, DebugProblem.INPUT)
        ControllerInputTrace.buffer.start(game, "old-launch", ControllerTraceBuffer.Mode.TEST)
        CodexDebugSession.checkpoint(game)
        assertFalse(CodexDebugSession.frozen(app, game, id).has("controller"))
        assertTrue(CodexDebugSession.claimInputTest(game, id))
        assertFalse(CodexDebugSession.claimInputTest(game, id))
        assertFalse(CodexDebugSession.claimInputTest("STEAM_99", id))
    }

    @Test fun stoppedCaptureCanBeFollowedByAnotherInSameGameAndOldReportStaysFrozen() = runBlocking {
        val first = CodexDebugSession.start(app, game, DebugProblem.PERFORMANCE)
        CodexDebugSession.finish(game)
        val second = CodexDebugSession.start(app, game, DebugProblem.OTHER)
        assertNotEquals(first, second)
        CodexDebugSession.mark(game)
        assertEquals(0, CodexDebugSession.store(app).read(game, first).getJSONArray("markers").length())
        assertEquals(1, CodexDebugSession.store(app).read(game, second).getJSONArray("markers").length())
    }

    @Test fun diagnosticCollectionInvalidatesAndBlocksComparableFpsMeasurements() = runBlocking {
        PluviaApp.isActivityInForeground = true
        GameOptimizationSession.attach(app, game, container, false)
        GameOptimizationSession.start(app, game, 120, "Same scene")
        CodexDebugSession.start(app, game, DebugProblem.PERFORMANCE)
        assertFalse(GameOptimizationSession.capture.view(game)!!.active)
        GameOptimizationSession.awaitSaved()
        assertFalse(GameOptimizationSession.store(app, game).read().getJSONArray("runs").getJSONObject(0).getBoolean("usable"))
        assertThrows(IllegalStateException::class.java) { GameOptimizationSession.start(app, game, 120, "Same scene") }
        CodexDebugSession.finish(game)
        GameOptimizationSession.start(app, game, 120, "Same scene")
        assertTrue(GameOptimizationSession.capture.view(game)!!.active)
    }

    @Test fun libraryLaunchUsesExistingParserAndVerbosePresetDoesNotPersistConfiguration() = runBlocking {
        LiveGameSession.end(); CodexDebugSession.finish(game)
        val before = container.configFile.readBytes()
        val intent = CodexDebugSession.requestLaunch(app, game, DebugProblem.CRASH)
        val draft = CodexDebugSession.store(app).list(game).single().getString("id")
        assertEquals(game, IntentLaunchManager.parseLaunchIntent(intent)!!.appId)
        LiveGameSession.begin(game, false)
        assertTrue(CodexDebugSession.begin(app, game, container, false))
        val id = CodexDebugSession.start(app, game, DebugProblem.CRASH)
        assertEquals(draft, id)
        assertEquals(1, CodexDebugSession.store(app).list(game).size)
        assertTrue(CodexDebugSession.frozen(app, game, id).getJSONObject("setup").getBoolean("verboseLogging"))
        assertArrayEquals(before, container.configFile.readBytes())
    }

    @Test fun earlyLaunchFailureAndCancellationPreserveLocalDraftWithoutArmingNextLaunch() = runBlocking {
        LiveGameSession.end(); CodexDebugSession.finish(game)
        CodexDebugSession.requestLaunch(app, game, DebugProblem.CRASH)
        assertTrue(CodexDebugSession.requested(game))
        val id = CodexDebugSession.store(app).list(game).single().getString("id")
        CodexDebugSession.launchError(game, "missing setup file\npassword=do-not-send")
        withTimeout(5000) { while (CodexDebugSession.requested(game)) delay(10) }
        val report = CodexDebugSession.store(app).read(game, id)
        assertEquals("pending_launch", report.getString("source"))
        assertEquals("ready", report.getString("state"))
        assertTrue(report.toString().contains("missing setup file"))
        assertFalse(report.toString().contains("do-not-send"))
        CodexDebugSession.requestLaunch(app, game, DebugProblem.CRASH)
        CodexDebugSession.cancelRequest(game)
        assertFalse(CodexDebugSession.requested(game))
        LiveGameSession.begin(game, false)
        assertFalse(CodexDebugSession.begin(app, game, container, false))
    }

    @Test fun legacyImportIsExplicitFilteredAndOriginalRemainsAvailable() = runBlocking {
        val legacy = File(DebugReportUtils.reportsDir(app), "${game}_123").apply { mkdirs() }
        DebugReportUtils.headerFile(legacy).writeText(JSONObject().put("appId", game)
            .put("configs", JSONObject().put("screenSize", "960x540").put("envVars", "TOKEN=nope")).toString())
        GZIPOutputStream(DebugReportUtils.logFile(legacy).outputStream()).use { it.write("Missing test.dll\npassword=secret".toByteArray()) }
        assertTrue(CodexDebugSession.reports(app, game).isEmpty())
        val id = CodexDebugSession.importLegacy(app, game)
        val imported = CodexDebugSession.store(app).read(game, id)
        assertEquals("legacy-unknown", imported.getString("launchId"))
        assertTrue(imported.toString().contains("Missing test.dll"))
        assertFalse(imported.toString().contains("secret"))
        assertTrue(DebugReportUtils.logFile(legacy).exists())
    }
}
