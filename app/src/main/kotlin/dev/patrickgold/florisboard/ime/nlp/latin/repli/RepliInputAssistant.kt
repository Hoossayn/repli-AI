package dev.patrickgold.florisboard.ime.nlp.latin.repli

import android.content.Context
import android.os.Trace
import dev.patrickgold.florisboard.repli.diagnostics.CaptureTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the offline dictionary and the private, bounded language model used by the active IME. */
class RepliInputAssistant(context: Context, private val onReady: () -> Unit) {
    private val appContext = context.applicationContext
    private val engine = WordPredictionEngine()
    private val repository = AdaptiveLanguageRepository(appContext)
    private val preferences = KeyboardLearningPreferences(appContext)
    private val initialRevision = preferences.revision
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()
    @Volatile private var model = AdaptiveLanguageModel()
    @Volatile private var ready = false
    private var saveJob: Job? = null
    private val predictionCache = PredictionCache(
        predict = { context, private -> engine.suggest(context, includeAdaptive = !private) },
        correct = { context, private -> engine.autocorrection(context, includeAdaptive = !private) },
        fastCorrect = { context, private -> engine.autocorrection(context, includeAdaptive = !private, allowFuzzy = false) },
    )
    val predictionMetrics: PredictionMetrics.Snapshot get() = predictionCache.metrics.snapshot()
    fun resetPredictionMetrics() = predictionCache.metrics.reset()

    init {
        engine.setAdaptiveEnabled(preferences.enabled)
        scope.launch {
            runCatching {
                appContext.assets.open("ime/dict/repli-en_us.dict").use { main ->
                    val supplements = listOf("ime/dict/repli-en_ng.supplement", "ime/dict/repli-ng_languages.supplement")
                        .mapNotNull { name -> runCatching { appContext.assets.open(name) }.getOrNull() }
                    try {
                        BundledKeyboardLexicon.load(main, supplements)
                    } finally {
                        supplements.forEach { it.close() }
                    }
                }
            }.onSuccess { engine.installLexicon(it); predictionCache.invalidate() }
            runCatching {
                appContext.assets.open("ime/dict/repli-en_chat.ngrams").use(ChatNgramModel::load)
            }.onSuccess { engine.installChatModel(it); predictionCache.invalidate() }
            val loaded = fileMutex.withLock { repository.load() }
            if (initialRevision == preferences.revision) {
                model = loaded
                engine.installAdaptiveModel(loaded)
                predictionCache.invalidate()
            }
            ready = true
            onReady()
        }
    }

    fun suggest(context: TypingContext, privateSession: Boolean = false): List<WordPrediction> {
        Trace.beginSection("Repli.wordSuggestions")
        return try { predictionCache.suggestions(context, privateSession) } finally { Trace.endSection() }
    }

    fun autocorrection(context: TypingContext, privateSession: Boolean = false): WordPrediction? {
        Trace.beginSection("Repli.wordCorrection")
        return try { predictionCache.autocorrection(context, privateSession) } finally { Trace.endSection() }
    }

    fun forgetInputContext() = predictionCache.invalidate()

    /**
     * The user undid an autocorrection. The word is never corrected again this session, and when
     * learning is on (and the field is not private) it is learned so the choice persists.
     */
    fun rejectCorrection(contextBeforeWord: String, originalWord: String, privateSession: Boolean = false) {
        engine.rejectCorrection(originalWord)
        predictionCache.invalidate()
        if (!privateSession) learn(contextBeforeWord, originalWord)
    }

    /** Records the last host field's kind and whether predictions applied (no text, no identifiers beyond the package). */
    fun noteField(packageName: String?, description: String) {
        CaptureTelemetry(appContext).recordLastField("${packageName ?: "?"} · $description")
    }

    /** Remembers an emoji the user inserted, so a favourite can be offered after a sentence. */
    fun learnEmoji(emoji: String) {
        if (!ready || !preferences.enabled) return
        if (model.recordEmoji(emoji)) { predictionCache.invalidate(); scheduleSave() }
    }

    fun learn(contextBeforeWord: String, committedWord: String) {
        if (!ready || !preferences.enabled) return
        val previous = WORD.findAll(contextBeforeWord.lowercase()).map { it.value }.toList().takeLast(2)
        if (model.observe(previous, committedWord)) { predictionCache.invalidate(); scheduleSave() }
    }

    fun setLearningEnabled(enabled: Boolean) {
        preferences.enabled = enabled
        engine.setAdaptiveEnabled(enabled)
        predictionCache.invalidate()
        onReady()
    }

    fun isLearningEnabled(): Boolean = preferences.enabled

    fun clearLearning() {
        preferences.markCleared()
        saveJob?.cancel()
        model = AdaptiveLanguageModel().also(engine::installAdaptiveModel)
        predictionCache.invalidate()
        scope.launch { fileMutex.withLock { repository.clear() } }
        onReady()
    }

    private fun scheduleSave() {
        val revision = preferences.revision
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600)
            fileMutex.withLock {
                if (revision == preferences.revision) repository.save(model)
            }
        }
    }

    private companion object {
        val WORD = Regex("[\\p{L}]+(?:'[\\p{L}]+)?")
    }
}
