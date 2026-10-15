package dev.patrickgold.florisboard.ime.nlp.latin.repli

import java.util.Locale

data class AdaptiveCount(
    val value: String,
    val count: Int,
    val lastUsed: Long,
)

data class AdaptiveTransition(
    val context: String,
    val nextWord: String,
    val count: Int,
    val lastUsed: Long,
)

data class AdaptivePrediction(val word: String, val observations: Int, val specificContext: Boolean, val recency: Double)

data class AdaptiveLanguageSnapshot(
    val sequence: Long,
    val words: List<AdaptiveCount>,
    val bigrams: List<AdaptiveTransition>,
    val trigrams: List<AdaptiveTransition>,
    val recentEmojis: List<String>,
)

/**
 * Small private language model built only from text the user commits in eligible editors.
 *
 * Counts make repeated choices win while [lastUsed] lets newer habits break ties. The model is
 * deliberately bounded: pruning happens outside the keypress-critical query path and prevents
 * long-term storage from growing with every word ever typed.
 */
class AdaptiveLanguageModel private constructor(
    private var sequence: Long,
    private val words: MutableMap<String, Stat>,
    private val bigrams: MutableMap<TransitionKey, Stat>,
    private val trigrams: MutableMap<TransitionKey, Stat>,
    private val emojis: MutableList<String>,
) {
    constructor() : this(0, LinkedHashMap(), LinkedHashMap(), LinkedHashMap(), ArrayList())

    @Synchronized
    fun observe(previousWords: List<String>, committedWord: String): Boolean {
        val word = committedWord.normalizedWord() ?: return false
        val context = previousWords.mapNotNull(String::normalizedWord).takeLast(2)
        sequence += 1
        words.bump(word, sequence)
        context.lastOrNull()?.let { previous ->
            bigrams.bump(TransitionKey(previous, word), sequence)
        }
        if (context.size == 2) {
            trigrams.bump(TransitionKey(context.joinToString(CONTEXT_SEPARATOR), word), sequence)
        }
        pruneIfNeeded(words, MAX_WORDS)
        pruneIfNeeded(bigrams, MAX_BIGRAMS)
        pruneIfNeeded(trigrams, MAX_TRIGRAMS)
        return true
    }

    @Synchronized
    fun completions(prefix: String, limit: Int): List<String> {
        val query = prefix.lowercase(Locale.ROOT).replace('’', '\'')
        if (query.length < 2 || limit <= 0) return emptyList()
        return words.asSequence()
            .filter { (word, _) -> word != query && word.startsWith(query) }
            .sortedWith(compareByDescending<Map.Entry<String, Stat>> { it.value.rank(sequence) }.thenBy { it.key.length })
            .take(limit)
            .map(Map.Entry<String, Stat>::key)
            .toList()
    }

    @Synchronized
    fun corrections(word: String, limit: Int): List<String> {
        val query = word.normalizedWord() ?: return emptyList()
        if (query in words || limit <= 0) return emptyList()
        return words.asSequence()
            .filter { (_, stat) -> stat.count >= MIN_CORRECTION_OBSERVATIONS }
            .filter { (candidate, _) -> BundledKeyboardLexicon.oneEditApart(query, candidate) }
            .sortedByDescending { it.value.rank(sequence) }
            .take(limit)
            .map(Map.Entry<String, Stat>::key)
            .toList()
    }

    @Synchronized
    fun contains(word: String): Boolean = word.normalizedWord()?.let(words::containsKey) == true

    @Synchronized
    fun wordObservations(word: String): Int = word.normalizedWord()?.let { words[it]?.count } ?: 0

    @Synchronized
    fun nextWordEvidence(previousWords: List<String>, limit: Int): List<AdaptivePrediction> {
        val context = previousWords.mapNotNull(String::normalizedWord).takeLast(2)
        if (context.isEmpty() || limit <= 0) return emptyList()
        val evidence = LinkedHashMap<String, AdaptivePrediction>()
        fun offer(transition: TransitionKey, stat: Stat, specific: Boolean) {
            val old = evidence[transition.nextWord]
            evidence[transition.nextWord] = AdaptivePrediction(
                transition.nextWord, maxOf(old?.observations ?: 0, stat.count),
                specific || old?.specificContext == true,
                maxOf(old?.recency ?: 0.0, stat.recency(sequence).toDouble() / RECENCY_WINDOW),
            )
        }
        bigrams.forEach { (transition, stat) -> if (transition.context == context.last()) offer(transition, stat, false) }
        if (context.size == 2) {
            val pair = context.joinToString(CONTEXT_SEPARATOR)
            trigrams.forEach { (transition, stat) -> if (transition.context == pair) offer(transition, stat, true) }
        }
        return evidence.values.sortedByDescending { it.observations * 10 + if (it.specificContext) 1 else 0 }.take(limit)
    }

    @Synchronized
    fun nextWords(previousWords: List<String>, limit: Int): List<String> {
        val context = previousWords.mapNotNull(String::normalizedWord).takeLast(2)
        if (context.isEmpty() || limit <= 0) return emptyList()
        val scores = HashMap<String, Long>()
        val latest = context.last()
        bigrams.forEach { (transition, stat) ->
            if (transition.context == latest) {
                scores[transition.nextWord] = scores.getOrDefault(transition.nextWord, 0) + stat.count * BIGRAM_WEIGHT + stat.recency(sequence)
            }
        }
        if (context.size == 2) {
            val pair = context.joinToString(CONTEXT_SEPARATOR)
            trigrams.forEach { (transition, stat) ->
                if (transition.context == pair) {
                    scores[transition.nextWord] = scores.getOrDefault(transition.nextWord, 0) + stat.count * TRIGRAM_WEIGHT + stat.recency(sequence)
                }
            }
        }
        return scores.entries.sortedByDescending(Map.Entry<String, Long>::value).take(limit).map(Map.Entry<String, Long>::key)
    }

    @Synchronized
    fun recordEmoji(emoji: String): Boolean {
        if (emoji.isBlank() || emoji.length > MAX_EMOJI_CODE_UNITS) return false
        emojis.remove(emoji)
        emojis.add(0, emoji)
        while (emojis.size > MAX_RECENT_EMOJIS) emojis.removeAt(emojis.lastIndex)
        return true
    }

    @Synchronized
    fun recentEmojis(): List<String> = emojis.toList()

    @Synchronized
    fun snapshot(): AdaptiveLanguageSnapshot {
        // The in-memory maps may temporarily use a small overflow batch to avoid sorting on every
        // newly learned word. Persisted snapshots always honor the strict file-format bounds.
        pruneToMaximum(words, MAX_WORDS)
        pruneToMaximum(bigrams, MAX_BIGRAMS)
        pruneToMaximum(trigrams, MAX_TRIGRAMS)
        return AdaptiveLanguageSnapshot(
            sequence = sequence,
            words = words.map { (value, stat) -> AdaptiveCount(value, stat.count, stat.lastUsed) },
            bigrams = bigrams.map { (key, stat) -> AdaptiveTransition(key.context, key.nextWord, stat.count, stat.lastUsed) },
            trigrams = trigrams.map { (key, stat) -> AdaptiveTransition(key.context, key.nextWord, stat.count, stat.lastUsed) },
            recentEmojis = emojis.toList(),
        )
    }

    private fun <K> MutableMap<K, Stat>.bump(key: K, usedAt: Long) {
        val current = this[key]
        this[key] = Stat((current?.count ?: 0).plus(1).coerceAtMost(MAX_COUNT), usedAt)
    }

    private fun <K> pruneIfNeeded(values: MutableMap<K, Stat>, maximum: Int) {
        if (values.size <= maximum + PRUNE_BATCH) return
        pruneToMaximum(values, maximum)
    }

    private fun <K> pruneToMaximum(values: MutableMap<K, Stat>, maximum: Int) {
        if (values.size <= maximum) return
        values.entries.sortedBy { it.value.rank(sequence) }.take(values.size - maximum).forEach { values.remove(it.key) }
    }

    private data class Stat(val count: Int, val lastUsed: Long) {
        fun rank(currentSequence: Long): Long = count.toLong() * COUNT_WEIGHT + recency(currentSequence)
        fun recency(currentSequence: Long): Long = (RECENCY_WINDOW - (currentSequence - lastUsed)).coerceAtLeast(0)
    }

    private data class TransitionKey(val context: String, val nextWord: String)

    companion object {
        const val MAX_WORDS = 2_000
        const val MAX_BIGRAMS = 4_000
        const val MAX_TRIGRAMS = 4_000
        const val MAX_RECENT_EMOJIS = 48
        private const val MAX_COUNT = 10_000
        private const val MAX_EMOJI_CODE_UNITS = 32
        private const val MIN_CORRECTION_OBSERVATIONS = 2
        private const val PRUNE_BATCH = 128
        private const val COUNT_WEIGHT = 100_000L
        private const val BIGRAM_WEIGHT = 1_000_000L
        private const val TRIGRAM_WEIGHT = 10_000_000L
        private const val RECENCY_WINDOW = 10_000L
        const val CONTEXT_SEPARATOR = "\u0001"

        fun from(snapshot: AdaptiveLanguageSnapshot): AdaptiveLanguageModel {
            val model = AdaptiveLanguageModel()
            model.sequence = snapshot.sequence.coerceAtLeast(0)
            snapshot.words.take(MAX_WORDS).forEach { item ->
                item.value.normalizedWord()?.let { model.words[it] = Stat(item.count.validCount(), item.lastUsed.validUsage(model.sequence)) }
            }
            snapshot.bigrams.take(MAX_BIGRAMS).forEach { item ->
                if (item.context.normalizedContext() != null && item.nextWord.normalizedWord() != null) {
                    model.bigrams[TransitionKey(item.context, item.nextWord)] = Stat(item.count.validCount(), item.lastUsed.validUsage(model.sequence))
                }
            }
            snapshot.trigrams.take(MAX_TRIGRAMS).forEach { item ->
                if (item.context.normalizedContext(parts = 2) != null && item.nextWord.normalizedWord() != null) {
                    model.trigrams[TransitionKey(item.context, item.nextWord)] = Stat(item.count.validCount(), item.lastUsed.validUsage(model.sequence))
                }
            }
            snapshot.recentEmojis.take(MAX_RECENT_EMOJIS).filter { it.isNotBlank() && it.length <= MAX_EMOJI_CODE_UNITS }
                .forEach { if (it !in model.emojis) model.emojis += it }
            return model
        }

        private fun String.normalizedContext(parts: Int = 1): String? {
            val normalized = split(CONTEXT_SEPARATOR).mapNotNull(String::normalizedWord)
            return takeIf { normalized.size == parts && normalized.joinToString(CONTEXT_SEPARATOR) == this }
        }

        private fun Int.validCount() = coerceIn(1, MAX_COUNT)
        private fun Long.validUsage(sequence: Long) = coerceIn(0, sequence)
    }
}

private fun String.normalizedWord(): String? {
    val normalized = lowercase(Locale.ROOT).replace('’', '\'').trim('\'')
    return normalized.takeIf {
        it.length in 2..32 && it.any(Char::isLetter) && it.all { character -> character.isLetter() || character == '\'' }
    }
}
