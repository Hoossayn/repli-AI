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

import dev.patrickgold.florisboard.repli.profile.VoiceProfile
import kotlin.math.abs

/** Resolves two readings of one captured chat against saved profiles. */
object CapturedContactProfileMatcher {
    sealed interface Result {
        data class Existing(val profile: VoiceProfile) : Result
        data class New(val name: String) : Result
    }

    fun resolve(localHeaderName: String?, aiName: String?, profiles: List<VoiceProfile>): Result? {
        // The on-device header is the chat title itself. AI sees only the cropped messages
        // and may infer a different spelling from quoted names or conversation text.
        val local = CapturedContactName.prepare(localHeaderName)
        val ai = CapturedContactName.prepare(aiName)
        val primary = local ?: ai
            ?: return null
        val key = identityKey(primary)
        val exact = profiles.filter { profile ->
            (listOf(profile.name) + profile.nameAliases).any { identityKey(it) == key }
        }
        if (exact.size == 1) return Result.Existing(exact.single())
        if (exact.size > 1) return null

        // If an earlier AI reading saved a close misspelling, the two readings from this
        // one capture provide evidence that its saved profile represents this chat.
        if (local != null && ai != null && sameCaptureVariant(key, identityKey(ai))) {
            val aiMatches = profiles.filter {
                it.relationship == CAPTURE_RELATIONSHIP && identityKey(it.name) == identityKey(ai)
            }
            if (aiMatches.size == 1) return Result.Existing(aiMatches.single())
        }

        // A single insertion, deletion, substitution, or transposition in a long name is
        // likely an OCR error. Never guess between multiple close profiles or short names.
        val close = profiles.filter { nearlySameName(key, identityKey(it.name)) }
        return when (close.size) {
            0 -> Result.New(primary)
            1 -> Result.Existing(close.single())
            else -> null
        }
    }

    private fun identityKey(name: String): String = CapturedContactName.identityKey(name)

    private fun nearlySameName(left: String, right: String): Boolean {
        if (left.length < MIN_FUZZY_LENGTH || right.length < MIN_FUZZY_LENGTH) return false
        if (left.any(Char::isDigit) || right.any(Char::isDigit)) return false
        if (abs(left.length - right.length) > 1) return false
        if (left.length == right.length) {
            val differences = left.indices.filter { left[it] != right[it] }
            return differences.size == 1 ||
                (differences.size == 2 && differences[1] == differences[0] + 1 &&
                    left[differences[0]] == right[differences[1]] &&
                    left[differences[1]] == right[differences[0]])
        }
        val shorter = if (left.length < right.length) left else right
        val longer = if (left.length > right.length) left else right
        var shortIndex = 0
        var longIndex = 0
        var skipped = false
        while (shortIndex < shorter.length && longIndex < longer.length) {
            if (shorter[shortIndex] == longer[longIndex]) {
                shortIndex++
            } else if (skipped) {
                return false
            } else {
                skipped = true
            }
            longIndex++
        }
        return true
    }

    private fun sameCaptureVariant(left: String, right: String): Boolean {
        if (left.length < MIN_FUZZY_LENGTH || right.length < MIN_FUZZY_LENGTH) return false
        if (left.any(Char::isDigit) || right.any(Char::isDigit)) return false
        if (abs(left.length - right.length) > 2) return false
        var previous = IntArray(right.length + 1) { it }
        for (leftIndex in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[right.length] <= 2
    }

    private const val MIN_FUZZY_LENGTH = 8
    private const val CAPTURE_RELATIONSHIP = "Added from AI capture"
}
