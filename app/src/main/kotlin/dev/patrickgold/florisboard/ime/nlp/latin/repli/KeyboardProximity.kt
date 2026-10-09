package dev.patrickgold.florisboard.ime.nlp.latin.repli

import kotlin.math.min

/**
 * QWERTY neighbourhood and a weighted Damerau-Levenshtein distance for typo correction.
 * A substitution between neighbouring keys costs half an edit, so "tje" → "the" is nearly
 * free while "tze" → "the" is a full edit; adjacent transpositions are cheaper than two edits.
 */
object KeyboardProximity {
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val rowOffset = doubleArrayOf(0.0, 0.5, 1.0)
    private val position: Map<Char, Pair<Int, Double>> = buildMap {
        rows.forEachIndexed { row, keys ->
            keys.forEachIndexed { column, key -> put(key, row to column + rowOffset[row]) }
        }
    }
    private val neighbours: Map<Char, Set<Char>> = position.mapValues { (key, at) ->
        position.filter { (other, otherAt) ->
            other != key && kotlin.math.abs(otherAt.first - at.first) <= 1 &&
                kotlin.math.abs(otherAt.second - at.second) <= 1.0
        }.keys
    }

    fun adjacent(a: Char, b: Char): Boolean = a != b && neighbours[a]?.contains(b) == true

    const val SUBSTITUTION = 1.0
    const val ADJACENT_SUBSTITUTION = 0.5
    const val INSERTION = 1.0
    const val DELETION = 1.0
    const val TRANSPOSITION = 0.7

    /**
     * Weighted optimal-string-alignment distance between [typed] and [candidate], capped so the
     * caller can bail out early: returns a value > [limit] as soon as it cannot stay within it.
     */
    fun distance(typed: String, candidate: String, limit: Double = Double.MAX_VALUE): Double {
        val n = typed.length
        val m = candidate.length
        if (n == 0) return m * INSERTION
        if (m == 0) return n * DELETION
        if (kotlin.math.abs(n - m) * min(INSERTION, DELETION) > limit) return limit + 1.0
        var prev2: DoubleArray? = null
        var prev = DoubleArray(m + 1) { it * INSERTION }
        for (i in 1..n) {
            val current = DoubleArray(m + 1)
            current[0] = i * DELETION
            var rowMin = current[0]
            for (j in 1..m) {
                val a = typed[i - 1]
                val b = candidate[j - 1]
                val substitute = when {
                    a == b -> 0.0
                    adjacent(a, b) -> ADJACENT_SUBSTITUTION
                    else -> SUBSTITUTION
                }
                var best = min(prev[j] + DELETION, min(current[j - 1] + INSERTION, prev[j - 1] + substitute))
                if (i > 1 && j > 1 && a == candidate[j - 2] && typed[i - 2] == b) {
                    best = min(best, prev2!![j - 2] + TRANSPOSITION)
                }
                current[j] = best
                if (best < rowMin) rowMin = best
            }
            if (rowMin > limit) return limit + 1.0
            prev2 = prev
            prev = current
        }
        return prev[m]
    }
}
