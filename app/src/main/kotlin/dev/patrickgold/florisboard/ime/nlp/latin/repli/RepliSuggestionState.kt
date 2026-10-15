package dev.patrickgold.florisboard.ime.nlp.latin.repli

/** One atomic UI snapshot: a pending calculation is distinct from an empty completed result. */
data class RepliSuggestionState(
    val active: Boolean = false,
    val pending: Boolean = false,
    val candidates: List<WordPrediction> = emptyList(),
)
