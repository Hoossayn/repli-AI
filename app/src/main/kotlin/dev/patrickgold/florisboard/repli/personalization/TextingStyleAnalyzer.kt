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

package dev.patrickgold.florisboard.repli.personalization

import dev.patrickgold.florisboard.repli.profile.LearnedStyleSnapshot
import dev.patrickgold.florisboard.repli.profile.LearnedTextingStyle
import dev.patrickgold.florisboard.repli.profile.VoiceStyle

class TextingStyleAnalyzer {
    fun analyze(rawMessages: List<String>): LearnedStyleSnapshot {
        val messages = rawMessages.map(String::trim).filter(::isUsableMessage)
        require(messages.isNotEmpty()) { "No usable outgoing messages were found" }

        val wordCounts = messages.map { message ->
            message.split(Regex("\\s+")).count(String::isNotBlank)
        }
        val letterStarts = messages.mapNotNull { message -> message.firstOrNull(Char::isLetter) }
        val emojiLists = messages.map(::emojisIn)
        val preferredEmojis = emojiLists.flatten()
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(5)
            .map { it.key }

        val style = LearnedTextingStyle(
            messagesAnalyzed = messages.size,
            averageWords = wordCounts.average(),
            averageCharacters = messages.map(String::length).average(),
            lowercaseStartRatio = ratio(letterStarts.count(Char::isLowerCase), letterStarts.size),
            terminalPunctuationRatio = ratio(messages.count { it.lastOrNull() in terminalPunctuation }, messages.size),
            emojiMessageRatio = ratio(emojiLists.count(List<String>::isNotEmpty), messages.size),
            questionRatio = ratio(messages.count { '?' in it }, messages.size),
            exclamationRatio = ratio(messages.count { '!' in it }, messages.size),
            preferredEmojis = preferredEmojis,
        )
        return LearnedStyleSnapshot(style, messages)
    }

    fun inferFallbackStyle(style: LearnedTextingStyle): VoiceStyle = when {
        style.emojiMessageRatio >= 0.25 || style.exclamationRatio >= 0.3 -> VoiceStyle.WARM
        style.lowercaseStartRatio <= 0.35 &&
            style.terminalPunctuationRatio >= 0.6 &&
            style.averageWords >= 4 -> VoiceStyle.DIRECT
        else -> VoiceStyle.CASUAL
    }

    private fun isUsableMessage(message: String): Boolean {
        if (message.isBlank()) return false
        val normalized = message.lowercase().trim('<', '>', ' ')
        if (normalized.contains("media omitted") || normalized.endsWith("omitted")) return false
        if (normalized in ignoredMessages) return false
        return message.any(Char::isLetterOrDigit) || emojisIn(message).isNotEmpty()
    }

    private fun emojisIn(message: String): List<String> {
        val result = mutableListOf<String>()
        var offset = 0
        while (offset < message.length) {
            val codePoint = message.codePointAt(offset)
            if (isEmoji(codePoint)) result += String(Character.toChars(codePoint))
            offset += Character.charCount(codePoint)
        }
        return result
    }

    private fun isEmoji(codePoint: Int): Boolean =
        codePoint in 0x1F300..0x1FAFF ||
            codePoint in 0x2600..0x27BF ||
            codePoint in 0x1F1E6..0x1F1FF

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator

    private companion object {
        val terminalPunctuation = setOf('.', '!', '?')
        val ignoredMessages = setOf(
            "this message was deleted",
            "you deleted this message",
            "image omitted",
            "video omitted",
            "audio omitted",
            "sticker omitted",
            "document omitted",
            "gif omitted",
        )
    }
}
