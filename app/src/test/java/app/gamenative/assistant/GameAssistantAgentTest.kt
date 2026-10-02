package app.gamenative.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GameAssistantAgentTest {
    private class Tools : GameAssistantAgent.Tools {
        val reads = mutableListOf<String>()
        var prepared: ConfigProposal? = null
        var backup = false
        override suspend fun read(name: String): String { reads += name; return "{\"inputApi\":\"DINPUT\"}" }
        override suspend fun prepare(proposal: ConfigProposal): List<String> { prepared = proposal; return proposal.changes() }
        override fun hasBackup() = backup
    }
    private fun call(name: String, args: String = "{}", id: String = "call_$name"): AssistantProtocol.Reply {
        val item = JSONObject().put("type", "function_call").put("namespace", "game").put("name", name)
            .put("call_id", id).put("arguments", args)
        return AssistantProtocol.completedResponse(JSONObject().put("status", "completed").put("output", JSONArray().put(item)))
    }
    private val proposal = """{"changes":[{"setting":"inputApi","value":"BOTH"},{"setting":"sdlControllerAPI","value":"true"}],"reason":"Test controller routing"}"""

    @Test fun investigatesThenStagesProposalAndFeedsMatchingResultsBackBeforeFinalAnswer() = runBlocking {
        val tools = Tools()
        val requests = mutableListOf<JSONObject>()
        val progress = mutableListOf<String>()
        val replies = ArrayDeque(listOf(call("read_configuration"), call("inspect_controllers"), call("propose_settings", proposal), AssistantProtocol.Reply("Review these settings", null)))
        val reply = GameAssistantAgent.run("model", "Help with controller", emptyList(), tools, {
            requests += JSONObject(it.toString()); replies.removeFirst()
        }, progress::add)
        assertEquals(listOf("read_configuration", "inspect_controllers"), tools.reads)
        assertEquals("BOTH", reply.proposal!!.settings["inputApi"])
        assertEquals("Review these settings", reply.text)
        assertTrue(progress.any { it.contains("handkontroller") })
        val items = requests.last().getJSONArray("input")
        val resultItems = (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optString("type") == "function_call_output" }
        assertEquals(listOf("call_read_configuration", "call_inspect_controllers", "call_propose_settings"), resultItems.map { it.getString("call_id") })
        assertFalse(JSONObject(resultItems.last().getString("output")).getBoolean("applied"))
        requests.forEach { assertFalse(it.has("previous_response_id")); assertFalse(it.getBoolean("store")); assertTrue(it.getBoolean("stream")) }
    }

    @Test fun cannotProposeWithoutReadingAndCanRecoverThroughToolError() = runBlocking {
        val tools = Tools()
        var count = 0
        GameAssistantAgent.run("m", "help", emptyList(), tools, { request ->
            when (count++) {
                0 -> call("propose_settings", proposal)
                else -> {
                    assertTrue(request.getJSONArray("input").toString().contains("Read configuration before proposing"))
                    AssistantProtocol.Reply("I need to read settings first", null)
                }
            }
        }, {})
        assertNull(tools.prepared)
    }

    @Test fun invalidSettingReturnsErrorWithoutAcceptingAnyPartialChange() = runBlocking {
        val tools = Tools()
        var step = 0
        val reply = GameAssistantAgent.run("m", "help", emptyList(), tools, { request ->
            when (step++) {
                0 -> call("read_configuration")
                1 -> call("propose_settings", proposal.replace("sdlControllerAPI", "execArgs"))
                else -> {
                    assertTrue(request.getJSONArray("input").toString().contains("Unsupported setting"))
                    AssistantProtocol.Reply("That setting is not supported", null)
                }
            }
        }, {})
        assertNull(tools.prepared)
        assertNull(reply.proposal)
    }

    @Test fun restoreOnlyRequestsApprovalAndRequiresExistingBackup() = runBlocking {
        val tools = Tools().apply { backup = true }
        val replies = ArrayDeque(listOf(call("request_restore"), AssistantProtocol.Reply("Confirm undo below", null)))
        val reply = GameAssistantAgent.run("m", "undo", emptyList(), tools, { replies.removeFirst() }, {})
        assertTrue(reply.restoreRequested)
        assertTrue(tools.backup)
        assertNull(reply.proposal)
    }

    @Test fun finiteLoopStopsRepeatedRequestsWithoutAcceptingAPartialProposal() = runBlocking {
        val tools = Tools()
        var requests = 0
        val result = runCatching { GameAssistantAgent.run("m", "help", emptyList(), tools, {
            call("read_configuration", id = "call_${requests++}")
        }, {}) }
        assertTrue(result.isFailure)
        assertEquals(8, requests)
        assertNull(tools.prepared)
    }

    @Test fun failedFinalResponseDoesNotReturnStagedProposal() = runBlocking {
        val tools = Tools()
        var step = 0
        assertTrue(runCatching { GameAssistantAgent.run("m", "help", emptyList(), tools, {
            when (step++) {
                0 -> call("read_configuration")
                1 -> call("propose_settings", proposal)
                else -> throw IllegalStateException("response.failed")
            }
        }, {}) }.isFailure)
        // A preview can exist locally, but the caller receives no proposal or success to display/apply.
        assertNotNull(tools.prepared)
    }

    @Test fun cancellationPropagatesWithoutBeingConvertedToToolOutput() = runBlocking {
        val tools = object : GameAssistantAgent.Tools {
            override suspend fun read(name: String): String = throw CancellationException("cancel")
            override suspend fun prepare(proposal: ConfigProposal) = error("should not prepare")
            override fun hasBackup() = false
        }
        val result = runCatching { GameAssistantAgent.run("m", "help", emptyList(), tools, { call("read_configuration") }, {}) }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test fun proposalCatalogRejectsUnknownPathsDuplicateKeysAndInvalidBooleanTypes() {
        listOf(
            """{"changes":[{"setting":"screenSize","value":"../../file"}],"reason":"x"}""",
            """{"changes":[{"setting":"sdlControllerAPI","value":true}],"reason":"x"}""",
            """{"changes":[{"setting":"sdlControllerAPI","value":"true"},{"setting":"sdlControllerAPI","value":"false"}],"reason":"x"}""",
            """{"changes":[{"setting":"envVars","value":"ANY=1"}],"reason":"x"}"""
        ).forEach { assertTrue(runCatching { GameSettingCatalog.parseProposal(JSONObject(it)) }.isFailure) }
    }
}
