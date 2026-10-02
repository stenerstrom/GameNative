package app.gamenative.assistant

import android.app.Application
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import com.winlator.inputcontrols.ControllerManager
import com.winlator.inputcontrols.GamepadState
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ControllerInputTraceTest {
    private val game = "STEAM_42"
    private lateinit var token: String
    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PrefManager.init(app)
        ControllerManager.getInstance().init(app)
        ControllerInputTrace.buffer.reset()
        token = LiveGameSession.buffer.begin(game)
        PluviaApp.isActivityInForeground = true
    }
    @After fun cleanup() { LiveGameSession.end(); ControllerInputTrace.buffer.reset() }

    private fun device(gamepad: Boolean = true) = mock<InputDevice>().also {
        whenever(it.supportsSource(InputDevice.SOURCE_GAMEPAD)).thenReturn(gamepad)
        whenever(it.supportsSource(InputDevice.SOURCE_JOYSTICK)).thenReturn(gamepad)
    }
    private fun key(code: Int, device: InputDevice = device(), action: Int = KeyEvent.ACTION_DOWN) = mock<KeyEvent>().also {
        whenever(it.keyCode).thenReturn(code); whenever(it.device).thenReturn(device)
        whenever(it.deviceId).thenReturn(34); whenever(it.action).thenReturn(action)
    }
    @Test fun buttonProbeExcludesKeyboardTypingVirtualDevicesAndOtherGamesAndStopsInBackground() = runBlocking {
        ControllerInputTrace.key(game, key(KeyEvent.KEYCODE_BUTTON_A), false)
        assertNull(ControllerInputTrace.view(game))
        ControllerInputTrace.start(game)
        ControllerInputTrace.key(game, key(KeyEvent.KEYCODE_A), false)
        ControllerInputTrace.key(game, key(KeyEvent.KEYCODE_BUTTON_A, device(false)), false)
        val virtual = device().also { whenever(it.isVirtual).thenReturn(true) }
        ControllerInputTrace.key(game, key(KeyEvent.KEYCODE_BUTTON_A, virtual), false)
        ControllerInputTrace.key("STEAM_99", key(KeyEvent.KEYCODE_BUTTON_A), false)
        assertEquals(0, ControllerInputTrace.view(game)!!.androidSamples)
        ControllerInputTrace.key(game, key(KeyEvent.KEYCODE_BUTTON_A), false)
        ControllerInputTrace.key(game, key(KeyEvent.KEYCODE_BUTTON_A, action = KeyEvent.ACTION_UP), true)
        val tools = GameAssistantTools(ApplicationProvider.getApplicationContext(), game)
        val result = tools.read("read_controller_trace")
        assertEquals(2, JSONObject(result).getJSONObject("counts").getInt("ANDROID"))
        assertTrue(result.contains("blocked_by_overlay_or_pause"))
        val values = JSONObject(result).getJSONArray("recentEvents").getJSONObject(0).getJSONObject("values")
        assertTrue(values.has(KeyEvent.keyCodeToString(KeyEvent.KEYCODE_BUTTON_A)))
        assertFalse(values.has(KeyEvent.keyCodeToString(KeyEvent.KEYCODE_A)))
        LiveGameSession.collecting(token, false)
        ControllerInputTrace.key(game, key(KeyEvent.KEYCODE_BUTTON_B), false)
        assertEquals(2, ControllerInputTrace.view(game)!!.androidSamples)
        assertFalse(ControllerInputTrace.view(game)!!.active)
        assertFalse(JSONObject(ControllerInputTrace.read("STEAM_99")).getBoolean("available"))
    }

    @Test fun sticksTriggersAndBridgeEvidenceAreCapturedAndBecomeHistoricalAfterExit() {
        ControllerInputTrace.start(game)
        val device = device()
        val range = ReflectionHelpers.callConstructor(InputDevice.MotionRange::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType!!, MotionEvent.AXIS_X),
            ClassParameter.from(Int::class.javaPrimitiveType!!, InputDevice.SOURCE_JOYSTICK),
            ClassParameter.from(Float::class.javaPrimitiveType!!, -1f),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 1f),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0f),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0f),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0f))
        whenever(device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK)).thenReturn(range)
        whenever(device.getMotionRange(MotionEvent.AXIS_RTRIGGER, InputDevice.SOURCE_JOYSTICK)).thenReturn(range)
        val event = mock<MotionEvent>()
        whenever(event.device).thenReturn(device); whenever(event.deviceId).thenReturn(34)
        whenever(event.source).thenReturn(InputDevice.SOURCE_JOYSTICK)
        whenever(event.isFromSource(InputDevice.SOURCE_JOYSTICK)).thenReturn(true)
        whenever(event.getAxisValue(MotionEvent.AXIS_X)).thenReturn(-0.8f)
        whenever(event.getAxisValue(MotionEvent.AXIS_RTRIGGER)).thenReturn(0.9f)
        ControllerInputTrace.motion(game, event, false)
        ControllerInputTrace.binding(token, 34, KeyEvent.KEYCODE_BUTTON_A, "GAMEPAD_BUTTON_A", true, 0f)
        val state = GamepadState().apply { thumbLX = -0.8f; triggerR = 0.9f; setPressed(0, true) }
        ControllerInputTrace.guestState("old-session", 1, state, true, false)
        ControllerInputTrace.guestState(token, 1, state, false, false)
        ControllerInputTrace.guestState(token, 1, state, true, false)
        val result = ControllerInputTrace.view(game)!!
        assertEquals(1, result.androidSamples); assertEquals(1, result.mappedSamples); assertEquals(1, result.wineSamples)
        assertTrue(JSONObject(result.json).getJSONArray("recentEvents").getJSONObject(0)
            .getJSONObject("values").has(MotionEvent.axisToString(MotionEvent.AXIS_RTRIGGER)))
        assertTrue(result.json.contains("GAMEPAD_BUTTON_A"))
        LiveGameSession.end()
        assertFalse(JSONObject(ControllerInputTrace.read(game)).getBoolean("sameLaunchStillRunning"))
        ControllerInputTrace.guestState(token, 1, state, true, false)
        assertEquals(1, ControllerInputTrace.view(game)!!.wineSamples)
    }

    @Test fun pausedLaunchCannotStartAndDurationFinishesWithoutModelCalls() {
        LiveGameSession.paused(token, true)
        assertTrue(runCatching { ControllerInputTrace.start(game) }.isFailure)
        LiveGameSession.paused(token, false)
        ControllerInputTrace.start(game)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(21))
        ControllerInputTrace.key(game, key(KeyEvent.KEYCODE_BUTTON_A), false)
        assertFalse(ControllerInputTrace.view(game)!!.active)
        assertEquals(0, ControllerInputTrace.view(game)!!.androidSamples)
    }
}
