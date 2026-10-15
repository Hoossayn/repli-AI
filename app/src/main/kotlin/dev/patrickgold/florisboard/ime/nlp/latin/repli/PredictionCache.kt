package dev.patrickgold.florisboard.ime.nlp.latin.repli

import java.util.Locale

/** One bounded input snapshot. Model changes and editor transitions invalidate it. */
class PredictionCache(
    private val predict: (TypingContext, Boolean) -> List<WordPrediction>,
    private val correct: (TypingContext, Boolean) -> WordPrediction?,
    private val fastCorrect: ((TypingContext, Boolean) -> WordPrediction?)? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class Key(val context: TypingContext, val privateSession: Boolean, val revision: Long)
    private data class Entry(
        val key: Key,
        val correction: WordPrediction?,
        val suggestions: List<WordPrediction>? = null,
    )

    private val guard = Any()
    private var revision = 0L
    private var entry: Entry? = null
    private var latestKey: Key? = null
    val metrics = PredictionMetrics()

    fun invalidate() = synchronized(guard) {
        revision++
        entry = null
        latestKey = null
    }

    fun suggestions(context: TypingContext, privateSession: Boolean): List<WordPrediction> {
        val (key, cached) = snapshot(context, privateSession)
        cached?.suggestions?.let {
            metrics.cacheHit()
            return it
        }
        val correction = if (cached != null) cached.correction else timedCorrection(context, privateSession)
        val start = nanoTime()
        val predictions = predict(context, privateSession)
        metrics.record(PredictionMetrics.Operation.SUGGESTIONS, nanoTime() - start)
        val suggestions = (listOfNotNull(correction?.copy(space = true)) + predictions)
            .distinctBy { it.word.lowercase(Locale.ROOT) }
            .take(3)
        store(Entry(key, correction, suggestions))
        return suggestions
    }

    fun autocorrection(context: TypingContext, privateSession: Boolean): WordPrediction? {
        val (key, cached) = snapshot(context, privateSession)
        if (cached != null) {
            metrics.cacheHit()
            return cached.correction
        }
        val correction = if (fastCorrect != null) {
            val start = nanoTime()
            fastCorrect.invoke(context, privateSession).also {
                metrics.record(PredictionMetrics.Operation.CORRECTION, nanoTime() - start)
            }
        } else timedCorrection(context, privateSession)
        // A fast separator fallback is not a completed full prediction result.
        if (fastCorrect != null) return correction
        store(Entry(key, correction))
        return correction
    }

    private fun timedCorrection(context: TypingContext, privateSession: Boolean): WordPrediction? {
        val start = nanoTime()
        return correct(context, privateSession).also {
            metrics.record(PredictionMetrics.Operation.CORRECTION, nanoTime() - start)
        }
    }

    private fun snapshot(context: TypingContext, privateSession: Boolean): Pair<Key, Entry?> = synchronized(guard) {
        val key = Key(context, privateSession, revision)
        latestKey = key
        key to entry?.takeIf { it.key == key }
    }

    private fun store(result: Entry) = synchronized(guard) {
        // Computation runs outside the lock: a late result must not resurrect invalidated data.
        if (result.key.revision == revision && latestKey == result.key &&
            !(entry?.key == result.key && entry?.suggestions != null && result.suggestions == null)
        ) entry = result
    }
}

/** Aggregate durations and counts only; no typed text, words, or editor identifiers. */
class PredictionMetrics {
    enum class Operation { SUGGESTIONS, CORRECTION }
    data class Snapshot(
        val suggestionComputations: Long,
        val correctionComputations: Long,
        val cacheHits: Long,
        val recentP95Micros: Long,
        val worstMicros: Long,
    )

    private val samples = LongArray(128)
    private var sampleCount = 0
    private var sampleIndex = 0
    private var suggestionComputations = 0L
    private var correctionComputations = 0L
    private var cacheHits = 0L
    private var worstNanos = 0L

    @Synchronized fun cacheHit() { cacheHits++ }

    @Synchronized fun record(operation: Operation, elapsedNanos: Long) {
        when (operation) {
            Operation.SUGGESTIONS -> suggestionComputations++
            Operation.CORRECTION -> correctionComputations++
        }
        val elapsed = elapsedNanos.coerceAtLeast(0)
        samples[sampleIndex] = elapsed
        sampleIndex = (sampleIndex + 1) % samples.size
        sampleCount = (sampleCount + 1).coerceAtMost(samples.size)
        worstNanos = maxOf(worstNanos, elapsed)
    }

    @Synchronized fun snapshot(): Snapshot {
        val sorted = samples.take(sampleCount).sorted()
        val p95 = sorted.getOrNull(((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)) ?: 0
        return Snapshot(suggestionComputations, correctionComputations, cacheHits, p95 / 1_000, worstNanos / 1_000)
    }

    @Synchronized fun reset() {
        sampleCount = 0
        sampleIndex = 0
        suggestionComputations = 0
        correctionComputations = 0
        cacheHits = 0
        worstNanos = 0
    }
}
