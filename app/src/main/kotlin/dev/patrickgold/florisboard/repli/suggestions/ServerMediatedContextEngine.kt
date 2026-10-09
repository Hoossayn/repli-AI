package dev.patrickgold.florisboard.repli.suggestions

import android.os.SystemClock
import dev.patrickgold.florisboard.repli.capture.ConversationTurn
import dev.patrickgold.florisboard.repli.capture.TurnSource
import dev.patrickgold.florisboard.repli.capture.VisualBubble
import dev.patrickgold.florisboard.repli.identity.CapturedContactName
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal fun interface ContextImageBackendTransport {
    suspend fun post(endpoint: String, bearerToken: String, body: ByteArray): ReplyBackendResponse
}

data class AiConversationContext(
    val turns: List<ConversationTurn>,
    val contactName: String?,
    val bubbles: List<VisualBubble>? = null,
)

/** Sends one bounded, opt-in image sequence to Repli's backend, never to OpenAI directly. */
class ServerMediatedContextEngine internal constructor(
    replyEndpoint: String,
    private val sessionProvider: RepliBackendSessionProvider,
    private val transport: ContextImageBackendTransport,
) {
    private val endpoint = contextEndpoint(replyEndpoint)

    constructor(replyEndpoint: String, sessionProvider: RepliBackendSessionProvider) :
        this(replyEndpoint, sessionProvider, UrlConnectionContextImageTransport)

    suspend fun extract(image: ByteArray): AiConversationContext = extract(listOf(image))

    suspend fun extract(images: List<ByteArray>): AiConversationContext {
        require(images.size in 1..MAX_IMAGES) { "Capture must contain 1..$MAX_IMAGES images" }
        require(images.all { it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES } &&
            images.sumOf(ByteArray::size) <= MAX_TOTAL_IMAGE_BYTES
        ) { "Cropped chat images are too large" }
        val startedAt = SystemClock.elapsedRealtime()
        val bearerToken = sessionProvider.bearerTokenForRequest()
            ?: throw RemoteReplyAuthenticationException("A Repli account session is required")
        require(BEARER_TOKEN_PATTERN.matches(bearerToken)) { "Invalid Repli account session" }
        val authenticatedAt = SystemClock.elapsedRealtime()
        val encodedImages = try {
            images.map(Base64.getEncoder()::encodeToString)
        } finally {
            images.forEach { it.fill(0) }
        }
        // The serialized request owns separate copies. Raw PNGs are overwritten as soon as
        // those copies exist instead of being retained through the network call.
        val request = JSONObject()
            .put("contract_version", 3)
            .put("media_type", "image/png")
            .put("images_base64", JSONArray(encodedImages))
            .put("frame_order", "oldest_to_newest")
            .put("max_turns", RemoteReplyPrivacyPolicy.MAX_CONTEXT_TURNS)
            .toString().toByteArray(Charsets.UTF_8)
        require(request.size <= MAX_REQUEST_BYTES) { "Cropped chat image request is too large" }

        val networkStartedAt = SystemClock.elapsedRealtime()
        val response = try {
            transport.post(endpoint, bearerToken, request)
        } finally {
            request.fill(0)
            flogDebug {
                "RepliCapture: image request authMs=${authenticatedAt - startedAt} " +
                    "requestEncodingMs=${networkStartedAt - authenticatedAt} " +
                    "networkAndAiMs=${SystemClock.elapsedRealtime() - networkStartedAt}"
            }
        }
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED ||
            response.status == HttpURLConnection.HTTP_FORBIDDEN
        ) {
            sessionProvider.invalidate(bearerToken)
            throw RemoteReplyAuthenticationException("The Repli account session was rejected")
        }
        if (response.status !in 200..299) {
            throw RemoteReplyException(
                "Context backend returned HTTP ${response.status}",
                reason = backendFailureReason(response),
            )
        }
        val json = runCatching { JSONObject(response.body) }
            .getOrElse { throw RemoteReplyException("Context backend returned invalid JSON", it) }
        val bubbles = json.optJSONArray("bubbles")
            ?: throw RemoteReplyException("Context backend omitted visual bubbles")
        if (bubbles.length() !in 0..MAX_VISUAL_BUBBLES) {
            throw RemoteReplyException("Context backend returned an invalid bubble count")
        }
        val parsedBubbles = List(bubbles.length()) { index ->
            val bubble = bubbles.optJSONObject(index)
                ?: throw RemoteReplyException("Context bubbles must be objects")
            val speaker = bubble.optString("speaker")
            val text = (bubble.opt("text") as? String)?.trim()
                ?: throw RemoteReplyException("Context text must be a string")
            val frameIndex = bubble.optInt("frame_index", -1)
            val box = bubble.optJSONObject("box")
                ?: throw RemoteReplyException("Context bubble has no bounds")
            val left = box.optInt("left", -1)
            val top = box.optInt("top", -1)
            val right = box.optInt("right", -1)
            val bottom = box.optInt("bottom", -1)
            if (speaker !in setOf("me", "them") || text.isEmpty() || text.length > 2_000 ||
                frameIndex !in images.indices || left !in 0..999 || top !in 0..999 ||
                right !in (left + 1)..1000 || bottom !in (top + 1)..1000
            ) throw RemoteReplyException("Context backend returned an invalid bubble")
            VisualBubble(text, speaker == "me", TurnSource(frameIndex, left, top, right, bottom))
        }
        val rawContactName: Any? = json.opt("contact_name")
        val contactName = if (rawContactName == null || rawContactName == JSONObject.NULL) {
            null
        } else if (rawContactName is String) {
            CapturedContactName.prepare(rawContactName)
        } else {
            throw RemoteReplyException("Context contact name must be text or null")
        }
        // Capture order is authoritative. A backend frame_order field is intentionally ignored so
        // neither the model nor an older backend can rearrange pages after the user's explicit taps.
        return AiConversationContext(emptyList(), contactName, parsedBubbles)
    }

    companion object {
        const val MAX_IMAGES = 4
        const val MAX_VISUAL_BUBBLES = 100
        const val MAX_IMAGE_BYTES = 8_000_000
        const val MAX_TOTAL_IMAGE_BYTES = 24_000_000
        private const val MAX_REQUEST_BYTES = 33_000_000
        private val BEARER_TOKEN_PATTERN = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

        fun contextEndpoint(replyEndpoint: String): String {
            val uri = URI(replyEndpoint.trim())
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
            require(uri.userInfo == null && uri.query == null && uri.fragment == null)
            require(uri.path == "/v1/reply-candidates") { "Unexpected reply endpoint path" }
            return URI(uri.scheme, null, uri.host, uri.port, "/v1/context-from-image", null, null).toString()
        }
    }
}

private object UrlConnectionContextImageTransport : ContextImageBackendTransport {
    override suspend fun post(endpoint: String, bearerToken: String, body: ByteArray): ReplyBackendResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 45_000
                doOutput = true
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $bearerToken")
                setFixedLengthStreamingMode(body.size)
            }
            try {
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readVisionResponse(MAX_RESPONSE_BYTES) }.orEmpty()
                ReplyBackendResponse(status, responseBody)
            } finally {
                connection.disconnect()
            }
        }

    private const val MAX_RESPONSE_BYTES = 160 * 1_024
}

private fun InputStream.readVisionResponse(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size() + count > maxBytes) throw RemoteReplyException("Context response was too large")
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}
