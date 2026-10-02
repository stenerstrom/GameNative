package app.gamenative.assistant

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameScreenshotTest {
    @Test fun explicitScreenshotUsesImageContentOnceAcrossToolTurnsAndNeverEntersHistory() = runBlocking {
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        val tools = object : GameAssistantAgent.Tools {
            override val screenshot = GameScreenshot.forTest(bitmap, "STEAM_42")
            override suspend fun read(name: String) = "{}"
            override suspend fun prepare(proposal: ConfigProposal) = error("No mutations")
            override fun hasBackup() = false
        }
        var count = 0
        val reply = GameAssistantAgent.run("m", "Vad visar bilden?", emptyList(), tools, { request ->
            val images = request.getJSONArray("input").objects().flatMap { it.optJSONArray("content")?.objects().orEmpty() }.filter { it.optString("type") == "input_image" }
            assertEquals(1, images.size)
            val data = images.single().getString("image_url")
            assertTrue(data.startsWith("data:image/jpeg;base64,"))
            val bytes = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
            assertEquals(160, BitmapFactory.decodeByteArray(bytes, 0, bytes.size).width)
            assertFalse(request.getBoolean("store")); assertTrue(request.getBoolean("stream"))
            if (count++ == 0) AssistantProtocol.completedResponse(JSONObject().put("status", "completed").put("output", JSONArray().put(
                JSONObject().put("type", "function_call").put("namespace", "game").put("name", "read_configuration").put("call_id", "config").put("arguments", "{}"))))
            else AssistantProtocol.Reply("En grön testbild.", null)
        }, {})
        assertEquals(2, count)
        assertFalse(AssistantProtocol.conversationTurn("Fråga", reply).toString().contains("data:image"))
    }
    @Test fun requestsWithoutExplicitImageContainNoImageAndNoCaptureOutsideSelectedGame() = careIo {
        val request = GameAssistantAgent.request("m", "Fråga", emptyList())
        assertFalse(request.toString().contains("input_image"))
        LiveGameSession.end()
        // capture fails before reading a view when the selected game is not running.
        val failed = runCatching { GameScreenshot.capture("STEAM_42") }
        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull()!!.message!!.contains("Starta detta spel"))
    }
}
