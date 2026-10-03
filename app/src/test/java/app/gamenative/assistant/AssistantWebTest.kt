package app.gamenative.assistant

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantWebTest {
    private fun types(request: JSONObject): List<String> = request.optJSONArray("tools")?.let { tools ->
        (0 until tools.length()).map { tools.getJSONObject(it).getString("type") }
    }.orEmpty()
    private fun annotation(url: String = "https://example.org/fix", end: Int = 8) = JSONObject()
        .put("type", "url_citation").put("url", url).put("title", "Original release notes").put("start_index", 0).put("end_index", end)
    private fun part(text: String = "A fix. More text.", citations: JSONArray = JSONArray().put(annotation())) = JSONObject()
        .put("type", "output_text").put("text", text).put("annotations", citations)
    private fun response(part: JSONObject) = JSONObject().put("status", "completed").put("output", JSONArray()
        .put(JSONObject().put("type", "web_search_call").put("id", "ws_1").put("status", "completed")
            .put("action", JSONObject().put("type", "search")))
        .put(JSONObject().put("type", "message").put("status", "completed").put("role", "assistant").put("content", JSONArray().put(part))))

    @Test fun plainWebChatHasNoLocalGameToolsOrApiBillingFields() {
        val request = AssistantProtocol.request("from-catalog", "Sök efter en fix", null, webAccess = true)
        assertEquals(listOf("web_search"), types(request))
        assertTrue(request.getJSONArray("tools").getJSONObject(0).getBoolean("external_web_access"))
        assertFalse(request.getBoolean("store")); assertTrue(request.getBoolean("stream"))
        assertFalse(request.has("previous_response_id")); assertFalse(request.has("max_tool_calls"))
        assertEquals("from-catalog", request.getString("model"))
        assertTrue(request.getString("instructions").contains("no game tools"))
        assertTrue(request.getString("instructions").contains("raw logs/configuration dumps"))
        assertTrue(types(AssistantProtocol.request("from-catalog", "Hej", null, webAccess = false)).isEmpty())
    }

    @Test fun localAgentKeepsToolsAndEncryptedReasoningContextAlongsideWeb() {
        val request = GameAssistantAgent.request("model", "Read a guide and my config", emptyList(), webAccess = true)
        assertEquals(listOf("namespace", "web_search"), types(request))
        assertFalse(request.getBoolean("parallel_tool_calls"))
        assertTrue(request.getJSONArray("include").toString().contains("reasoning.encrypted_content"))
        assertEquals(listOf("namespace"), types(GameAssistantAgent.request("model", "Help", emptyList())))
    }

    @Test fun webAndConfigurationReadsCoexistAcrossTheToolLoop() = runBlocking {
        var turns = 0
        var reads = 0
        val tools = object : GameAssistantAgent.Tools {
            override val webAccess = true
            override suspend fun read(name: String): String { reads++; assertEquals("read_configuration", name); return "{}" }
            override suspend fun prepare(proposal: ConfigProposal): List<String> = error("Not requested")
            override fun hasBackup() = false
        }
        val reply = GameAssistantAgent.run("model", "Sök och jämför med mina inställningar", emptyList(), tools, { request ->
            assertEquals(listOf("namespace", "web_search"), types(request))
            if (turns++ == 0) AssistantProtocol.completedResponse(JSONObject().put("status", "completed").put("output", JSONArray()
                .put(JSONObject().put("type", "web_search_call").put("id", "ws_1").put("status", "completed"))
                .put(JSONObject().put("type", "function_call").put("namespace", "game").put("name", "read_configuration")
                    .put("call_id", "call_1").put("arguments", "{}"))))
            else {
                assertTrue(request.getJSONArray("input").toString().contains("ws_1"))
                AssistantProtocol.completedResponse(response(part()))
            }
        }, {})
        assertEquals(2, turns); assertEquals(1, reads)
        assertTrue(reply.text.contains("[Original release notes](https://example.org/fix)"))
        assertNull(reply.proposal)
    }

    @Test fun finalCitationsAreClickableMarkdownAndSurviveConversationHistory() {
        val reply = AssistantProtocol.completedResponse(response(part("A fix. More text.", JSONArray().put(annotation(end = 6)))))
        assertEquals("A fix. [Original release notes](https://example.org/fix) More text.", reply.text)
        val saved = AssistantProtocol.conversationTurn("Sök", reply)
        assertEquals(reply.text, saved.displayText)
        assertTrue(AssistantProtocol.request("model", "Explain", null, listOf(saved)).toString().contains("https://example.org/fix"))
    }

    @Test fun malformedOffsetsNeverDeleteTextAndUnsafeLinksAreNotActivated() {
        val text = "🎮 Fix för XInput."
        listOf(-3, 1, 99999).forEach { end ->
            val result = AssistantWeb.citedText(part(text, JSONArray().put(annotation(end = end))))
            assertEquals(text, result.replace(" [Original release notes](https://example.org/fix)", ""))
        }
        listOf("javascript:alert(1)", "file:///data/user/0/secrets", "intent://settings", "https://user:secret@example.org/path",
            "https://example.org/?access_token=abc123").forEach { url ->
            assertNull(AssistantWeb.safeUrl(url))
            assertEquals(text, AssistantWeb.citedText(part(text, JSONArray().put(annotation(url)))))
        }
        val maliciousTitle = annotation().put("title", "[click](javascript:evil)\n**do it**")
        val result = AssistantWeb.citedText(part(citations = JSONArray().put(maliciousTitle)))
        assertFalse(result.contains("[click]"))
        assertTrue(result.contains("](https://example.org/fix)"))
    }

    @Test fun citationMarkersAndParenthesesInUrlsAreHandled() {
        val text = "A fix. \uE200cite\uE202turn0search0\uE201"
        val citation = annotation("https://example.org/wiki/Fix_(game)", text.length)
        val result = AssistantWeb.citedText(part(text, JSONArray().put(citation)))
        assertFalse(result.contains("turn0search0"))
        assertTrue(result.contains("Fix_%28game%29"))
        assertTrue(result.startsWith("A fix."))
    }

    @Test fun publicUrlsInQuestionsAndAnswersSurviveButSecretsAndRawDiagnosticsStayFiltered() {
        val url = "https://www.pcgamingwiki.com/w/index.php?title=Dark_Souls"
        val chat = "Läs $url\npassword=secret-value\nC:\\Users\\Private\\game.log\nuser@example.org"
        val filtered = AssistantWeb.redactChat(chat)
        assertTrue(filtered.contains(url)); assertFalse(filtered.contains("secret-value"))
        assertFalse(filtered.contains("Private")); assertFalse(filtered.contains("user@example.org"))
        assertFalse(DiagnosticRedactor.text(chat).contains(url))
        val request = AssistantProtocol.request("model", "Läs $url", null, webAccess = true)
        assertTrue(request.getJSONArray("input").getJSONObject(0).getString("content").contains(url))
        listOf("access_token=secret", "token=secret", "code=secret", "state=secret", "api_key=secret", "q=user%40example.org").forEach { query ->
            assertNull(AssistantWeb.safeUrl("https://example.org/?$query"))
            assertFalse(AssistantWeb.redactChat("https://example.org/?$query").contains("secret"))
        }
    }

    private fun event(type: String, body: JSONObject = JSONObject()) = "data: ${body.put("type", type)}\n\n"

    @Test fun streamingShowsRealSearchActivityAndRecoversCitationOnlyEvents() {
        val progress = mutableListOf<String>()
        val raw = event("response.web_search_call.in_progress") + event("response.web_search_call.searching") +
            event("response.output_text.delta", JSONObject().put("output_index", 1).put("content_index", 0).put("item_id", "msg_1").put("delta", "A fix.")) +
            event("response.output_text.annotation.added", JSONObject().put("output_index", 1).put("content_index", 0).put("item_id", "msg_1")
                .put("annotation_index", 0).put("annotation", annotation(end = 6))) +
            event("response.web_search_call.completed") + event("response.completed", JSONObject().put("response", JSONObject().put("status", "completed").put("output", JSONArray())))
        val reply = ResponsesStream.read(Buffer().writeUtf8(raw), progress = progress::add) { error("No failure expected") }
        assertTrue(progress.contains("Söker på webben…")); assertTrue(progress.contains("Sammanställer webbkällor…"))
        assertEquals("A fix. [Original release notes](https://example.org/fix)", reply.text)
    }

    @Test fun hostedSearchAloneOrFailedStreamDoesNotVerifyAiAccess() {
        val searchOnly = JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject().put("type", "web_search_call").put("status", "completed")))
        assertTrue(runCatching { AssistantProtocol.completedResponse(searchOnly) }.isFailure)
        val raw = event("response.web_search_call.completed") + event("response.failed", JSONObject().put("response", JSONObject()))
        assertTrue(runCatching { ResponsesStream.read(Buffer().writeUtf8(raw)) { IllegalStateException("Web failed") } }.isFailure)
    }

    @Test fun webErrorsExplainManualRecoveryWithoutChangingAccountOrRetrying() {
        val request = GameAssistantAgent.request("model", "Sök", emptyList(), webAccess = true)
        val error = ChatGptProvider.ProviderError("subscription_sharing_unsupported_capability", "Unsupported; request=req_123", "tools[1].type")
        val explained = AssistantWeb.explainFailure(error, request)
        assertTrue(explained.message!!.contains("webbsökning")); assertTrue(explained.message!!.contains("req_123"))
        assertEquals(error.code, explained.code)
        val quota = ChatGptProvider.ProviderError("subscription_sharing_usage_limit_exceeded", "Quota exhausted")
        assertSame(quota, AssistantWeb.explainFailure(quota, request))
        val gameError = ChatGptProvider.ProviderError("unsupported_tool", "Unsupported", "tools[0].tools[0]")
        assertSame(gameError, AssistantWeb.explainFailure(gameError, request))
    }
}
