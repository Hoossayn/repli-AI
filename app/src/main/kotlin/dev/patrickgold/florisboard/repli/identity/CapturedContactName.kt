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

package dev.patrickgold.florisboard.repli.identity

/** Conservative boundary for an untrusted chat title returned by image reading. */
object CapturedContactName {
    fun identityKey(name: String): String = name.lowercase().filter(Char::isLetterOrDigit)
    private val genericTitles = setOf(
        "whatsapp", "telegram", "messenger", "messages", "chat", "online", "typing", "you",
        "5g", "4g", "lte", "volte",
    )
    private val clock = Regex("(?<!\\d)\\d{1,2}:\\d{2}(?::\\d{2})?(?!\\d)")
    private val phone = Regex("^\\+?[\\d()\\s-]+$")

    fun prepare(value: String?): String? {
        val name = value?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        if (name.length !in 1..MAX_LENGTH || clock.containsMatchIn(name)) return null
        val hasLetters = name.any(Char::isLetter)
        val isPhone = phone.matches(name) && name.count(Char::isDigit) in 10..15
        if (!hasLetters && !isPhone) return null
        val key = name.lowercase()
        if (key in genericTitles || key.startsWith("last seen") ||
            Regex("^\\d+\\s+(members|participants)$").matches(key)
        ) return null
        return name
    }

    /** The header is read on device and never sent with the cropped conversation image. */
    fun fromHeaderLines(lines: List<String>): String? {
        val cleaned = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val titleIndex = cleaned.indexOfFirst { prepare(it) != null }
        if (titleIndex < 0) return null
        val title = cleaned[titleIndex]
        val subtitles = cleaned.drop(titleIndex + 1)
        if (subtitles.any { subtitle ->
                subtitle.contains(',') || subtitle.contains("participants", ignoreCase = true) ||
                    subtitle.contains("members", ignoreCase = true)
            }) return null
        return prepare(title.removeSuffix(" · practice chat"))
    }

    const val MAX_LENGTH = 80
}
