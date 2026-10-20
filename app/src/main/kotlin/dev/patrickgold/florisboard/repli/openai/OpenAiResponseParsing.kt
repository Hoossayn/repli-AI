package dev.patrickgold.florisboard.repli.openai

import org.json.JSONArray
import org.json.JSONObject

/** Token counts reported by the API; the only usage data Repli keeps (for the Diagnostics card). */
data class OpenAiUsage(val inputTokens: Int, val outputTokens: Int)

/** One bubble read from a screenshot: frame index, speaker, body text and a 0..1000 box. */
data class ParsedBubble(val frameIndex: Int, val fromMe: Boolean, val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

data class ParsedCapture(val contactName: String?, val bubbles: List<ParsedBubble>)

/** The finished Responses API object plus how it arrived. */
data class ResponseEnvelope(val response: JSONObject, val usage: OpenAiUsage?)

/**
 * Turns Responses API output into Repli's bounded shapes. Ported from openai.mjs so the same
 * validation that the backend applied now runs on the phone.
 */
object OpenAiResponseParsing {
    private const val MAX_TURN_CHARACTERS = 2_000
    private val genericNames = setOf("whatsapp", "telegram", "messenger", "messages", "chat", "online", "typing", "you")
    private val uiActions = setOf("message", "add contact", "view contact", "create contact", "create new contact")

    private val timestamp = Regex("(?i)^\\d{1,2}:\\d{2}(?:\\s?[ap]m)?(?:\\s*(?:[✓✔☑√]{1,4}|[vny/\\\\|!li]{1,4}))?$")
    private val receipt = Regex("(?i)^(?:[✓✔☑√]{1,4}|[v/\\\\|]|[vny/\\\\|!li]{2,4})$")
    private val numericDate = Regex("^\\d{1,4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,4}$")
    private val namedDate = Regex(
        "(?i)^(?:(?:\\d{1,2}\\s+)?(?:january|february|march|april|may|june|july|august|september|october|november|december)\\s+\\d{4}|" +
            "(?:january|february|march|april|may|june|july|august|september|october|november|december)\\s+\\d{1,2},?\\s+\\d{4})$",
    )
    private val suffix = Regex("(?i)\\s+\\d{1,2}:\\d{2}(?:\\s?[ap]m)?\\s*(?:[✓✔☑√]{1,4}|[vny/\\\\|!li]{1,4})$")
    private val letterOrDigit = Regex("[\\p{L}\\p{N}]")
    private val letter = Regex("\\p{L}")
    private val whitespace = Regex("\\s+")
    private val capitalizedWord = Regex("^\\p{Lu}[\\p{L}'’\\-]*$")

    /**
     * Reads either a plain JSON response object or a server-sent-event stream of one, and
     * returns the final `response` object. Throws [OpenAiException] when the stream reports
     * failure, the response is incomplete, or the model refused.
     */
    fun finishedResponse(body: String): ResponseEnvelope {
        val trimmed = body.trimStart()
        val response = if (trimmed.startsWith("{")) {
            JSONObject(trimmed)
        } else {
            finalResponseFromStream(trimmed)
        }
        val usage = parseUsage(response.optJSONObject("usage"))
        when (response.optString("status")) {
            "completed" -> Unit
            "incomplete" -> {
                val reason = response.optJSONObject("incomplete_details")?.optString("reason")
                throw OpenAiException(if (reason == "content_filter") OpenAiFailure.REFUSED else OpenAiFailure.INCOMPLETE, "Response incomplete: $reason")
            }
            "failed" -> throw OpenAiException(OpenAiFailure.UNAVAILABLE, "Response failed").withApiError(response.optJSONObject("error"))
            else -> throw OpenAiException(OpenAiFailure.INVALID_RESPONSE, "Unexpected response status")
        }
        val output = response.optJSONArray("output") ?: JSONArray()
        for (index in 0 until output.length()) {
            val content = output.optJSONObject(index)?.optJSONArray("content") ?: continue
            for (item in 0 until content.length()) {
                if (content.optJSONObject(item)?.optString("type") == "refusal") {
                    throw OpenAiException(OpenAiFailure.REFUSED, "Model refused")
                }
            }
        }
        return ResponseEnvelope(response, usage)
    }

    private fun finalResponseFromStream(stream: String): JSONObject {
        var completed: JSONObject? = null
        var failure: OpenAiException? = null
        // The plan route delivers the text in response.output_text.delta events; the final
        // response.completed object may omit output content, so the deltas are kept as well.
        val deltas = StringBuilder()
        var doneText: String? = null
        val data = StringBuilder()
        fun flush() {
            if (data.isEmpty()) return
            val raw = data.toString()
            data.setLength(0)
            if (raw == "[DONE]") return
            val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
            when (event.optString("type")) {
                "response.output_text.delta" -> deltas.append(event.optString("delta"))
                "response.output_text.done" -> doneText = event.optString("text").takeIf(String::isNotEmpty)
                "response.completed", "response.incomplete" -> completed = event.optJSONObject("response")
                "response.failed" -> {
                    val response = event.optJSONObject("response")
                    completed = response
                    failure = OpenAiException(OpenAiFailure.UNAVAILABLE, "Response failed").withApiError(response?.optJSONObject("error"))
                }
                "error" -> failure = OpenAiException(OpenAiFailure.UNAVAILABLE, "Stream error").withApiError(event.optJSONObject("error") ?: event)
            }
        }
        for (line in stream.lineSequence()) {
            when {
                line.isEmpty() -> flush()
                line.startsWith("data:") -> data.append(line.substring(5).trimStart())
                // event:, id:, retry: and comment lines carry nothing Repli needs.
            }
        }
        flush()
        failure?.let { throw it }
        val response = completed ?: throw OpenAiException(OpenAiFailure.INVALID_RESPONSE, "Stream ended without a completed response")
        if (!hasOutputText(response)) {
            val streamed = doneText ?: deltas.toString()
            if (streamed.isNotEmpty()) response.put("output_text", streamed)
        }
        return response
    }

    private fun hasOutputText(response: JSONObject): Boolean = runCatching { outputText(response) }.isSuccess

    fun outputText(response: JSONObject): String {
        response.optString("output_text").takeIf { response.has("output_text") && it.isNotEmpty() }?.let { return it }
        val output = response.optJSONArray("output") ?: JSONArray()
        for (index in 0 until output.length()) {
            val content = output.optJSONObject(index)?.optJSONArray("content") ?: continue
            for (item in 0 until content.length()) {
                val part = content.optJSONObject(item) ?: continue
                if (part.optString("type") == "output_text") return part.optString("text")
            }
        }
        throw OpenAiException(OpenAiFailure.INVALID_RESPONSE, "Response had no output text")
    }

    /** Exactly three distinct, non-empty candidates of at most [maxCharacters]. */
    fun candidates(response: JSONObject, maxCharacters: Int): List<String> {
        val json = parseObject(outputText(response))
        val array = json.optJSONArray("candidates") ?: throw invalid("Response omitted candidates")
        if (array.length() != 3) throw invalid("Response did not contain exactly three candidates")
        val values = List(3) { index ->
            val text = (array.opt(index) as? String)?.trim() ?: throw invalid("Candidate was not a string")
            if (text.isEmpty() || text.length > maxCharacters) throw invalid("Candidate was outside its allowed length")
            text
        }
        if (values.distinct().size != 3) throw invalid("Candidates were not distinct")
        return values
    }

    /** Contract v3 visual bubbles for [frameCount] screenshots, sorted by page then vertical position. */
    fun visualCapture(response: JSONObject, frameCount: Int): ParsedCapture {
        require(frameCount in 1..ReplyPromptBuilder.MAX_VISION_IMAGES)
        val json = parseObject(outputText(response))
        val contactName = contactName(json.opt("contact_name"))
        val array = json.optJSONArray("bubbles") ?: throw invalid("Response omitted bubbles")
        if (array.length() > ReplyPromptBuilder.MAX_VISUAL_BUBBLES) throw invalid("Too many bubbles")
        val bubbles = ArrayList<ParsedBubble>()
        for (index in 0 until array.length()) {
            val bubble = array.optJSONObject(index) ?: throw invalid("Bubble was not an object")
            val frame = bubble.optInt("frame_index", -1)
            val speaker = bubble.optString("speaker")
            val rawText = bubble.opt("text") as? String ?: throw invalid("Bubble text was not a string")
            val box = bubble.optJSONObject("box") ?: throw invalid("Bubble had no box")
            if (frame !in 0 until frameCount || speaker !in setOf("me", "them")) throw invalid("Bubble was invalid")
            val left = box.optInt("left", -1); val top = box.optInt("top", -1)
            val right = box.optInt("right", -1); val bottom = box.optInt("bottom", -1)
            if (listOf(left, top, right, bottom).any { it !in 0..1000 } || right <= left || bottom <= top) throw invalid("Bubble bounds were invalid")
            val text = cleanVisionText(rawText, allowStandaloneTime = true) ?: continue
            bubbles += ParsedBubble(frame, speaker == "me", text, left, top, right, bottom)
        }
        bubbles.sortWith(compareBy<ParsedBubble> { it.frameIndex }.thenBy { it.top })
        return ParsedCapture(contactName, removeUiArtifacts(bubbles))
    }

    fun parseUsage(value: JSONObject?): OpenAiUsage? {
        value ?: return null
        val input = value.optInt("input_tokens", -1)
        val output = value.optInt("output_tokens", -1)
        return if (input >= 0 && output >= 0) OpenAiUsage(input, output) else null
    }

    /** Accepts a bare JSON object or one wrapped in prose/code fences (plain-text fallback mode). */
    internal fun parseObject(text: String): JSONObject {
        runCatching { return JSONObject(text) }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw invalid("Response text was not JSON")
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrElse { throw invalid("Response text was not JSON") }
    }

    internal fun contactName(raw: Any?): String? {
        val value = raw as? String ?: return null
        val cleaned = value.replace(whitespace, " ").trim()
        if (cleaned.length > 80) throw invalid("Contact name was too long")
        return cleaned.takeIf { it.isNotEmpty() && letterOrDigit.containsMatchIn(it) && it.lowercase() !in genericNames }
    }

    internal fun cleanVisionText(value: String, allowStandaloneTime: Boolean = false): String? {
        val lines = value.split('\n').map { it.trimEnd('\r').replace(whitespace, " ").trim() }
            .filter { line ->
                line.isNotEmpty() && (allowStandaloneTime || !timestamp.matches(line)) && !receipt.matches(line) &&
                    !numericDate.matches(line) && !namedDate.matches(line)
            }
            .map { it.replace(suffix, "").trim() }
            .filter(String::isNotEmpty)
        val text = lines.joinToString(" ").replace(whitespace, " ").trim()
        val meaningful = if (allowStandaloneTime) letterOrDigit else letter
        return text.takeIf { it.isNotEmpty() && it.length <= MAX_TURN_CHARACTERS && meaningful.containsMatchIn(it) }
    }

    private fun removeUiArtifacts(bubbles: List<ParsedBubble>): List<ParsedBubble> {
        val result = ArrayList<ParsedBubble>()
        for (bubble in bubbles) {
            val key = bubble.text.lowercase()
            if (key in uiActions) {
                if (key == "add contact" && looksLikeContactCardTitle(result.lastOrNull()?.text)) result.removeAt(result.lastIndex)
                continue
            }
            result += bubble
        }
        return result
    }

    private fun looksLikeContactCardTitle(value: String?): Boolean {
        if (value == null || value.length < 2 || value.length > 60) return false
        val words = value.trim().split(whitespace)
        return words.size in 1..4 && words.all(capitalizedWord::matches)
    }

    private fun invalid(message: String) = OpenAiException(OpenAiFailure.INVALID_RESPONSE, message)
}
