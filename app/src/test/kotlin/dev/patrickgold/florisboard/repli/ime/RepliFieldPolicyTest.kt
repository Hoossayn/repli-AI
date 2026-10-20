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

package dev.patrickgold.florisboard.repli.ime

import android.text.InputType
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepliFieldPolicyTest {
    @Test fun `chat capture remains available when a host disables dictionary suggestions`() {
        val noSuggestions = InputAttributes.wrap(InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val autoComplete = InputAttributes.wrap(InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)
        assertTrue(RepliFieldPolicy.allows(noSuggestions))
        assertTrue(RepliFieldPolicy.allows(autoComplete))
    }

    @Test fun `chat capture stays unavailable in credentials and non-chat fields`() {
        listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_NUMBER,
        ).forEach { assertFalse(RepliFieldPolicy.allows(InputAttributes.wrap(it))) }
    }
}
