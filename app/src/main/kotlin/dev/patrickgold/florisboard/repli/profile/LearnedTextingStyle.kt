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

package dev.patrickgold.florisboard.repli.profile

import kotlin.math.roundToInt

data class LearnedTextingStyle(
    val messagesAnalyzed: Int,
    val averageWords: Double,
    val averageCharacters: Double,
    val lowercaseStartRatio: Double,
    val terminalPunctuationRatio: Double,
    val emojiMessageRatio: Double,
    val questionRatio: Double,
    val exclamationRatio: Double,
    val preferredEmojis: List<String>,
) {
    fun summary(): String {
        val casing = when {
            lowercaseStartRatio >= 0.7 -> "mostly lowercase"
            lowercaseStartRatio <= 0.3 -> "usually capitalized"
            else -> "mixed casing"
        }
        val length = when {
            averageWords <= 3.5 -> "very short replies"
            averageWords <= 9 -> "about ${averageWords.roundToInt()} words/reply"
            else -> "longer replies"
        }
        val punctuation = when {
            terminalPunctuationRatio <= 0.25 -> "rarely ends with punctuation"
            terminalPunctuationRatio >= 0.75 -> "usually ends with punctuation"
            else -> "mixed punctuation"
        }
        val emoji = when {
            emojiMessageRatio >= 0.35 && preferredEmojis.isNotEmpty() ->
                "often uses ${preferredEmojis.take(3).joinToString(" ")}"
            emojiMessageRatio >= 0.1 -> "sometimes uses emojis"
            else -> "rarely uses emojis"
        }
        return listOf(casing, length, punctuation, emoji).joinToString(" · ")
    }
}

data class LearnedStyleSnapshot(
    val style: LearnedTextingStyle,
    val outgoingExamples: List<String>,
)
