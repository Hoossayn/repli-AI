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

import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.InputAttributes

/** Explicit chat capture does not depend on whether the host wants dictionary suggestions. */
object RepliFieldPolicy {
    fun allows(info: FlorisEditorInfo): Boolean = allows(info.inputAttributes)

    fun allows(input: InputAttributes): Boolean = input.type == InputAttributes.Type.TEXT &&
        input.variation in setOf(
            InputAttributes.Variation.NORMAL,
            InputAttributes.Variation.SHORT_MESSAGE,
            InputAttributes.Variation.LONG_MESSAGE,
            InputAttributes.Variation.WEB_EDIT_TEXT,
            InputAttributes.Variation.EMAIL_SUBJECT,
        )
}
