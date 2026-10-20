package dev.patrickgold.florisboard.repli.openai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Who pays for the call. Repli has no server: every request goes from the phone to
 * api.openai.com with either the user's ChatGPT plan (Sign in with ChatGPT access token)
 * or an API key the user pasted in. Tokens are held in memory only; storage lives elsewhere.
 */
sealed class OpenAiCredential {
    abstract val bearer: String

    /** Access token from Sign in with ChatGPT carrying the `chatgpt.tokens.use.direct` scope. */
    data class ChatGptPlan(val accessToken: String, val preferredModel: String? = null) : OpenAiCredential() {
        override val bearer: String get() = accessToken
    }

    /** A personal OpenAI API key; [model] is whichever the user chose in settings. */
    data class ApiKey(val key: String, val model: String) : OpenAiCredential() {
        override val bearer: String get() = key
    }
}

/** Closed set of outcomes the UI can explain. Provider messages are never shown or logged. */
enum class OpenAiFailure(val label: String, val retryable: Boolean) {
    SIGN_IN_REQUIRED("Sign in with ChatGPT again", false),
    PLAN_USAGE_LIMIT("ChatGPT plan usage limit reached · check usage in ChatGPT settings", false),
    PLAN_UNAVAILABLE("ChatGPT plan temporarily unavailable · try again shortly", true),
    PLAN_NOT_ELIGIBLE("This ChatGPT account can't share its plan with apps", false),
    PLAN_UNSUPPORTED("This feature isn't available on the ChatGPT plan route", false),
    API_KEY_QUOTA("OpenAI API key has no remaining credit", false),
    RATE_LIMITED("OpenAI is busy · try again later", true),
    NO_MODEL("No usable model is available for this account", false),
    REQUEST_REJECTED("OpenAI rejected the request · check the reviewed chats", false),
    UNAVAILABLE("OpenAI unavailable", true),
    TIMEOUT("OpenAI timed out", true),
    CONNECTION("Connection to OpenAI failed", true),
    INCOMPLETE("OpenAI returned an incomplete reply", true),
    REFUSED("OpenAI couldn't suggest safely", false),
    INVALID_RESPONSE("OpenAI returned something Repli couldn't read", true),
}

class OpenAiException(val failure: OpenAiFailure, message: String, cause: Throwable? = null) : Exception(message, cause) {
    var apiCode: String? = null
        private set
    var apiParam: String? = null
        private set

    /** Keeps only the closed `code` and `param` fields; the free-text message never crosses this boundary. */
    fun withApiError(error: JSONObject?): OpenAiException = apply {
        apiCode = error?.optString("code")?.takeIf(String::isNotEmpty)
        apiParam = error?.optString("param")?.takeIf(String::isNotEmpty)
    }
}

data class OpenAiHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val timeoutMillis: Int,
)

data class OpenAiHttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

fun interface OpenAiTransport {
    @Throws(IOException::class)
    suspend fun send(request: OpenAiHttpRequest): OpenAiHttpResponse
}

data class OpenAiModel(val id: String, val slug: String, val listed: Boolean)

data class OpenAiResult<T>(val value: T, val model: String, val usage: OpenAiUsage?, val requestId: String?)

/**
 * Device-direct client for the Responses API. Model choice, capability fallbacks and the
 * Sign in with ChatGPT error contract live here so callers only see [OpenAiFailure].
 */
