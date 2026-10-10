package dev.patrickgold.florisboard.ime.nlp.latin

import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.InputAttributes

/**
 * Which host fields get word suggestions and which also get autocorrect on space.
 *
 * Suggestions: any text field that is not a password, address, email, URI, date/time or
 * phonetic field. Hosts that ask for no suggestions still get them (chat apps set that flag
 * for their own reasons); hosts with their own auto-complete dropdown get them too.
 *
 * Autocorrect: a stricter subset. Fields that typically hold names, search terms or values
 * the host completes itself are left alone, because a wrong "fix" there is worse than none.
 */
object TypingPredictionPolicy {
    private val proseVariations = setOf(
        InputAttributes.Variation.NORMAL,
        InputAttributes.Variation.SHORT_MESSAGE,
        InputAttributes.Variation.LONG_MESSAGE,
        InputAttributes.Variation.WEB_EDIT_TEXT,
        InputAttributes.Variation.EMAIL_SUBJECT,
    )
    private val suggestOnlyVariations = setOf(
        InputAttributes.Variation.PERSON_NAME,
        InputAttributes.Variation.FILTER,
    )

    fun allows(info: FlorisEditorInfo): Boolean = allows(info.inputAttributes)

    fun allows(input: InputAttributes): Boolean = reason(input) == null

    fun allowsAutocorrect(info: FlorisEditorInfo): Boolean = allowsAutocorrect(info.inputAttributes)

    fun allowsAutocorrect(input: InputAttributes): Boolean =
        allows(input) && input.variation in proseVariations && !input.flagTextAutoComplete

    /** Null when suggestions are allowed; otherwise a short, loggable reason. */
    fun reason(input: InputAttributes): String? {
        if (input.type != InputAttributes.Type.TEXT) return "type ${input.type.name.lowercase()}"
        if (input.variation !in proseVariations && input.variation !in suggestOnlyVariations) {
            return "variation ${input.variation.name.lowercase()}"
        }
        return null
    }

    /** One line for diagnostics: "text/short_message · suggestions on · autocorrect off (auto-complete)". */
    fun describe(input: InputAttributes): String {
        val kind = "${input.type.name.lowercase()}/${input.variation.name.lowercase()}" +
            (if (input.flagTextAutoComplete) "+autocomplete" else "") +
            (if (input.flagTextNoSuggestions) "+nosuggestions" else "") +
            (if (input.flagTextAutoCorrect) "+autocorrect" else "")
        val suggestions = reason(input)?.let { "suggestions off ($it)" } ?: "suggestions on"
        val autocorrect = if (allowsAutocorrect(input)) "autocorrect on" else "autocorrect off"
        return "$kind · $suggestions · $autocorrect"
    }
}
