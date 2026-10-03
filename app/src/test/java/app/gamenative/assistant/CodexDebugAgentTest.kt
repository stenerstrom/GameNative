package app.gamenative.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal fun debugTestCall(name: String, args: JSONObject = JSONObject(), id: String = name): AssistantProtocol.Reply {
    val item = JSONObject().put("type", "function_call").put("namespace", "game").put("name", name)
        .put("call_id", id).put("arguments", args.toString())
    return AssistantProtocol.completedResponse(JSONObject().put("status", "completed").put("output", JSONArray().put(item)))
}

class CodexDebugAgentTest {
    @Test fun reportToolsRequireExplicitAttachmentAndHistoricalSettingsNeverAuthorizeWrites() = runBlocking {
        val report = JSONObject().put("id", "test-report").put("game", "STEAM_42").put("launchId", "old-launch").put("startedAtMs", 10L)
        var writes = 0
        val tools = object : GameAssistantAgent.Tools {
            override val debugReport = report
            override suspend fun read(name: String): String = if (name == "read_debug_report") CodexDebugReportStore.summary(report).toString() else "{}"
            override suspend fun prepare(proposal: ConfigProposal): List<String> { writes++; return listOf("review only") }
            override fun hasBackup() = false
        }
        val proposal = JSONObject("""{"changes":[{"setting":"screenSize","value":"1280x720"}],"reason":"test"}""")
        val replies = ArrayDeque(listOf(debugTestCall("read_debug_report"), debugTestCall("propose_settings", proposal, "blocked"),
            debugTestCall("read_configuration"), debugTestCall("propose_settings", proposal, "allowed"), AssistantProtocol.Reply("Granska", null)))
        var last: JSONObject? = null
        val answer = GameAssistantAgent.run("model", "Analyze", emptyList(), tools, { last = it; replies.removeFirst() }, {})
        assertEquals(1, writes)
        assertNotNull(answer.proposal)
        assertTrue(last!!.getJSONArray("input").toString().contains("Read configuration before proposing changes"))
        assertTrue(last!!.getJSONArray("tools").toString().contains("search_debug_report"))
        assertFalse(GameAssistantAgent.request("model", "hello", emptyList()).getJSONArray("tools").toString().contains("search_debug_report"))
    }

    @Test fun fabricatedReportReadWithoutAttachmentIsRejectedBeforeToolsAreCalled() = runBlocking {
        var reads = 0
        val tools = object : GameAssistantAgent.Tools {
            override suspend fun read(name: String): String { reads++; return "sensitive" }
            override suspend fun prepare(proposal: ConfigProposal) = emptyList<String>()
            override fun hasBackup() = false
        }
        val replies = ArrayDeque(listOf(debugTestCall("read_debug_report"), AssistantProtocol.Reply("Select report", null)))
        var last: JSONObject? = null
        GameAssistantAgent.run("m", "hi", emptyList(), tools, { last = it; replies.removeFirst() }, {})
        assertEquals(0, reads)
        assertTrue(last!!.getJSONArray("input").toString().contains("No report attached"))
    }
}