class OpenAiPlanClient(
    private val transport: OpenAiTransport = UrlConnectionOpenAiTransport,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val defaultOptions: PromptOptions = PromptOptions(),
) {
    private var cachedPlanModel: Pair<String, Long>? = null

    suspend fun generateReplies(credential: OpenAiCredential, prompt: ReplyPrompt): OpenAiResult<List<String>> {
        val model = resolveModel(credential)
        return call(credential, REPLY_TIMEOUT_MILLIS, { options -> ReplyPromptBuilder.reply(prompt, model, clock(), options) }) { response ->
            OpenAiResponseParsing.candidates(response, ReplyPromptBuilder.MAX_CANDIDATE_CHARACTERS)
        }.let { OpenAiResult(it.value, model, it.usage, it.requestId) }
    }

    suspend fun readScreenshots(credential: OpenAiCredential, imagesBase64: List<String>, mediaType: String = "image/png"): OpenAiResult<ParsedCapture> {
        val model = resolveModel(credential)
        return call(credential, VISION_TIMEOUT_MILLIS, { options -> ReplyPromptBuilder.vision(imagesBase64, mediaType, model, options) }) { response ->
            OpenAiResponseParsing.visualCapture(response, imagesBase64.size)
        }.let { OpenAiResult(it.value, model, it.usage, it.requestId) }
    }

    suspend fun rewrite(credential: OpenAiCredential, prompt: RewritePrompt): OpenAiResult<List<String>> {
        val model = resolveModel(credential)
        return call(credential, REPLY_TIMEOUT_MILLIS, { options -> ReplyPromptBuilder.rewrite(prompt, model, options) }) { response ->
            OpenAiResponseParsing.candidates(response, ReplyPromptBuilder.MAX_REWRITE_CHARACTERS)
        }.let { OpenAiResult(it.value, model, it.usage, it.requestId) }
    }

    /** Models the credential may use. For a ChatGPT plan only `visibility == "list"` entries are usable. */
    suspend fun listModels(credential: OpenAiCredential): List<OpenAiModel> {
        val response = send(OpenAiHttpRequest("GET", "$baseUrl/models", headers(credential), null, MODELS_TIMEOUT_MILLIS))
        if (response.status !in 200..299) throw failureFor(response)
        val data = runCatching { JSONObject(response.body).optJSONArray("data") }.getOrNull()
            ?: throw OpenAiException(OpenAiFailure.INVALID_RESPONSE, "Model list was not JSON")
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val slug = item.optString("slug").takeIf(String::isNotEmpty) ?: id
            OpenAiModel(id, slug, listed = !item.has("visibility") || item.optString("visibility") == "list")
        }
    }

    /** The model to send: the API key's configured model, or the plan's preferred/first listed model (cached for an hour). */
    suspend fun resolveModel(credential: OpenAiCredential): String {
        if (credential is OpenAiCredential.ApiKey) return credential.model
        val plan = credential as OpenAiCredential.ChatGptPlan
        cachedPlanModel?.let { (model, at) -> if (clock() - at < MODEL_CACHE_SECONDS && (plan.preferredModel == null || plan.preferredModel == model)) return model }
        val listed = listModels(plan).filter { it.listed }
        val chosen = plan.preferredModel?.let { wanted -> listed.firstOrNull { it.slug == wanted || it.id == wanted } }
            ?: listed.firstOrNull()
            ?: throw OpenAiException(OpenAiFailure.NO_MODEL, "No listed model")
        cachedPlanModel = chosen.slug to clock()
        return chosen.slug
    }

    fun forgetCachedModel() {
        cachedPlanModel = null
    }

    private suspend fun <T> call(
        credential: OpenAiCredential,
        timeoutMillis: Int,
        build: (PromptOptions) -> JSONObject,
        parse: (JSONObject) -> T,
    ): OpenAiResult<T> {
        var options = if (credential is OpenAiCredential.ChatGptPlan) defaultOptions.copy(maxOutputTokens = false) else defaultOptions
        var attempts = 0
        while (true) {
            attempts++
            val body = build(options).toString().toByteArray(Charsets.UTF_8)
            val response = send(OpenAiHttpRequest("POST", "$baseUrl/responses", headers(credential, json = true), body, timeoutMillis))
            if (response.status !in 200..299) {
                val failure = failureFor(response)
                val downgraded = downgrade(options, failure)
                if (downgraded != null && attempts < MAX_ATTEMPTS) {
                    options = downgraded
                    continue
                }
                throw failure
            }
            val envelope = OpenAiResponseParsing.finishedResponse(response.body)
            val value = parse(envelope.response)
            return OpenAiResult(value, envelope.response.optString("model"), envelope.usage, envelope.response.optString("id").takeIf(String::isNotEmpty))
        }
    }

    /**
     * Some routes or models reject optional parameters (`subscription_sharing_unsupported_capability`
     * names the offending one in `param`). Drop that parameter and try once more rather than failing.
     */
    internal fun downgrade(options: PromptOptions, failure: OpenAiException): PromptOptions? {
        if (failure.failure != OpenAiFailure.PLAN_UNSUPPORTED && failure.failure != OpenAiFailure.REQUEST_REJECTED) return null
        val param = failure.apiParam ?: return null
        return when {
            param.startsWith("reasoning") && options.reasoningEffort != null -> options.copy(reasoningEffort = null)
            param.startsWith("text.verbosity") && options.verbosity != null -> options.copy(verbosity = null)
            param.startsWith("text") && options.structuredOutput -> options.copy(structuredOutput = false, verbosity = null)
            param.startsWith("stream") && options.stream -> options.copy(stream = false)
            param.startsWith("max_output_tokens") && options.maxOutputTokens -> options.copy(maxOutputTokens = false)
            else -> null
        }
    }

    internal fun failureFor(response: OpenAiHttpResponse): OpenAiException {
        val error = runCatching { JSONObject(response.body).optJSONObject("error") }.getOrNull()
        val code = error?.optString("code").orEmpty()
        val failure = when (response.status) {
            400, 422 -> if (code == "subscription_sharing_unsupported_capability") OpenAiFailure.PLAN_UNSUPPORTED else OpenAiFailure.REQUEST_REJECTED
            401 -> OpenAiFailure.SIGN_IN_REQUIRED
            403 -> when (code) {
                "subscription_sharing_user_not_eligible" -> OpenAiFailure.PLAN_NOT_ELIGIBLE
                "subscription_sharing_route_not_supported" -> OpenAiFailure.PLAN_UNSUPPORTED
                else -> OpenAiFailure.SIGN_IN_REQUIRED
            }
            404 -> if (code == "model_not_found") OpenAiFailure.NO_MODEL else OpenAiFailure.REQUEST_REJECTED
            429 -> when (code) {
                "subscription_sharing_usage_limit_exceeded" -> OpenAiFailure.PLAN_USAGE_LIMIT
                "insufficient_quota" -> OpenAiFailure.API_KEY_QUOTA
                else -> OpenAiFailure.RATE_LIMITED
            }
            503 -> if (code.startsWith("subscription_sharing_")) OpenAiFailure.PLAN_UNAVAILABLE else OpenAiFailure.UNAVAILABLE
            else -> OpenAiFailure.UNAVAILABLE
        }
        return OpenAiException(failure, "OpenAI returned HTTP ${response.status}").withApiError(error)
    }

    private suspend fun send(request: OpenAiHttpRequest): OpenAiHttpResponse = try {
        transport.send(request)
    } catch (timeout: SocketTimeoutException) {
        throw OpenAiException(OpenAiFailure.TIMEOUT, "OpenAI timed out", timeout)
    } catch (io: IOException) {
        throw OpenAiException(OpenAiFailure.CONNECTION, "OpenAI connection failed", io)
    }

    private fun headers(credential: OpenAiCredential, json: Boolean = false): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${credential.bearer}")
        put("Accept", "application/json, text/event-stream")
        if (json) put("Content-Type", "application/json")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val REPLY_TIMEOUT_MILLIS = 25_000
        const val VISION_TIMEOUT_MILLIS = 50_000
        const val MODELS_TIMEOUT_MILLIS = 10_000
        private const val MODEL_CACHE_SECONDS = 3_600L
        private const val MAX_ATTEMPTS = 4
    }
}

/** Plain HttpURLConnection transport; reads the whole (bounded) body, SSE or JSON. */
object UrlConnectionOpenAiTransport : OpenAiTransport {
    private const val MAX_RESPONSE_BYTES = 4 * 1_024 * 1_024

    override suspend fun send(request: OpenAiHttpRequest): OpenAiHttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 10_000
            connection.readTimeout = request.timeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (request.body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(request.body.size)
                connection.outputStream.use { it.write(request.body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBounded(MAX_RESPONSE_BYTES) }.orEmpty()
            val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(", ") }
            OpenAiHttpResponse(status, body, headers)
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readBounded(maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) throw IOException("OpenAI response was too large")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
