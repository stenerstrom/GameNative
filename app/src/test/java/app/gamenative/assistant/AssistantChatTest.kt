package app.gamenative.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantChatTest {
    @Test fun plainChatNeedsNeitherDiagnosticsNorTools() {
        val request = AssistantProtocol.request("catalog-model", "Hej!", null)
        assertEquals(1, request.getJSONArray("input").length())
        assertEquals("Hej!", request.getJSONArray("input").getJSONObject(0).getString("content"))
        assertFalse(request.has("tools"))
        assertFalse(request.has("parallel_tool_calls"))
        assertFalse(request.getBoolean("store"))
        assertTrue(request.getBoolean("stream"))
    }

    @Test fun followsUpWithBoundedTextHistoryInOrder() {
        val history = (1..12).map { AssistantProtocol.ChatTurn("Question $it", "Answer $it") }
        val request = AssistantProtocol.request("catalog-model", "Follow up", null, history)
        val input = request.getJSONArray("input")
        assertEquals(17, input.length())
        assertEquals("Question 5", input.getJSONObject(0).getString("content"))
        assertEquals("assistant", input.getJSONObject(1).getString("role"))
        assertEquals("Answer 12", input.getJSONObject(15).getString("content"))
        assertEquals("Follow up", input.getJSONObject(16).getString("content"))
        assertFalse(request.has("previous_response_id"))
        assertFalse(request.has("conversation"))
    }

    @Test fun explicitAttachmentEnablesReviewedProposalToolsEvenWithoutALog() {
        val request = AssistantProtocol.request("catalog-model", "Help", """{"configuration":{"screenSize":"1280x720"},"log":null}""")
        assertTrue(request.has("tools"))
        assertEquals("namespace", request.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertTrue(request.getJSONArray("input").getJSONObject(1).getString("content").contains("1280x720"))
    }

    @Test fun filtersSecretsInMessageHistoryAndAttachment() {
        val history = listOf(AssistantProtocol.ChatTurn("password=old-secret", "Authorization: Bearer old-token"))
        val request = AssistantProtocol.request("catalog-model", "password=new-secret", "refresh_token=secret-attachment", history).toString()
        listOf("old-secret", "old-token", "new-secret", "secret-attachment").forEach { assertFalse(request.contains(it)) }
    }

    @Test fun proposalIsOnlyATextSummaryInLaterConversation() {
        val reply = AssistantProtocol.Reply("Try a cap", ConfigProposal(30, null, "Reduce load"))
        val turn = AssistantProtocol.conversationTurn("Help", reply)
        assertTrue(turn.assistant.contains("not applied"))
        val request = AssistantProtocol.request("catalog-model", "Why?", null, listOf(turn))
        assertFalse(request.has("tools"))
        assertFalse(request.toString().contains("function_call"))
    }

    @Test fun boundsRememberedRepliesAndRejectsInvalidInput() {
        val turn = AssistantProtocol.conversationTurn("Hi", AssistantProtocol.Reply("x".repeat(20_000), null))
        assertEquals(AssistantProtocol.HISTORY_REPLY_CHARS, turn.assistant.length)
        assertTrue(runCatching { AssistantProtocol.request("model", " ", null) }.isFailure)
        assertTrue(runCatching { AssistantProtocol.request("model", "Hi", "") }.isFailure)
    }
}
