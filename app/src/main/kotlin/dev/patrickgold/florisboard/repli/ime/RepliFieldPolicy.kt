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
