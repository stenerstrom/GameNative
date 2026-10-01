package app.gamenative.assistant

import okio.BufferedSource
import org.json.JSONObject

/** Partial text or a tool-call delta is never proof of a successful request. */
object ResponsesStream {
    fun read(source: BufferedSource, failure: (JSONObject) -> Exception): AssistantProtocol.Reply {
        val event = StringBuilder()
        var total = 0
        while (!source.exhausted()) {
            val line = source.readUtf8LineStrict(262_144)
            total += line.length
            check(total <= 2_000_000) { "AI response exceeded prototype limit" }
            if (line.startsWith("data:")) event.append(line.substring(5).trimStart()).append('\n')
            if (line.isEmpty() && event.isNotEmpty()) {
                val payload = event.toString().trim()
                event.clear()
                if (payload == "[DONE]") break
                val json = JSONObject(payload)
                when (json.optString("type")) {
                    "response.completed" -> return AssistantProtocol.completedResponse(json.getJSONObject("response"))
                    "response.failed", "response.incomplete", "error" -> throw failure(json.optJSONObject("response") ?: json)
                }
            }
        }
        error("Stream ended without response.completed. No AI access was verified and no proposal was accepted.")
    }
}
