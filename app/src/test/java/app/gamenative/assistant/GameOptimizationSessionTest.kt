package app.gamenative.assistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import com.winlator.container.Container
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GameOptimizationSessionTest {
    private lateinit var app: Application
    private lateinit var container: Container
    private val game = "STEAM_42"
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); PrefManager.init(app)
        PluviaApp.isActivityInForeground = true
        container = Container(game).apply { setRootDir(File(app.filesDir, "test-game").apply { mkdirs() }); inputType = 3 }
        assertTrue(container.saveDataChecked())
        LiveGameSession.begin(game, false)
        GameOptimizationSession.attach(app, game, container, false)
    }
    @After fun cleanup() = runBlocking { LiveGameSession.end(); GameOptimizationSession.awaitSaved(); GameOptimizationSession.capture.reset() }
    private fun samples() {
        repeat(134) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(500))
            LiveGameSession.metrics(LiveGameSession.token(), liveTestMetrics(System.currentTimeMillis()), 1)
        }
    }
    @Test fun productionCollectorHookPersistsResultsWithoutChangingConfigurationOrCrossingGames() = runBlocking {
        val original = container.configFile.readBytes()
        GameOptimizationSession.start(app, game, 30, "Main hall")
        samples(); GameOptimizationSession.awaitSaved()
        val profile = GameOptimizationSession.store(app, game).read()
        assertTrue(profile.toString(), profile.getJSONArray("runs").getJSONObject(0).getBoolean("usable"))
        assertEquals(30, profile.getJSONArray("runs").getJSONObject(0).getJSONObject("summary").getInt("windows"))
        assertEquals(0, GameOptimizationSession.store(app, "GOG_42").read().getJSONArray("runs").length())
        assertArrayEquals(original, container.configFile.readBytes())
    }
    @Test fun changingConfigOrOpeningChatCannotProduceAValidOptimizationResult() = runBlocking {
        GameOptimizationSession.start(app, game, 30, "Main hall")
        container.configFile.writeText(JSONObject(container.configFile.readText()).put("screenSize", "640x480").toString())
        samples(); GameOptimizationSession.awaitSaved()
        val run = GameOptimizationSession.store(app, game).read().getJSONArray("runs").getJSONObject(0)
        assertFalse(run.getBoolean("usable"))
        assertFalse(GameOptimizationSession.store(app, game).read().has("baselineId"))
        GameOptimizationSession.attach(app, game, container, false)
        GameOptimizationSession.start(app, game, 30, "Main hall")
        LiveGameSession.assistant(game, true)
        assertFalse(GameOptimizationSession.capture.view(game)!!.active)
        GameOptimizationSession.awaitSaved()
        assertEquals(2, GameOptimizationSession.store(app, game).read().getJSONArray("runs").length())
    }
    @Test fun debugLaunchAndOtherGameCannotStartComparableMeasurements() {
        assertTrue(runCatching { GameOptimizationSession.start(app, "GOG_42", 30, "Main hall") }.isFailure)
        GameOptimizationSession.attach(app, game, container, true)
        assertTrue(runCatching { GameOptimizationSession.start(app, game, 30, "Main hall") }.isFailure)
        assertEquals(0, GameOptimizationSession.store(app, game).read().getJSONArray("runs").length())
    }
}
