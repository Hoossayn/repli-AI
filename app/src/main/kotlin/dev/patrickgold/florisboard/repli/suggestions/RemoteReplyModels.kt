package dev.patrickgold.florisboard.repli.suggestions

import dev.patrickgold.florisboard.repli.capture.ConversationTurn
import dev.patrickgold.florisboard.repli.profile.LearnedStyleSnapshot
import dev.patrickgold.florisboard.repli.profile.LearnedTextingStyle
import dev.patrickgold.florisboard.repli.profile.VoiceStyle
import dev.patrickgold.florisboard.repli.persona.Persona
import kotlin.math.abs
import java.util.TimeZone

enum class ReplyOrigin { REMOTE, ON_DEVICE, ON_DEVICE_FALLBACK }

data class ReplyGenerationResult(
    val replies: List<String>,
    val explanation: String,
    val origin: ReplyOrigin,
)

enum class ReplyIntent(val wireValue: String) { REPLY("reply"), FRESH_START("fresh_start") }

data class SharedReplySituation(
    val intent: ReplyIntent = ReplyIntent.REPLY,
    val timeZoneId: String = TimeZone.getDefault().id,
)

enum class SharedSpeaker(val wireValue: String) { ME("me"), THEM("them") }

data class SharedConversationTurn(val speaker: SharedSpeaker, val text: String)

data class SharedReplyStyle(
    val preset: String,
    val summary: String?,
    val examples: List<String>,
    val personaName: String? = null,
    val personaDescription: String? = null,
    val personaExamples: List<String> = emptyList(),
)

/** The bounded payload prepared after the user confirms the reviewed context. */
data class PreparedRemoteReplyRequest(
    val context: List<SharedConversationTurn>,
    val style: SharedReplyStyle,
    val instructions: String? = null,
    val profileId: String? = null,
    val replySituation: SharedReplySituation = SharedReplySituation(),
)

/**
 * Applies the privacy boundary before the network layer sees any data. It has no
 * fields for package names, contact names, notification metadata, screenshots,
 * or the retained message corpus. A selected profile contributes only its opaque ID.
 * The device timezone is shared only to situate the requested reply in local time.
 */
object RemoteReplyPrivacyPolicy {
    const val MAX_CONTEXT_TURNS = 60
    const val MAX_CONTEXT_CHARACTERS = 1_000
    const val MAX_STYLE_EXAMPLES = 5
    const val MAX_EXAMPLE_CHARACTERS = 280
    const val MAX_INSTRUCTION_CHARACTERS = 500

    fun prepare(
        turns: List<ConversationTurn>,
        fallbackStyle: VoiceStyle?,
        learnedSnapshot: LearnedStyleSnapshot?,
        compactLearnedStyle: LearnedTextingStyle? = learnedSnapshot?.style,
        instructions: String? = null,
        profileId: String? = null,
        persona: Persona? = null,
        replyIntent: ReplyIntent = ReplyIntent.REPLY,
        timeZoneId: String = TimeZone.getDefault().id,
    ): PreparedRemoteReplyRequest {
        val context = turns
            .mapNotNull { turn ->
                normalize(turn.text, MAX_CONTEXT_CHARACTERS)
                    .takeIf(String::isNotEmpty)
                    ?.let { SharedConversationTurn(if (turn.fromMe) SharedSpeaker.ME else SharedSpeaker.THEM, it) }
            }
            .takeLast(MAX_CONTEXT_TURNS)
        val target = context.lastOrNull { it.speaker == SharedSpeaker.THEM }?.text.orEmpty()
        val examples = selectExamples(learnedSnapshot?.outgoingExamples.orEmpty(), target)
        return PreparedRemoteReplyRequest(
            context = context,
            style = SharedReplyStyle(
                preset = (fallbackStyle ?: VoiceStyle.CASUAL).name.lowercase(),
                summary = compactLearnedStyle?.summary(),
                examples = examples,
                personaName = persona?.name,
                personaDescription = persona?.description,
                personaExamples = persona?.examples.orEmpty(),
            ),
            instructions = prepareInstructions(instructions),
            profileId = profileId,
            replySituation = SharedReplySituation(replyIntent, timeZoneId),
        )
    }

    /** Preserve internal whitespace: the review shows exactly the text sent. Never silently truncate. */
    fun prepareInstructions(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)?.also {
        require(it.length <= MAX_INSTRUCTION_CHARACTERS) { "Response guidance is too long" }
    }

    private fun selectExamples(examples: List<String>, target: String): List<String> {
        val targetTokens = tokens(target)
        return examples.mapIndexedNotNull { index, raw ->
            val text = normalize(raw, MAX_EXAMPLE_CHARACTERS)
            if (text.isEmpty()) null else ScoredExample(
                text = text,
                score = tokens(text).intersect(targetTokens).size * 100 - abs(text.length - target.length),
                recency = index,
            )
        }
            .distinctBy { it.text }
            .sortedWith(compareByDescending<ScoredExample> { it.score }.thenByDescending { it.recency })
            .take(MAX_STYLE_EXAMPLES)
            .map(ScoredExample::text)
    }

    private fun normalize(value: String, maxCharacters: Int): String =
        value.trim().replace(Regex("\\s+"), " ").take(maxCharacters)

    private fun tokens(value: String): Set<String> = Regex("[\\p{L}\\p{N}']+")
        .findAll(value.lowercase())
        .map { it.value }
        .filter { it.length > 1 }
        .toSet()

    private data class ScoredExample(val text: String, val score: Int, val recency: Int)
}

internal fun requireThreeCandidates(candidates: List<String>): List<String> {
    val normalized = candidates.map(String::trim)
    require(normalized.size == 3) { "The backend must return exactly three candidates" }
    require(normalized.all { it.isNotEmpty() && it.length <= 500 }) { "Candidates must be 1..500 characters" }
    require(normalized.distinct().size == normalized.size) { "Candidates must be distinct" }
    return normalized
}
