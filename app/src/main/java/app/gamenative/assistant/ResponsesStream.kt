package app.gamenative.assistant

import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/** Partial text or a tool-call delta is never proof of a successful request. */
object ResponsesStream {
    fun read(source: BufferedSource, requestId: String? = null, progress: (String) -> Unit = {}, failure: (JSONObject) -> Exception): AssistantProtocol.Reply {
        val event = StringBuilder()
        val output = StreamOutput()
        var total = 0
        var events = 0
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
                events++
                when (json.optString("type")) {
                    "response.web_search_call.in_progress", "response.web_search_call.searching" -> progress("Söker på webben…")
                    "response.web_search_call.completed" -> progress("Sammanställer webbkällor…")
                    "response.completed" -> {
                        val response = json.getJSONObject("response")
                        try {
                            return AssistantProtocol.completedResponse(output.complete(response))
                        } catch (e: IllegalArgumentException) {
                            // Counts and opaque IDs only: never expose raw events, prompts or reasoning.
                            throw IllegalStateException("${e.message}. Events=$events, ${output.summary()}, " +
                                "response=${safeId(response.optString("id"))}, request=${safeId(requestId)}", e)
                        }
                    }
                    "response.failed", "response.incomplete", "error" -> throw failure(json.optJSONObject("response") ?: json)
                    else -> output.accept(json)
                }
            }
        }
        error("Stream ended without response.completed. No AI access was verified and no proposal was accepted.")
    }

    private fun safeId(value: String?): String = value?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,160}")) } ?: "unavailable"

    /** Text may arrive before the terminal envelope. Tools require a complete output_item.done. */
    private class StreamOutput {
        private val items = sortedMapOf<Int, JSONObject>()
        private val text = sortedMapOf<Int, java.util.SortedMap<Int, StringBuilder>>()
        private val ids = mutableMapOf<Int, String>()
        private val annotations = mutableMapOf<Pair<Int, Int>, java.util.SortedMap<Int, JSONObject>>()

        fun accept(event: JSONObject) {
            when (event.optString("type")) {
                "response.output_text.annotation.added" -> {
                    val output = index(event, "output_index")
                    bind(output, event.getString("item_id"))
                    val key = output to index(event, "content_index")
                    annotations.getOrPut(key) { sortedMapOf() }[index(event, "annotation_index")] = event.getJSONObject("annotation")
                }
                "response.output_item.added", "response.output_item.done" -> {
                    val index = index(event, "output_index")
                    val item = event.getJSONObject("item")
                    bind(index, item.getString("id"))
                    if (event.getString("type") == "response.output_item.done") items[index] = item
                }
                "response.output_text.delta", "response.output_text.done" -> {
                    val index = index(event, "output_index")
                    bind(index, event.getString("item_id"))
                    val parts = text.getOrPut(index) { sortedMapOf() }
                    val content = index(event, "content_index")
                    if (event.getString("type") == "response.output_text.done") {
                        parts[content] = StringBuilder(event.getString("text"))
                    } else parts.getOrPut(content) { StringBuilder() }.append(event.getString("delta"))
                }
            }
        }

        fun complete(response: JSONObject): JSONObject {
            // A populated terminal output is authoritative; don't append earlier drafts or duplicates.
            if ((response.optJSONArray("output")?.length() ?: 0) > 0) return response
            val collected = JSONArray()
            (items.keys + text.keys).sorted().forEach { index ->
                val item = items[index] ?: JSONObject().put("type", "message").put("role", "assistant")
                    .put("status", "completed").put("id", ids[index]).put("content", JSONArray().apply {
                        text[index]?.forEach { (contentIndex, content) -> put(JSONObject().put("type", "output_text").put("text", content.toString())
                            .put("annotations", JSONArray(annotations[index to contentIndex]?.values?.toList() ?: emptyList<JSONObject>()))) }
                    })
                collected.put(item)
            }
            return JSONObject(response.toString()).put("output", collected)
        }

        fun summary() = "text parts=${text.values.sumOf { it.size }}, done items=${items.size}"

        private fun index(event: JSONObject, key: String): Int {
            val value = event.get(key)
            require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 0..4095) { "Invalid stream index" }
            return value.toInt()
        }

        private fun bind(index: Int, id: String) {
            require(id.isNotBlank() && (ids[index] == null || ids[index] == id)) { "Mismatched stream item" }
            ids[index] = id
        }
    }
}
