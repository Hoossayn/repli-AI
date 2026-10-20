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

package dev.patrickgold.florisboard.repli.voice

import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyPrivacyPolicy
import java.util.Locale

object VoiceGuidanceText {
    fun combine(draft: String, transcript: String): String? {
        val text = transcript.trim()
        if (text.isBlank()) return null
        val combined = if (draft.isBlank()) text else draft + (if (draft.endsWith("\n")) "" else "\n") + text
        return combined.takeIf { it.length <= RemoteReplyPrivacyPolicy.MAX_INSTRUCTION_CHARACTERS }
    }

    /** Regional fallback is allowed only within the user's language, and only to an installed model. */
    fun installedLanguage(requested: String, installed: List<String>): String? =
        installed.firstOrNull { it.equals(requested, ignoreCase = true) }
            ?: installed.firstOrNull { Locale.forLanguageTag(it).language == Locale.forLanguageTag(requested).language }
}
