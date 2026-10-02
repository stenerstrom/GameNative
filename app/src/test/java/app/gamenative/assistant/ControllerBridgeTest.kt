package app.gamenative.assistant

import android.app.Application
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.ui.screen.xserver.PhysicalControllerHandler
import com.winlator.inputcontrols.*
import com.winlator.widget.XServerRendererView
import com.winlator.winhandler.WinHandler
import com.winlator.xserver.XServer
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Only the JNI wake-up is substituted; the production mapper and shared-memory writes execute. */
@Implements(value = WinHandler::class, isInAndroidSdk = false)
class ShadowControllerWinHandlerNative {
    companion object {
        var notifications = 0
        @JvmStatic @Implementation fun __staticInitializer__() = Unit
        @JvmStatic @Implementation fun notifyStateChanged(playerIndex: Int) { notifications++ }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShadowControllerWinHandlerNative::class],
    instrumentedPackages = ["com.winlator.winhandler"])
class ControllerBridgeTest {
    @get:Rule val folder = TemporaryFolder()
    @After fun cleanup() { LiveGameSession.end(); ControllerInputTrace.buffer.reset() }

    @Test fun physicalButtonIsTracedThroughRealProfileMappingAndSharedMemoryWrite() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PrefManager.init(app); ControllerManager.getInstance().init(app)
        val token = LiveGameSession.buffer.begin("STEAM_42")
        ControllerInputTrace.buffer.start("STEAM_42", token)
        val server = mock<XServer>()
        val renderer = mock<XServerRendererView>().also { whenever(it.context).thenReturn(app) }
        val bridge = WinHandler(server, renderer)
        whenever(server.winHandler).thenReturn(bridge)
        WinHandler::class.java.getDeclaredField("assistantSessionToken").apply { isAccessible = true; set(bridge, token) }
        val state = GamepadState()
        val controller = ExternalController().apply {
            addControllerBinding(ExternalControllerBinding().apply { setKeyCode(KeyEvent.KEYCODE_BUTTON_A); binding = Binding.GAMEPAD_BUTTON_B })
        }
        val profile = mock<ControlsProfile>().also {
            whenever(it.getController(34)).thenReturn(controller); whenever(it.gamepadState).thenReturn(state)
        }
        val device = mock<InputDevice>().also { whenever(it.supportsSource(InputDevice.SOURCE_GAMEPAD)).thenReturn(true) }
        fun event(action: Int) = mock<KeyEvent>().also {
            whenever(it.device).thenReturn(device); whenever(it.deviceId).thenReturn(34)
            whenever(it.keyCode).thenReturn(KeyEvent.KEYCODE_BUTTON_A); whenever(it.action).thenReturn(action)
        }
        RandomAccessFile(folder.newFile(), "rw").use { file ->
            file.setLength(64)
            val memory = file.channel.map(FileChannel.MapMode.READ_WRITE, 0, 64).apply { order(ByteOrder.LITTLE_ENDIAN) }
            WinHandler::class.java.getDeclaredField("gamepadBuffer").apply { isAccessible = true; set(bridge, memory) }
            val handler = PhysicalControllerHandler(profile, server)
            try {
                val down = event(KeyEvent.ACTION_DOWN)
                ControllerInputTrace.key("STEAM_42", down, false)
                assertTrue(handler.onKeyEvent(down))
                assertEquals(1, memory.getInt(40)) // Connected, real bridge layout.
                assertEquals(0, memory.get(16).toInt()) // Physical A was remapped.
                assertEquals(1, memory.get(17).toInt()) // Virtual B was written.
                val trace = ControllerInputTrace.view("STEAM_42")!!
                assertEquals(1, trace.androidSamples)
                assertTrue(trace.mappedSamples > 0 && trace.wineSamples > 0)
                assertTrue(trace.json.contains("GAMEPAD_BUTTON_B"))
                assertTrue(ShadowControllerWinHandlerNative.notifications > 0)
                val up = event(KeyEvent.ACTION_UP)
                ControllerInputTrace.key("STEAM_42", up, false)
                assertTrue(handler.onKeyEvent(up))
                assertEquals(0, memory.get(17).toInt())
                assertFalse(bridge.assistantControllerStatus.getBoolean("guestInitReceived"))
            } finally { handler.cleanup() }
        }
    }
}
