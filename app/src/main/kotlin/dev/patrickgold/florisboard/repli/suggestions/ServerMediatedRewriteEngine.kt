package dev.patrickgold.florisboard.repli.suggestions

import dev.patrickgold.florisboard.repli.persona.Persona
import dev.patrickgold.florisboard.repli.profile.LearnedStyleSnapshot
import dev.patrickgold.florisboard.repli.profile.VoiceStyle
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Closed list mirrored by the backend; the wire value is the only thing sent. */
enum class RewriteTone(val wireValue: String, val label: String) {
    CASUAL("casual", "Casual"),
    WARM("warm", "Warm"),
    DIRECT("direct", "Direct"),
    POLITE("polite", "Polite"),
    SHORTER("shorter", "Shorter"),
    CLEARER("clearer", "Clearer"),
    GRAMMAR("grammar", "Fix grammar"),
    PIDGIN("pidgin", "Pidgin");

    companion object {
        fun fromWire(value: String?): RewriteTone? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Exactly what leaves the phone for a rewrite: the draft, the tone and style cues. */
data class PreparedRewriteRequest(
    val text: String,
    val tone: RewriteTone,
    val style: SharedReplyStyle,
    val instructions: String? = null,
)

object RewritePrivacyPolicy {
    const val MAX_TEXT_CHARACTERS = 600
    const val MAX_INSTRUCTION_CHARACTERS = 300
    private const val MAX_EXAMPLES = 5
    private const val MAX_EXAMPLE_CHARACTERS = 280

    /** Null when the draft is empty or too long to rewrite; never truncates silently. */
    fun prepare(
        text: String,
        tone: RewriteTone,
        fallbackStyle: VoiceStyle?,
        learnedSnapshot: LearnedStyleSnapshot?,
        persona: Persona?,
        instructions: String? = null,
    ): PreparedRewriteRequest? {
        val draft = text.trim()
        if (draft.isEmpty() || draft.length > MAX_TEXT_CHARACTERS) return null
        val direction = instructions?.trim()?.takeIf(String::isNotEmpty)
        if (direction != null && direction.length > MAX_INSTRUCTION_CHARACTERS) return null
        val examples = learnedSnapshot?.outgoingExamples.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_EXAMPLE_CHARACTERS }
            .distinct()
            .takeLast(MAX_EXAMPLES)
        return PreparedRewriteRequest(
            text = draft,
            tone = tone,
            style = SharedReplyStyle(
                preset = (fallbackStyle ?: VoiceStyle.CASUAL).name.lowercase(),
                summary = learnedSnapshot?.style?.summary(),
                examples = examples,
                personaName = persona?.name,
                personaDescription = persona?.description,
                personaExamples = persona?.examples.orEmpty().take(3),
            ),
            instructions = direction,
        )
    }
}

/** Calls only the app team's backend (`/v1/rewrite`); never the AI provider directly. */
class ServerMediatedRewriteEngine internal constructor(
    replyEndpoint: String,
    private val sessionProvider: RepliBackendSessionProvider,
    private val transport: ReplyBackendTransport,
) {
    private val endpoint = rewriteEndpoint(replyEndpoint)

    constructor(replyEndpoint: String, sessionProvider: RepliBackendSessionProvider) :
        this(replyEndpoint, sessionProvider, UrlConnectionReplyBackendTransport)

    suspend fun rewrite(request: PreparedRewriteRequest): List<String> {
        val bearerToken = sessionProvider.bearerTokenForRequest()
            ?: throw RemoteReplyAuthenticationException("A Repli account session is required")
        val body = request.toJson().toString().toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_REQUEST_BYTES) { "Rewrite request is too large" }
        val response = transport.post(endpoint, bearerToken, body)
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED ||
            response.status == HttpURLConnection.HTTP_FORBIDDEN
        ) {
            sessionProvider.invalidate(bearerToken)
            throw RemoteReplyAuthenticationException("The Repli account session was rejected")
        }
        if (response.status !in 200..299) {
            throw RemoteReplyException("Rewrite backend returned HTTP ${response.status}", reason = backendFailureReason(response))
        }
        val json = runCatching { JSONObject(response.body) }
            .getOrElse { throw RemoteReplyException("Rewrite backend returned invalid JSON", it) }
        val array = json.optJSONArray("candidates") ?: throw RemoteReplyException("Rewrite backend omitted candidates")
        val candidates = List(array.length()) { index ->
            (array.opt(index) as? String)?.trim() ?: throw RemoteReplyException("Rewrite candidates must be strings")
        }
        if (candidates.size != 3 || candidates.any { it.isEmpty() || it.length > MAX_CANDIDATE_CHARACTERS } ||
            candidates.distinct().size != 3
        ) {
            throw RemoteReplyException("Rewrite backend returned invalid candidates", reason = CloudReplyFailure.INVALID_RESPONSE)
        }
        return candidates
    }

    private fun PreparedRewriteRequest.toJson() = JSONObject().apply {
        put("contract_version", CONTRACT_VERSION)
        put("text", text)
        put("tone", tone.wireValue)
        if (instructions != null) put("instructions", instructions)
        put("style", JSONObject().apply {
            put("preset", style.preset)
            if (style.summary == null) put("summary", JSONObject.NULL) else put("summary", style.summary)
            put("examples", JSONArray(style.examples))
            if (style.personaName != null) {
                put("persona", JSONObject().apply {
                    put("name", style.personaName)
                    put("description", style.personaDescription)
                    put("examples", JSONArray(style.personaExamples))
                })
            }
        })
    }

    companion object {
        const val CONTRACT_VERSION = 1
        private const val MAX_REQUEST_BYTES = 16 * 1_024
        private const val MAX_CANDIDATE_CHARACTERS = 1_000

        fun rewriteEndpoint(replyEndpoint: String): String {
            val uri = URI(replyEndpoint.trim())
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
            require(uri.userInfo == null && uri.query == null && uri.fragment == null)
            require(uri.path == "/v1/reply-candidates") { "Unexpected reply endpoint path" }
            return URI(uri.scheme, null, uri.host, uri.port, "/v1/rewrite", null, null).toString()
        }
    }
}
