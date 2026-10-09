package dev.patrickgold.florisboard.ime.nlp.latin.repli

import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Static next-word prior built from a conversational corpus (see utils/build_chat_ngrams.py).
 *
 * Read-only and fully offline. It answers two questions: which words commonly start a message,
 * and which word commonly follows the last one or two words. The user's own adaptive model
 * always outranks it; this only fills in when the user has not typed a phrase before.
 */
class ChatNgramModel private constructor(
    private val starts: List<ScoredWord>,
    private val unigrams: Map<String, Int>,
    private val bigrams: Map<String, List<ScoredWord>>,
    private val trigrams: Map<String, List<ScoredWord>>,
) {
    data class ScoredWord(val word: String, val count: Int)

    fun sentenceStarts(limit: Int): List<ScoredWord> = starts.take(limit.coerceAtLeast(0))

    /** How often [word] appears in chat messages; 0 when unseen. Used to rank corrections. */
    fun chatCount(word: String): Int = unigrams[word.normalized()] ?: 0

    /**
     * Candidates after [previousWords] (oldest first). Standard back-off: every trigram candidate
     * outranks every bigram-only candidate, and counts order words within a tier. Empty when
     * nothing is known.
     */
    fun nextWords(previousWords: List<String>, limit: Int): List<ScoredWord> {
        if (limit <= 0) return emptyList()
        val context = previousWords.map { it.normalized() }.filter(String::isNotEmpty).takeLast(2)
        if (context.isEmpty()) return emptyList()
        val scores = LinkedHashMap<String, Long>()
        if (context.size == 2) {
            trigrams[context.joinToString(" ")]?.forEach { (word, count) ->
                scores[word] = TRIGRAM_TIER + count.toLong()
            }
        }
        bigrams[context.last()]?.forEach { (word, count) ->
            scores[word] = maxOf(scores[word] ?: 0L, count.toLong())
        }
        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { ScoredWord(it.key, it.value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
    }

    fun isEmpty(): Boolean = starts.isEmpty() && bigrams.isEmpty() && trigrams.isEmpty()

    companion object {
        private const val TRIGRAM_TIER = 1_000_000L
        private val GZIP_MAGIC = byteArrayOf(0x1f.toByte(), 0x8b.toByte())

        fun load(input: InputStream): ChatNgramModel {
            var starts: List<ScoredWord> = emptyList()
            val unigrams = HashMap<String, Int>()
            val bigrams = HashMap<String, List<ScoredWord>>()
            val trigrams = HashMap<String, List<ScoredWord>>()
            maybeGunzip(input).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val columns = line.split('\t')
                    when (columns[0]) {
                        "S" -> if (columns.size >= 2) starts = parseCandidates(columns[1])
                        "U" -> if (columns.size >= 2) parseCandidates(columns[1]).forEach { unigrams[it.word] = it.count }
                        "B" -> if (columns.size >= 3) bigrams[columns[1].normalized()] = parseCandidates(columns[2])
                        "T" -> if (columns.size >= 3) trigrams[columns[1].normalized()] = parseCandidates(columns[2])
                    }
                }
            }
            return ChatNgramModel(starts, unigrams, bigrams, trigrams)
        }

        private fun parseCandidates(column: String): List<ScoredWord> = column.split('|').mapNotNull { item ->
            val separator = item.lastIndexOf(':')
            if (separator <= 0) return@mapNotNull null
            val count = item.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null
            val word = item.substring(0, separator).normalized()
            if (word.isEmpty() || count <= 0) null else ScoredWord(word, count)
        }

        private fun maybeGunzip(input: InputStream): InputStream {
            val buffered = if (input.markSupported()) input else input.buffered()
            buffered.mark(GZIP_MAGIC.size)
            val header = ByteArray(GZIP_MAGIC.size)
            val read = buffered.read(header)
            buffered.reset()
            return if (read == GZIP_MAGIC.size && header.contentEquals(GZIP_MAGIC)) GZIPInputStream(buffered) else buffered
        }

        private fun String.normalized(): String = trim().lowercase(Locale.ROOT).replace('’', '\'')
    }
}
