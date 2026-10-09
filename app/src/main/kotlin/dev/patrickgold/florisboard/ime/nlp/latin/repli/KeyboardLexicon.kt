package dev.patrickgold.florisboard.ime.nlp.latin.repli

import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

data class LexiconWord(val word: String, val frequency: Int)

/** Read-only language data used by the keyboard. Implementations must stay fully offline. */
interface KeyboardLexicon {
    fun completions(prefix: String, limit: Int): List<LexiconWord>
    fun corrections(word: String, limit: Int): List<LexiconWord>
    fun nextWords(previousWord: String, limit: Int): List<LexiconWord>
    fun contains(word: String): Boolean
    fun frequency(word: String): Int = 0
}

/**
 * Compact in-memory reader for the bundled AOSP-derived lexicon.
 *
 * Loading and index creation are intentionally done off the IME thread. Lookups are bounded and
 * allocation-light so showing suggestions does not delay key presses.
 */
class BundledKeyboardLexicon private constructor(
    private val entries: List<Entry>,
    private val words: Map<String, Entry>,
    private val deleteIndex: Map<String, List<Entry>>,
) : KeyboardLexicon {
    override fun completions(prefix: String, limit: Int): List<LexiconWord> {
        val query = prefix.normalized()
        if (query.isEmpty() || limit <= 0) return emptyList()
        val best = ArrayList<Entry>(limit)
        var index = lowerBound(query)
        while (index < entries.size) {
            val entry = entries[index++]
            if (!entry.normalized.startsWith(query)) break
            insertBest(best, entry, limit) { candidate ->
                candidate.frequency * 100 - candidate.word.length
            }
        }
        return best.map(Entry::asLexiconWord)
    }

    override fun corrections(word: String, limit: Int): List<LexiconWord> {
        val query = word.normalized()
        if (query.length !in MIN_CORRECTION_LENGTH..MAX_CORRECTION_LENGTH || limit <= 0 || query in words) {
            return emptyList()
        }
        val candidates = LinkedHashSet<Entry>()
        deletions(query).forEach { deleted ->
            words[deleted]?.let(candidates::add) // The typed word contains one extra character.
            deleteIndex[deleted]?.let(candidates::addAll) // Substitution candidates.
        }
        deleteIndex[query]?.let(candidates::addAll) // The typed word is missing one character.
        for (index in 0 until query.lastIndex) {
            if (query[index] == query[index + 1]) continue
            val swapped = query.toCharArray().also { chars ->
                val held = chars[index]
                chars[index] = chars[index + 1]
                chars[index + 1] = held
            }.concatToString()
            words[swapped]?.let(candidates::add)
        }
        return candidates.asSequence()
            .filter { oneEditApart(query, it.normalized) }
            .sortedWith(
                compareBy<Entry> { if (isMissingApostrophe(query, it.normalized)) 0 else 1 }
                    .thenByDescending { it.frequency }
                    .thenBy { it.word.length }
                    .thenBy { it.word },
            )
            .take(limit)
            .map(Entry::asLexiconWord)
            .toList()
    }

    override fun nextWords(previousWord: String, limit: Int): List<LexiconWord> {
        if (limit <= 0) return emptyList()
        return words[previousWord.normalized()]?.next.orEmpty()
            .asSequence()
            .mapNotNull { (nextWord, weight) -> words[nextWord.normalized()]?.let { it to weight } }
            .sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenByDescending { it.first.frequency })
            .map { it.first.asLexiconWord() }
            .distinctBy { it.word.normalized() }
            .take(limit)
            .toList()
    }

    override fun contains(word: String): Boolean = word.normalized() in words

    override fun frequency(word: String): Int = words[word.normalized()]?.frequency ?: 0

    private fun lowerBound(query: String): Int {
        var low = 0
        var high = entries.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (entries[middle].normalized < query) low = middle + 1 else high = middle
        }
        return low
    }

    private fun insertBest(best: MutableList<Entry>, candidate: Entry, limit: Int, score: (Entry) -> Int) {
        val candidateScore = score(candidate)
        val position = best.indexOfFirst { existing -> candidateScore > score(existing) }
            .let { if (it < 0) best.size else it }
        if (position < limit) best.add(position, candidate)
        if (best.size > limit) best.removeAt(best.lastIndex)
    }

    private data class Entry(
        val word: String,
        val normalized: String,
        val frequency: Int,
        val next: List<Pair<String, Int>>,
    ) {
        fun asLexiconWord() = LexiconWord(word, frequency)
    }

    companion object {
        private const val MIN_CORRECTION_LENGTH = 3
        private const val MAX_CORRECTION_LENGTH = 20
        private const val CORRECTION_FREQUENCY_FLOOR = 100

        /** Default frequency for supplement words: valid and completable, but below the correction floor. */
        const val SUPPLEMENT_FREQUENCY = 90

        /**
         * Loads the main dictionary plus optional supplement word lists (one word per line, optional
         * tab-separated frequency). A supplement never overrides a word the main dictionary already has.
         */
        fun load(input: InputStream, supplements: List<InputStream> = emptyList()): BundledKeyboardLexicon {
            val main = maybeGunzip(input).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filterNot { it.startsWith('#') || it.isBlank() }.mapNotNull(::parseEntry).toList()
            }
            val known = main.mapTo(HashSet(), Entry::normalized)
            val extra = supplements.flatMap { supplement ->
                maybeGunzip(supplement).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.filterNot { it.startsWith('#') || it.isBlank() }
                        .mapNotNull(::parseSupplementEntry)
                        .filter { known.add(it.normalized) }
                        .toList()
                }
            }
            val entries = (main + extra).sortedBy(Entry::normalized)
            val words = entries.associateBy(Entry::normalized)
            val mutableDeleteIndex = HashMap<String, MutableList<Entry>>()
            entries.asSequence()
                .filter { it.frequency >= CORRECTION_FREQUENCY_FLOOR && it.normalized.length in MIN_CORRECTION_LENGTH..MAX_CORRECTION_LENGTH }
                .forEach { entry ->
                    deletions(entry.normalized).distinct().forEach { deleted ->
                        mutableDeleteIndex.getOrPut(deleted, ::ArrayList).add(entry)
                    }
                }
            return BundledKeyboardLexicon(entries, words, mutableDeleteIndex)
        }

        private fun maybeGunzip(input: InputStream): InputStream {
            val buffered = if (input.markSupported()) input else input.buffered()
            buffered.mark(GZIP_MAGIC.size)
            val header = ByteArray(GZIP_MAGIC.size)
            val read = buffered.read(header)
            buffered.reset()
            return if (read == GZIP_MAGIC.size && header.contentEquals(GZIP_MAGIC)) {
                GZIPInputStream(buffered)
            } else {
                buffered
            }
        }

        private val GZIP_MAGIC = byteArrayOf(0x1f.toByte(), 0x8b.toByte())

        private fun parseEntry(line: String): Entry? {
            val columns = line.split('\t', limit = 3)
            if (columns.size < 2) return null
            val word = columns[0]
            val frequency = columns[1].toIntOrNull() ?: return null
            val next = columns.getOrNull(2).orEmpty().split('|').mapNotNull { item ->
                val separator = item.lastIndexOf(':')
                if (separator <= 0) null else item.substring(0, separator) to
                    (item.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null)
            }
            // 40% of the shipped rows carry a placeholder next-word column; it carries no signal.
            return Entry(word, word.normalized(), frequency, if (next == FILLER_NEXT) emptyList() else next)
        }

        private val FILLER_NEXT = listOf("the" to 1, "to" to 2, "of" to 3)

        private fun parseSupplementEntry(line: String): Entry? {
            val columns = line.trim().split('\t', limit = 2)
            val word = columns[0].trim()
            if (word.isEmpty() || word.any { !(it.isLetter() || it == '\'' || it == '’') }) return null
            val frequency = columns.getOrNull(1)?.trim()?.toIntOrNull() ?: SUPPLEMENT_FREQUENCY
            return Entry(word, word.normalized(), frequency, emptyList())
        }

        private fun deletions(word: String): List<String> = word.indices.map { index -> word.removeRange(index, index + 1) }

        /**
         * True when the candidate is exactly the typed word with one apostrophe added.
         * Omitting apostrophes is far more common than the reverse typo, so "hows" means
         * "how's" even when a plain rival ("how") ranks higher by raw frequency.
         */
        private fun isMissingApostrophe(query: String, candidate: String): Boolean {
            if (candidate.length != query.length + 1) return false
            val withoutApostrophe = StringBuilder(candidate.length - 1)
            var skipped = false
            for (char in candidate) {
                if (!skipped && char == '\'') {
                    skipped = true
                    continue
                }
                withoutApostrophe.append(char)
            }
            return skipped && withoutApostrophe.toString() == query
        }

        /** True for insertion, deletion, substitution, or one adjacent transposition. */
        internal fun oneEditApart(first: String, second: String): Boolean {
            if (first == second || kotlin.math.abs(first.length - second.length) > 1) return false
            if (first.length == second.length) {
                val differences = first.indices.filter { first[it] != second[it] }
                return differences.size == 1 ||
                    (differences.size == 2 && differences[1] == differences[0] + 1 &&
                        first[differences[0]] == second[differences[1]] && first[differences[1]] == second[differences[0]])
            }
            val shorter = if (first.length < second.length) first else second
            val longer = if (first.length < second.length) second else first
            var shortIndex = 0
            var longIndex = 0
            var skipped = false
            while (shortIndex < shorter.length && longIndex < longer.length) {
                if (shorter[shortIndex] == longer[longIndex]) {
                    shortIndex++; longIndex++
                } else if (skipped) {
                    return false
                } else {
                    skipped = true; longIndex++
                }
            }
            return true
        }
    }
}

private fun String.normalized(): String = lowercase(Locale.ROOT).replace('’', '\'')
