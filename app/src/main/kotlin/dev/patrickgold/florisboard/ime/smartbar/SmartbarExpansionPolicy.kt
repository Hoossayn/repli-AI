package dev.patrickgold.florisboard.ime.smartbar

import dev.patrickgold.florisboard.ime.nlp.latin.repli.RepliSuggestionState

/** Null preserves the current toolbar while a prediction is pending. */
internal object SmartbarExpansionPolicy {
    fun expanded(
        repli: RepliSuggestionState,
        hasLegacyCandidates: Boolean,
        hasInlineCandidates: Boolean,
        hasSelection: Boolean,
    ): Boolean? = when {
        hasSelection -> true
        hasInlineCandidates -> false
        repli.active && repli.pending -> null
        repli.active -> repli.candidates.isEmpty()
        else -> !hasLegacyCandidates
    }
}
