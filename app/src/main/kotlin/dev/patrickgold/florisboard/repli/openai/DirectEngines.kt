package dev.patrickgold.florisboard.repli.openai

import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.repli.capture.TurnSource
import dev.patrickgold.florisboard.repli.capture.VisualBubble
import dev.patrickgold.florisboard.repli.identity.CapturedContactName
import dev.patrickgold.florisboard.repli.suggestions.AiConversationContext
import dev.patrickgold.florisboard.repli.suggestions.CloudReplyFailure
import dev.patrickgold.florisboard.repli.suggestions.PreparedRemoteReplyRequest
import dev.patrickgold.florisboard.repli.suggestions.PreparedRewriteRequest
import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyAuthenticationException
import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyBatch
import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyException
import dev.patrickgold.florisboard.repli.suggestions.ReplyIntent
import dev.patrickgold.florisboard.repli.suggestions.SharedConversationTurn
import dev.patrickgold.florisboard.repli.suggestions.SharedReplyStyle
import dev.patrickgold.florisboard.repli.suggestions.SharedSpeaker
import java.util.Base64

/**
 * Supplies the credential for the next call: the user's ChatGPT plan token (refreshed by the
 * sign-in layer) or their API key. Null means nothing is configured yet.
 */
fun interface OpenAiCredentialProvider {
    suspend fun credentialForRequest(): OpenAiCredential?
}

/**
 * Bridges the keyboard's existing prepared requests to the device-direct OpenAI client.
 * The reply, capture and rewrite flows keep their privacy-policy types and failure labels;
 * only the destination changed from Repli's backend to the user's own OpenAI access.
 */
