package dev.patrickgold.florisboard.repli.suggestions

import dev.patrickgold.florisboard.repli.account.RepliAccountSessionRepository
import dev.patrickgold.florisboard.repli.account.RepliFirebaseAccountManager
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

fun interface RemoteReplyGenerator {
    suspend fun suggest(request: PreparedRemoteReplyRequest): List<String>
}

/**
 * Minimal session seam over the fork's account singletons. It mirrors the exact
 * subset of the source `BackendSessionProvider` contract these engines use —
 * [bearerTokenForRequest] plus [invalidate] — so engine behavior stays identical
 * while auth remains owned by [RepliFirebaseAccountManager] and
 * [RepliAccountSessionRepository].
 */
interface RepliBackendSessionProvider {
    suspend fun bearerTokenForRequest(): String?
    fun invalidate(token: String)
}

/** Default provider backed by the fork's account singletons. */
object RepliAccountSessionProvider : RepliBackendSessionProvider {
    override suspend fun bearerTokenForRequest(): String? =
        RepliFirebaseAccountManager.bearerTokenForRequest()

    override fun invalidate(token: String) =
        RepliAccountSessionRepository.invalidate(token)
}

internal data class ReplyBackendResponse(val status: Int, val body: String)
/** [memoryTurns] is how many earlier approved turns the backend could use (null when no saved chat). */
data class RemoteReplyBatch(val replies: List<String>, val memorySaved: Boolean?, val memoryTurns: Int? = null)

internal fun interface ReplyBackendTransport {
    suspend fun post(endpoint: String, bearerToken: String, body: ByteArray): ReplyBackendResponse
}

/** Calls only the app team's backend. The OpenAI API is never addressed by this client. */
class ServerMediatedReplyEngine internal constructor(
    endpoint: String,
    private val sessionProvider: RepliBackendSessionProvider,
    private val transport: ReplyBackendTransport,
) : RemoteReplyGenerator {
    private val endpoint = validateEndpoint(endpoint)

    constructor(endpoint: String, sessionProvider: RepliBackendSessionProvider) :
        this(endpoint, sessionProvider, UrlConnectionReplyBackendTransport)

    override suspend fun suggest(request: PreparedRemoteReplyRequest): List<String> = suggestDetailed(request).replies

    suspend fun suggestDetailed(request: PreparedRemoteReplyRequest): RemoteReplyBatch {
        val bearerToken = sessionProvider.bearerTokenForRequest()
            ?: throw RemoteReplyAuthenticationException("A Repli account session is required")
        require(BEARER_TOKEN_PATTERN.matches(bearerToken)) { "Invalid Repli account session" }
        val requestBytes = request.toJson().toString().toByteArray(Charsets.UTF_8)
        require(requestBytes.size <= MAX_REQUEST_BYTES) { "Approved request is too large" }

        val response = transport.post(endpoint, bearerToken, requestBytes)
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED ||
            response.status == HttpURLConnection.HTTP_FORBIDDEN
        ) {
            sessionProvider.invalidate(bearerToken)
            throw RemoteReplyAuthenticationException("The Repli account session was rejected")
        }
        if (response.status !in 200..299) {
            throw RemoteReplyException(
                "Reply backend returned HTTP ${response.status}",
                reason = backendFailureReason(response),
            )
        }

        val json = runCatching { JSONObject(response.body) }
            .getOrElse { throw RemoteReplyException("Reply backend returned invalid JSON", it) }
        val memorySaved = if (request.profileId != null) json.opt("memory_saved") as? Boolean else null
        val memoryTurns = if (request.profileId != null) (json.opt("memory_turns") as? Int)?.takeIf { it >= 0 } else null
        val array = json.optJSONArray("candidates")
            ?: throw RemoteReplyException("Reply backend omitted candidates")
        val candidates = List(array.length()) { index ->
            array.opt(index) as? String
                ?: throw RemoteReplyException("Reply candidates must be strings")
        }
        val replies = runCatching { requireThreeCandidates(candidates) }
            .getOrElse { throw RemoteReplyException(it.message ?: "Invalid reply candidates", it) }
        return RemoteReplyBatch(replies, memorySaved, memoryTurns)
    }

    private fun PreparedRemoteReplyRequest.toJson() = JSONObject().apply {
        // An older server must reject guidance, not silently generate without applying it.
        put("contract_version", SITUATION_CONTRACT_VERSION)
        put("reply_situation", JSONObject().apply {
            put("intent", replySituation.intent.wireValue)
            put("time_zone", replySituation.timeZoneId)
        })
        if (profileId != null) {
            require(PROFILE_ID_PATTERN.matches(profileId)) { "Invalid profile ID" }
            put("profile_id", profileId)
        }
        if (instructions != null) {
            require(RemoteReplyPrivacyPolicy.prepareInstructions(instructions) == instructions)
            put("instructions", instructions)
        }
        put("candidate_count", 3)
        put("context", JSONArray().apply {
            context.forEach { turn ->
                put(JSONObject().put("speaker", turn.speaker.wireValue).put("text", turn.text))
            }
        })
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
        const val GUIDANCE_CONTRACT_VERSION = 2
        const val MEMORY_CONTRACT_VERSION = 3
        const val PERSONA_CONTRACT_VERSION = 4
        const val SITUATION_CONTRACT_VERSION = 5
        private const val MAX_REQUEST_BYTES = 64 * 1_024
        private val BEARER_TOKEN_PATTERN = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        private val PROFILE_ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

        fun isConfigured(endpoint: String): Boolean = runCatching { validateEndpoint(endpoint) }.isSuccess

        private fun validateEndpoint(endpoint: String): String {
            val uri = URI(endpoint.trim())
            require(uri.scheme.equals("https", ignoreCase = true)) { "Reply backend URL must use HTTPS" }
            require(!uri.host.isNullOrBlank()) { "Reply backend URL must include a host" }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Reply backend URL cannot contain credentials, a query, or a fragment"
            }
            return uri.toString()
        }
    }
}

