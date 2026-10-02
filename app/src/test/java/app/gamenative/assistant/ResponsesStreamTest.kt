package app.gamenative.assistant

import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponsesStreamTest {
    private fun event(type: String, fields: String) = "data: ${JSONObject(fields).put("type", type)}\n\n"
    private fun completed(output: String = "[]") = event("response.completed", """{"response":{"id":"resp_test","status":"completed","output":$output}}""")
    private fun delta(text: String, index: Int = 0, content: Int = 0) = event("response.output_text.delta",
        JSONObject().put("output_index", index).put("content_index", content).put("item_id", "msg_$index").put("delta", text).toString())
    private fun read(raw: String) = ResponsesStream.read(Buffer().writeUtf8(raw)) { IllegalStateException("server failure") }
    private fun reject(raw: String) { assertTrue(runCatching { read(raw) }.isFailure) }
    private fun message(text: String) = JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
        .put("id", "msg_0").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))
    private val tool = """{"id":"fc_test","type":"function_call","status":"completed","namespace":"game","name":"propose_configuration","arguments":"{\"fps\":30,\"screenSize\":null,\"reason\":\"Test cap\"}"}"""

    @Test fun collectsTextBeforeEmptyCompletedEnvelope() {
        assertEquals("Hello world", read(delta("Hello ") + delta("world") + completed()).text)
    }
    @Test fun finalTextReplacesDeltasWithoutDuplicatingThem() {
        val done = event("response.output_text.done", """{"output_index":0,"content_index":0,"item_id":"msg_0","text":"Hello world"}""")
        assertEquals("Hello world", read(delta("Hello ") + done + completed()).text)
    }
    @Test fun fullTerminalOutputTakesPrecedence() {
        assertEquals("Authoritative", read(delta("Draft") + completed("[${message("Authoritative")}]")).text)
    }
    @Test fun completedItemTakesPrecedenceOverItsDeltas() {
        val done = event("response.output_item.done", """{"output_index":0,"item":${message("Final")}}""")
        assertEquals("Final", read(delta("Draft") + done + completed()).text)
    }
    @Test fun preservesContentAndOutputOrder() {
        val stream = delta("Second", 1) + delta("Part B", 0, 1) + delta("Part A", 0, 0)
        assertEquals("Part A\n\nPart B\n\nSecond", read(stream + completed()).text)
    }
    @Test fun acceptsDoneToolOnlyAfterResponseCompletion() {
        val done = event("response.output_item.done", """{"output_index":0,"item":$tool}""")
        reject(done)
        assertEquals(30, read(done + completed()).proposal?.fps)
    }
    @Test fun neverPromotesPartialToolArgumentsToAProposal() {
        val added = event("response.output_item.added", """{"output_index":0,"item":$tool}""")
        reject(added + completed())
        val args = event("response.function_call_arguments.done", """{"output_index":0,"arguments":"{}"}""")
        reject(args + completed())
    }
    @Test fun failedIncompleteAndTruncatedStreamsDiscardAllOutput() {
        val text = delta("Looks successful")
        listOf("response.failed", "response.incomplete", "error").forEach { reject(text + event(it, "{}")) }
        reject(text)
        reject(text + "data: [DONE]\n\n")
    }
    @Test fun rejectsUnexpectedAndIncompleteDoneTools() {
        listOf(tool.replace("propose_configuration", "shell"), tool.replace("completed", "in_progress")).forEach {
            reject(event("response.output_item.done", """{"output_index":0,"item":$it}""") + completed())
        }
    }
    @Test fun ignoresReasoningTextAndReportsEmptyResponseWithSafeCounts() {
        val reasoning = event("response.reasoning_text.delta", """{"delta":"private reasoning"}""")
        val failure = runCatching { read(reasoning + completed()) }.exceptionOrNull()!!
        assertTrue(failure.message.orEmpty().contains("resp_test"))
        assertFalse(failure.message.orEmpty().contains("private reasoning"))
    }
    @Test fun rejectsMismatchedItemIdentityAndNegativeIndexes() {
        reject(delta("A") + delta("B").replace("msg_0", "msg_other") + completed())
        reject(delta("A", -1) + completed())
    }
    @Test fun whitespaceDoesNotVerifyAiAccess() { reject(delta("   ") + completed()) }
}
