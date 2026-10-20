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

package dev.patrickgold.florisboard.repli.capture

enum class MessageSide { LEFT, RIGHT }

data class OcrTextRegion(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Conservative visual filter for generic one-to-one chat layouts.
 * It deliberately favors reviewable false negatives over collecting whole screens. */
class ScrollingTextCollector(
    private val messageSide: MessageSide,
    private val maxMessages: Int = 500,
) {
    private val collected = linkedMapOf<String, String>()

    fun addFrame(regions: List<OcrTextRegion>, frameWidth: Int, frameHeight: Int): Int {
        if (frameWidth <= 0 || frameHeight <= 0) return 0
        val frameText = regions.joinToString(" ") { normalize(it.text).lowercase() }
        if (captureUiMarkers.any(frameText::contains)) return 0
        val before = collected.size
        regions.sortedBy(OcrTextRegion::top).forEach { region ->
            if (collected.size >= maxMessages) return@forEach
            val centerX = (region.left + region.right) / 2.0 / frameWidth
            val centerY = (region.top + region.bottom) / 2.0 / frameHeight
            if (centerY !in CONTENT_TOP..CONTENT_BOTTOM) return@forEach
            val onSelectedSide = when (messageSide) {
                MessageSide.RIGHT -> centerX >= RIGHT_SIDE_START
                MessageSide.LEFT -> centerX <= LEFT_SIDE_END
            }
            if (!onSelectedSide) return@forEach

            val text = normalize(region.text)
            if (!isUsable(text)) return@forEach
            collected.putIfAbsent(dedupeKey(text), text)
        }
        return collected.size - before
    }

    fun messages(): List<String> = collected.values.toList()

    private fun normalize(text: String): String = text
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_MESSAGE_CHARACTERS)

    private fun dedupeKey(text: String): String = text
        .lowercase()
        .replace('’', '\'')
        .replace('“', '"')
        .replace('”', '"')

    private fun isUsable(text: String): Boolean {
        if (text.length < 2) return false
        val key = text.lowercase().trim()
        if (key in ignoredLabels) return false
        if (timePattern.matches(key) || numericDatePattern.matches(key)) return false
        return text.any(Char::isLetterOrDigit) || text.any { Character.getType(it) == Character.OTHER_SYMBOL.toInt() }
    }

    private companion object {
        const val CONTENT_TOP = 0.10
        const val CONTENT_BOTTOM = 0.88
        const val RIGHT_SIDE_START = 0.52
        const val LEFT_SIDE_END = 0.48
        const val MAX_MESSAGE_CHARACTERS = 2_000

        val timePattern = Regex("""^\d{1,2}:\d{2}(?:\s?[ap]m)?$""")
        val numericDatePattern = Regex("""^\d{1,4}[/.-]\d{1,2}[/.-]\d{1,4}$""")
        val ignoredLabels = setOf(
            "today", "yesterday", "seen", "sent", "delivered", "read",
            "message", "message…", "write a message", "write a message…", "send", "aa",
            "typing…", "online", "active now", "new messages", "scroll to bottom",
        )
        val captureUiMarkers = setOf(
            "learning from visible messages",
            "stop and review",
            "scroll the chat manually",
        )
    }
}
