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

/** Matches a notification sender name to a voice profile conservatively.
 * Comparison happens on whole-word tokens, never raw substrings, so "Ann"
 * matches "Message from Ann" but can never match "Joanne". Every word of the
 * profile name must be present in the sender string; extra sender words such
 * as "Message from" are tolerated, but partial overlaps like "Mary" matching
 * the profile "Mary Jane" are rejected because they cannot disambiguate
 * between people who share a first name. */
object ProfileMatcher {
    private val tokenSeparator = Regex("[^\\p{L}\\p{Nd}]+")

    fun matches(sender: String, profileName: String): Boolean {
        val senderTokens = tokenize(sender)
        val profileTokens = tokenize(profileName)
        if (senderTokens.isEmpty() || profileTokens.isEmpty()) return false
        return profileTokens.all { it in senderTokens }
    }

    fun matches(sender: String, profile: VoiceProfile): Boolean = matches(sender, profile.name)

    private fun tokenize(value: String): List<String> =
        value.lowercase().trim().split(tokenSeparator).filter(String::isNotBlank)
}
