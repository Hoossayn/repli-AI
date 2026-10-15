package dev.patrickgold.florisboard.ime.nlp.latin.repli

data class TapKey(val letter: Char, val centerX: Float, val centerY: Float, val width: Float, val height: Float)
data class TapAlternative(val letter: Char, val penalty: Double)

/** Normalized, word-local geometry; never persisted or included in diagnostics. */
data class TapEvidence(
    val typed: Char,
    val x: Float,
    val y: Float,
    val uptimeMillis: Long,
    val layoutId: String,
    val alternatives: List<TapAlternative>,
) {
    fun substitutionCost(candidate: Char): Double {
        if (candidate == typed.lowercaseChar()) return 0.0
        val alternative = alternatives.firstOrNull { it.letter == candidate } ?: return 1.5
        val best = alternatives.minOfOrNull { it.penalty } ?: return 1.5
        return (0.25 + 0.45 * (alternative.penalty - best)).coerceIn(0.25, 1.5)
    }

    companion object {
        fun from(typed: Char, x: Float, y: Float, uptimeMillis: Long, layoutId: String, keys: List<TapKey>): TapEvidence? {
            if (!typed.isLetter() || !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
            val scores = keys.filter { it.width > 0 && it.height > 0 && it.width.isFinite() && it.height.isFinite() }
                .mapNotNull { key ->
                    val dx = (x - key.centerX) / (key.width * 0.45)
                    val dy = (y - key.centerY) / (key.height * 0.45)
                    val penalty = (dx * dx + dy * dy) * 0.5
                    if (penalty.isFinite()) TapAlternative(key.letter.lowercaseChar(), penalty) else null
                }
            val original = scores.firstOrNull { it.letter == typed.lowercaseChar() } ?: return null
            val nearby = scores.filter { it.penalty <= original.penalty + 2.0 }
                .sortedBy { it.penalty }.take(3)
            return TapEvidence(typed.lowercaseChar(), x, y, uptimeMillis, layoutId,
                (listOf(original) + nearby).distinctBy { it.letter })
        }
    }
}

/** Aligns touch samples with the current word; missing/pasted characters remain unknown. */
class WordTouchHistory {
    private var word = ""
    private val taps = ArrayList<TapEvidence?>()

    fun record(before: String, emitted: String, tap: TapEvidence?) {
        val prefix = prefix(before)
        if (emitted.length != 1 || !isWordCharacter(emitted[0]) || prefix.length >= MAX_LENGTH) {
            clear()
            return
        }
        if (prefix != word || (tap != null && taps.lastOrNull()?.layoutId?.let { it != tap.layoutId } == true)) {
            clear()
            repeat(prefix.length) { taps += null }
        }
        word = prefix + emitted
        taps += tap?.takeIf { it.typed == emitted[0].lowercaseChar() }
    }

    fun samples(before: String): List<TapEvidence?> = if (prefix(before) == word) taps.toList() else emptyList()

    fun backspace(before: String, after: String) {
        val old = prefix(before)
        val new = prefix(after)
        if (word == old && old.isNotEmpty() && new == old.dropLast(1) && taps.isNotEmpty()) {
            word = new
            taps.removeAt(taps.lastIndex)
        } else clear()
    }

    fun clear() { word = ""; taps.clear() }

    private fun prefix(text: String) = text.takeLastWhile(::isWordCharacter)
    private fun isWordCharacter(char: Char) = char.isLetter() || char == '\'' || char == '’'

    companion object { private const val MAX_LENGTH = 20 }
}
