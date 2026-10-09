package dev.patrickgold.florisboard.ime.nlp.latin.repli

import android.content.Context
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

    init {
        engine.setAdaptiveEnabled(preferences.enabled)
        scope.launch {
            runCatching {
                appContext.assets.open("ime/dict/repli-en_us.dict").use { main ->
                    val supplement = runCatching { appContext.assets.open("ime/dict/repli-en_ng.supplement") }.getOrNull()
                    try {
                        BundledKeyboardLexicon.load(main, listOfNotNull(supplement))
                    } finally {
                        supplement?.close()
                    }
                }
            }.onSuccess(engine::installLexicon)
            runCatching {
                appContext.assets.open("ime/dict/repli-en_chat.ngrams").use(ChatNgramModel::load)
            }.onSuccess(engine::installChatModel)
            val loaded = fileMutex.withLock { repository.load() }
            if (initialRevision == preferences.revision) {
                model = loaded
                engine.installAdaptiveModel(loaded)
            }
            ready = true
            onReady()
        }
    }

    fun suggest(context: TypingContext, privateSession: Boolean = false): List<WordPrediction> {
        val correction = engine.autocorrection(context, includeAdaptive = !privateSession)?.copy(space = true)
        return (listOfNotNull(correction) + engine.suggest(context, includeAdaptive = !privateSession))
            .distinctBy { it.word.lowercase() }
            .take(3)
    }

    fun autocorrection(context: TypingContext, privateSession: Boolean = false): WordPrediction? =
        engine.autocorrection(context, includeAdaptive = !privateSession)

    /**
     * The user undid an autocorrection. The word is never corrected again this session, and when
     * learning is on (and the field is not private) it is learned so the choice persists.
     */
    fun rejectCorrection(contextBeforeWord: String, originalWord: String, privateSession: Boolean = false) {
        engine.rejectCorrection(originalWord)
        if (!privateSession) learn(contextBeforeWord, originalWord)
    }

    fun learn(contextBeforeWord: String, committedWord: String) {
        if (!ready || !preferences.enabled) return
        val previous = WORD.findAll(contextBeforeWord.lowercase()).map { it.value }.toList().takeLast(2)
        if (model.observe(previous, committedWord)) scheduleSave()
    }

    fun setLearningEnabled(enabled: Boolean) {
        preferences.enabled = enabled
        engine.setAdaptiveEnabled(enabled)
        onReady()
    }

    fun isLearningEnabled(): Boolean = preferences.enabled

    fun clearLearning() {
        preferences.markCleared()
        saveJob?.cancel()
        model = AdaptiveLanguageModel().also(engine::installAdaptiveModel)
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
