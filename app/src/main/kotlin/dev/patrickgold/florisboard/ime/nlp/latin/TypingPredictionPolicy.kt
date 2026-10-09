package dev.patrickgold.florisboard.ime.nlp.latin

import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.InputAttributes

/** Restrict language predictions to ordinary prose fields, including chat inputs without composing. */
object TypingPredictionPolicy {
    fun allows(info: FlorisEditorInfo): Boolean = allows(info.inputAttributes)

    fun allows(input: InputAttributes): Boolean {
        if (input.type != InputAttributes.Type.TEXT || input.flagTextAutoComplete) return false
        return when (input.variation) {
            InputAttributes.Variation.NORMAL,
            InputAttributes.Variation.SHORT_MESSAGE,
            InputAttributes.Variation.LONG_MESSAGE,
            InputAttributes.Variation.WEB_EDIT_TEXT,
            InputAttributes.Variation.EMAIL_SUBJECT -> true
            else -> false
        }
    }
}