class DirectReplyEngines(
    private val credentials: OpenAiCredentialProvider,
    /** Resolved per request so constructing the engines (at IME start-up) touches no storage. */
    private val memoryProvider: () -> ProfileMemoryStore?,
    private val client: OpenAiPlanClient = OpenAiPlanClient(),
    /** Called when api.openai.com rejected a ChatGPT-plan token, so the session layer can refresh or re-sign-in. */
    private val onPlanTokenRejected: () -> Unit = {},
) {
    /** Three candidates, with on-device memory recalled before and remembered after, like the backend did. */
    suspend fun suggest(request: PreparedRemoteReplyRequest): RemoteReplyBatch {
        val credential = credentials.credentialForRequest()
            ?: throw RemoteReplyAuthenticationException("Sign in with ChatGPT or add an API key")
        val context = request.context.map { MemoryTurn(it.speaker == SharedSpeaker.ME, it.text) }
        val memory = if (request.profileId != null) memoryProvider() else null
        val recall = request.profileId?.let { memory?.recall(it, context) }
        val style = request.style.toPrompt().let { if (recall == null) it else ProfileMemory.styleWithRecall(it, recall) }
        val prompt = ReplyPrompt(
            context = request.context.map(SharedConversationTurn::toPrompt),
            style = style,
            history = recall?.history.orEmpty().map { PromptTurn(it.fromMe, it.text) },
            instructions = request.instructions,
            intent = if (request.replySituation.intent == ReplyIntent.FRESH_START) PromptIntent.FRESH_START else PromptIntent.REPLY,
            timeZoneId = request.replySituation.timeZoneId,
        )
        val result = translating(credential) { client.generateReplies(credential, prompt) }
        val saved = request.profileId?.let { memory?.remember(it, context) }
        return RemoteReplyBatch(result.value, memorySaved = saved, memoryTurns = recall?.history?.size)
    }

    /** Reads 1..4 cropped chat screenshots into speaker-tagged bubbles (same shape as the backend's contract v3). */
    suspend fun extract(images: List<ByteArray>): AiConversationContext {
        require(images.size in 1..ReplyPromptBuilder.MAX_VISION_IMAGES) { "Capture must contain 1..4 images" }
        require(images.all { it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES } && images.sumOf(ByteArray::size) <= MAX_TOTAL_IMAGE_BYTES) {
            "Cropped chat images are too large"
        }
        val credential = credentials.credentialForRequest()
            ?: throw RemoteReplyAuthenticationException("Sign in with ChatGPT or add an API key")
        val encoded = try {
            images.map(Base64.getEncoder()::encodeToString)
        } finally {
            images.forEach { it.fill(0) }
        }
        val capture = translating(credential) { client.readScreenshots(credential, encoded, "image/png") }.value
        val bubbles = capture.bubbles.map { bubble ->
            VisualBubble(bubble.text, bubble.fromMe, TurnSource(bubble.frameIndex, bubble.left, bubble.top, bubble.right, bubble.bottom))
        }
        return AiConversationContext(emptyList(), capture.contactName?.let(CapturedContactName::prepare), bubbles)
    }

    suspend fun rewrite(request: PreparedRewriteRequest): List<String> {
        val credential = credentials.credentialForRequest()
            ?: throw RemoteReplyAuthenticationException("Sign in with ChatGPT or add an API key")
        val prompt = RewritePrompt(request.text, request.tone.wireValue, request.style.toPrompt(), request.instructions)
        return translating(credential) { client.rewrite(credential, prompt) }.value
    }

    private inline fun <T> translating(credential: OpenAiCredential, block: () -> T): T = try {
        block()
    } catch (error: OpenAiException) {
        // Closed fields only: failure class, API error code/param and our own message — never content or tokens.
        flogError { "RepliOpenAI: ${error.failure} code=${error.apiCode} param=${error.apiParam} (${error.message})" }
        if (error.failure == OpenAiFailure.SIGN_IN_REQUIRED) {
            if (credential is OpenAiCredential.ChatGptPlan) onPlanTokenRejected()
            throw RemoteReplyAuthenticationException(error.message ?: "Sign in again")
        }
        throw RemoteReplyException(error.message ?: "OpenAI request failed", error, reason = error.failure.toCloudFailure())
    }

    private fun OpenAiFailure.toCloudFailure(): CloudReplyFailure = when (this) {
        OpenAiFailure.SIGN_IN_REQUIRED -> CloudReplyFailure.SIGN_IN_REQUIRED
        OpenAiFailure.PLAN_USAGE_LIMIT, OpenAiFailure.API_KEY_QUOTA -> CloudReplyFailure.USAGE_LIMIT
        OpenAiFailure.PLAN_UNAVAILABLE, OpenAiFailure.RATE_LIMITED -> CloudReplyFailure.BUSY
        OpenAiFailure.PLAN_NOT_ELIGIBLE, OpenAiFailure.PLAN_UNSUPPORTED, OpenAiFailure.NO_MODEL -> CloudReplyFailure.SERVICE_CONFIGURATION
        OpenAiFailure.REQUEST_REJECTED -> CloudReplyFailure.INVALID_REQUEST
        OpenAiFailure.TIMEOUT -> CloudReplyFailure.TIMEOUT
        OpenAiFailure.CONNECTION -> CloudReplyFailure.CONNECTION
        OpenAiFailure.INCOMPLETE, OpenAiFailure.INVALID_RESPONSE -> CloudReplyFailure.INVALID_RESPONSE
        OpenAiFailure.REFUSED -> CloudReplyFailure.REFUSED
        OpenAiFailure.UNAVAILABLE -> CloudReplyFailure.UNAVAILABLE
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 8_000_000
        const val MAX_TOTAL_IMAGE_BYTES = 24_000_000
    }
}

private fun SharedConversationTurn.toPrompt() = PromptTurn(speaker == SharedSpeaker.ME, text)

private fun SharedReplyStyle.toPrompt() = PromptStyle(
    preset = preset,
    summary = summary,
    examples = examples,
    persona = personaName?.let { PromptPersona(it, personaDescription.orEmpty(), personaExamples) },
)
