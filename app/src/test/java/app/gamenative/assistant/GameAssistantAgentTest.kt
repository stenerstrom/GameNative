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
        override var fileAccess = false
        var filePrepared = false
        override var modAccess = false
        var modPrepared = false
        override suspend fun readModTool(name: String, arguments: JSONObject): String { reads += name; return "{}" }
        override suspend fun prepareMod(arguments: JSONObject): ModActionPreview {
            modPrepared = true
            return ModActionPreview("Installera testmod", "Test", listOf("dinput8.dll → dinput8.dll"), emptyList(), 1, true)
        }
        override suspend fun readFileTool(name: String, arguments: JSONObject): String { reads += name; return "{}" }
        override suspend fun prepareFile(proposal: FileEditProposal): GameTextFiles.Preview {
            filePrepared = true
            return GameTextFiles.Preview("Spelmapp/settings.ini", "- FPS=60\n+ FPS=30", proposal.reason)
        }
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
    private val fileEdit = """{"file_id":"5f2dba31-f460-4cb5-9f60-129ab181f26b","replacements":[{"old_text":"FPS=60","new_text":"FPS=30"}],"reason":"Test FPS cap"}"""
    private val modAction = """{"mod_id":"local_test","action":"install","plan_id":"local_plan","reason":"Test"}"""

    @Test fun combinedInputReadAllowsOneReviewedProposalWithoutThreeRedundantReads() = runBlocking {
        val tools = Tools()
        val replies = ArrayDeque(listOf(call("read_input_route"), call("propose_settings", proposal), AssistantProtocol.Reply("Granska förslaget", null)))
        var calls = 0
        val reply = GameAssistantAgent.run("m", "Ingen input", emptyList(), tools, { calls++; replies.removeFirst() }, {})
        assertEquals(3, calls)
        assertEquals(listOf("read_input_route"), tools.reads)
        assertNotNull(reply.proposal)
    }

    @Test fun failedCombinedReadDoesNotAuthorizeAProposal() = runBlocking {
        var prepared = false
        val tools = object : GameAssistantAgent.Tools {
            override suspend fun read(name: String): String = error("Game launch changed")
            override suspend fun prepare(proposal: ConfigProposal): List<String> { prepared = true; return emptyList() }
            override fun hasBackup() = false
        }
        val replies = ArrayDeque(listOf(call("read_input_route"), call("propose_settings", proposal), AssistantProtocol.Reply("Läs igen", null)))
        val reply = GameAssistantAgent.run("m", "Ingen input", emptyList(), tools, { replies.removeFirst() }, {})
        assertFalse(prepared); assertNull(reply.proposal)
    }

    @Test fun modToolsNeedTheirOwnPermissionAndDoNotAdvertiseUnconnectedCapabilities() = runBlocking {
        val tools = Tools()
        val request = GameAssistantAgent.request("m", "help", emptyList())
        assertFalse(request.getJSONArray("tools").toString().contains("read_mods"))
        var step = 0
        val reply = GameAssistantAgent.run("m", "help", emptyList(), tools, { next ->
            if (step++ == 0) call("propose_mod_action", modAction) else {
                assertTrue(next.getJSONArray("input").toString().contains("Mod access is disabled"))
                AssistantProtocol.Reply("Enable mod access", null)
            }
        }, {})
        assertFalse(tools.modPrepared)
        assertNull(reply.modProposal)
    }

    @Test fun modProposalWaitsForFinalReplyAndBlocksOtherActionsInSameTurn() = runBlocking {
        val tools = Tools().apply { modAccess = true; fileAccess = true }
        val replies = ArrayDeque(listOf(call("read_mods"), call("inspect_mod", """{"mod_id":"local_test"}"""),
            call("propose_mod_action", modAction), call("propose_file_edit", fileEdit), AssistantProtocol.Reply("Review this mod", null)))
        var last: JSONObject? = null
        val reply = GameAssistantAgent.run("m", "install", emptyList(), tools, {
            last = JSONObject(it.toString()); replies.removeFirst()
        }, {})
        assertTrue(last!!.getJSONArray("tools").toString().contains("propose_mod_action"))
        assertTrue(last!!.getJSONArray("input").toString().contains("an action is already awaiting approval"))
        assertTrue(tools.modPrepared)
        assertFalse(tools.filePrepared)
        assertTrue(reply.modProposal!!.needsLoaderApproval)
        assertNull(reply.proposal)
        assertNull(reply.fileProposal)
        var step = 0
        assertTrue(runCatching { GameAssistantAgent.run("m", "install", emptyList(), tools, {
            if (step++ == 0) call("propose_mod_action", modAction) else error("response.failed")
        }, {}) }.isFailure)
        assertTrue(runCatching { GameAssistantAgent.run("m", "install", emptyList(), tools, {
            AssistantProtocol.Reply("bypass tools", null, modProposal = reply.modProposal)
        }, {}) }.isFailure)
    }

    @Test fun fileToolsAreOnlyAdvertisedAndDispatchedWithSeparatePermission() = runBlocking {
        val tools = Tools()
        assertFalse(GameAssistantAgent.request("m", "help", emptyList()).getJSONArray("tools").toString().contains("list_game_files"))
        var step = 0
        val reply = GameAssistantAgent.run("m", "help", emptyList(), tools, { request ->
            if (step++ == 0) call("propose_file_edit", fileEdit) else {
                assertTrue(request.getJSONArray("input").toString().contains("File access is disabled"))
                AssistantProtocol.Reply("Enable file access", null)
            }
        }, {})
        assertFalse(tools.filePrepared)
        assertNull(reply.fileProposal)
    }

    @Test fun fileEditsStageOnlyOneActionAndRequireCompletedFinalResponse() = runBlocking {
        val tools = Tools().apply { fileAccess = true }
        val replies = ArrayDeque(listOf(call("list_game_files", """{"query":"settings"}"""),
            call("read_game_file", """{"file_id":"5f2dba31-f460-4cb5-9f60-129ab181f26b"}"""),
            call("propose_file_edit", fileEdit), call("read_configuration"), AssistantProtocol.Reply("Review the file edit", null)))
        var lastRequest: JSONObject? = null
        val reply = GameAssistantAgent.run("m", "edit fps", emptyList(), tools, { request ->
            lastRequest = JSONObject(request.toString()); replies.removeFirst()
        }, {})
        assertTrue(lastRequest!!.getJSONArray("tools").toString().contains("list_game_files"))
        assertTrue(lastRequest!!.getJSONArray("input").toString().contains("an action is already awaiting approval"))
        assertEquals(listOf("list_game_files", "read_game_file"), tools.reads)
        assertEquals("Spelmapp/settings.ini", reply.fileProposal!!.path)
        assertNull(reply.proposal)
        assertFalse(reply.restoreRequested)
        var step = 0
        assertTrue(runCatching { GameAssistantAgent.run("m", "edit", emptyList(), tools, {
            if (step++ == 0) call("propose_file_edit", fileEdit) else error("response.failed")
        }, {}) }.isFailure)
    }

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
