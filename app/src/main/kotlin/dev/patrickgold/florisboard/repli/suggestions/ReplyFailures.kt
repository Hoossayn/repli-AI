package dev.patrickgold.florisboard.repli.suggestions

/** [memoryTurns] is how many earlier approved turns were available (null when no saved chat). */
data class RemoteReplyBatch(val replies: List<String>, val memorySaved: Boolean?, val memoryTurns: Int? = null)

/** Why an AI call failed, in words the keyboard panels can show. Mapped from OpenAiFailure in DirectReplyEngines. */
enum class CloudReplyFailure(val label: String) {
    UNAVAILABLE("OpenAI unavailable"),
    SIGN_IN_REQUIRED("Connect ChatGPT or an API key in Repli"),
    USAGE_LIMIT("Usage limit reached · check ChatGPT usage settings"),
    BUSY("OpenAI is busy · try again later"),
    SERVICE_CONFIGURATION("AI access needs attention · open Repli settings"),
    TIMEOUT("OpenAI timed out"),
    CONNECTION("Connection failed"),
    INVALID_REQUEST("Request rejected · check the reviewed chats"),
    INVALID_RESPONSE("OpenAI returned an incomplete reply"),
    REFUSED("OpenAI couldn't suggest safely"),
}

open class RemoteReplyException(
    message: String,
    cause: Throwable? = null,
    val reason: CloudReplyFailure = CloudReplyFailure.UNAVAILABLE,
) : Exception(message, cause)

class RemoteReplyAuthenticationException(message: String) :
    RemoteReplyException(message, reason = CloudReplyFailure.SIGN_IN_REQUIRED)
