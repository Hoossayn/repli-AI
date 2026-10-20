/*
 * Copyright (C) 2025-2026 The Repli Keyboard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.repli.suggestions

import dev.patrickgold.florisboard.repli.persona.Persona
import dev.patrickgold.florisboard.repli.profile.LearnedStyleSnapshot
import dev.patrickgold.florisboard.repli.profile.VoiceStyle

/** Closed list of tones; the wire value selects the prompt in ReplyPromptBuilder. */
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
