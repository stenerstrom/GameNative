package app.gamenative.assistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LiveGameToolsTest {
    @After fun cleanup() { LiveGameSession.end() }

    @Test fun normalLaunchProvidesMetricsAndFilteredOutputWithoutAContainerOrSavedDebugRun() = runBlocking {
        val token = LiveGameSession.buffer.begin("STEAM_42")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        LiveGameSession.metrics(token, liveTestMetrics(System.currentTimeMillis()), 1)
        LiveGameSession.line(token, "wine: graphics context lost")
        LiveGameSession.line(token, "Authorization: Bearer secret-access")
        val tools = GameAssistantTools(ApplicationProvider.getApplicationContext(), "STEAM_42")
        for (name in listOf("read_live_session", "read_performance", "read_game_log")) {
            val raw = tools.read(name)
            val data = JSONObject(raw)
            assertEquals("STEAM_42", data.getString("game"))
            assertEquals("live", data.getString("status"))
            assertEquals(29.5, data.getJSONObject("current").getDouble("fps"), 0.01)
            assertFalse(data.getJSONObject("log").getBoolean("debugRun"))
            assertTrue(raw.contains("graphics context lost"))
            assertFalse(raw.contains("secret-access"))
        }
        val otherGame = GameAssistantTools(ApplicationProvider.getApplicationContext(), "STEAM_99")
        assertFalse(JSONObject(otherGame.read("read_live_session")).getBoolean("available"))
        LiveGameSession.end()
        assertFalse(JSONObject(tools.read("read_live_session")).getBoolean("available"))
    }
}