internal object UrlConnectionReplyBackendTransport : ReplyBackendTransport {
    private const val CONNECT_TIMEOUT_MS = 5_000
    // The provider itself has a 12-second timeout; leave room for backend accounting.
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_RESPONSE_BYTES = 64 * 1_024

    override suspend fun post(endpoint: String, bearerToken: String, body: ByteArray): ReplyBackendResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
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
                    ?.use { it.readBoundedUtf8(MAX_RESPONSE_BYTES) }
                    .orEmpty()
                ReplyBackendResponse(status, responseBody)
            } finally {
                connection.disconnect()
            }
        }
}

enum class CloudReplyFailure(val label: String) {
    UNAVAILABLE("Cloud unavailable"),
    SIGN_IN_REQUIRED("Cloud session unavailable · open Repli to sign in"),
    USAGE_LIMIT("Cloud usage limit reached"),
    BUSY("Cloud busy · try again later"),
    SERVICE_CONFIGURATION("Cloud service needs attention"),
    TIMEOUT("Cloud timed out"),
    CONNECTION("Cloud connection failed"),
    INVALID_REQUEST("Cloud request rejected · check the reviewed chats"),
    INVALID_RESPONSE("Cloud returned an incomplete reply"),
    REFUSED("Cloud couldn't suggest safely"),
}

open class RemoteReplyException(
    message: String,
    cause: Throwable? = null,
    val reason: CloudReplyFailure = CloudReplyFailure.UNAVAILABLE,
) : Exception(message, cause)

class RemoteReplyAuthenticationException(message: String) :
    RemoteReplyException(message, reason = CloudReplyFailure.SIGN_IN_REQUIRED)

internal fun backendFailureReason(response: ReplyBackendResponse): CloudReplyFailure {
    val json = runCatching { JSONObject(response.body) }.getOrNull()
    return when (response.status) {
        400, 413, 422 -> CloudReplyFailure.INVALID_REQUEST
        429 -> if (json?.optString("error") == "quota_exceeded") {
            CloudReplyFailure.USAGE_LIMIT
        } else {
            CloudReplyFailure.BUSY
        }
        502 -> when (json?.optString("reason")) {
            "provider_authentication_failed", "provider_quota_exceeded", "provider_request_rejected" ->
                CloudReplyFailure.SERVICE_CONFIGURATION
            "provider_rate_limited" -> CloudReplyFailure.BUSY
            "provider_timeout" -> CloudReplyFailure.TIMEOUT
            "provider_connection_failed" -> CloudReplyFailure.CONNECTION
            "generation_incomplete", "generation_invalid_response" -> CloudReplyFailure.INVALID_RESPONSE
            "generation_refused" -> CloudReplyFailure.REFUSED
            else -> CloudReplyFailure.UNAVAILABLE
        }
        else -> CloudReplyFailure.UNAVAILABLE
    }
}

private fun InputStream.readBoundedUtf8(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size() + count > maxBytes) throw RemoteReplyException("Reply backend response was too large")
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}
