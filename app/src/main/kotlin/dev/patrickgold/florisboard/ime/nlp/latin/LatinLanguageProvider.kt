/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.latin.repli.BundledKeyboardLexicon
import dev.patrickgold.florisboard.ime.nlp.latin.repli.TypingContext
import dev.patrickgold.florisboard.ime.nlp.latin.repli.WordPredictionEngine
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock
import org.k3lp.runtime.K3Content

class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"
    }

    private val appContext by context.appContext()

    private val wordData = guardedByLock { mutableMapOf<String, Int>() }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())
    private val predictionEngine = WordPredictionEngine()
    @Volatile private var predictionLexicon: BundledKeyboardLexicon? = null

    override val providerId = ProviderId

    override suspend fun create() {
        // Here we initialize our provider, set up all things which are not language dependent.
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        // Here we have the chance to preload dictionaries and prepare a neural network for a specific language.
        // Is kept in sync with the active keyboard subtype of the user, however a new preload does not necessary mean
        // the previous language is not needed anymore (e.g. if the user constantly switches between two subtypes)

        // To read a file from the APK assets the following methods can be used:
        // appContext.assets.open()
        // appContext.assets.reader()
        // appContext.assets.bufferedReader()
        // appContext.assets.readText()
        // To copy an APK file/dir to the file system cache (appContext.cacheDir), the following methods are available:
        // appContext.assets.copy()
        // appContext.assets.copyRecursively()

        // The subtype we get here contains a lot of data, however we are only interested in subtype.primaryLocale and
        // subtype.secondaryLocales.

        if (subtype.primaryLocale.language == "en" && predictionLexicon == null) {
            synchronized(predictionEngine) {
                if (predictionLexicon == null) {
                    val loaded = appContext.assets.open("ime/dict/repli-en_us.dict").use(BundledKeyboardLexicon::load)
                    predictionEngine.installLexicon(loaded)
                    predictionLexicon = loaded
                }
            }
        }
        wordData.withLock { wordData ->
            if (wordData.isEmpty()) {
                // Here we use readText() because the test dictionary is a json dictionary
                val rawData = appContext.assets.readText("ime/dict/data.json")
                val jsonData = Json.decodeFromString(wordDataSerializer, rawData)
                wordData.putAll(jsonData)
            }
        }
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        val lexicon = predictionLexicon
        if (subtype.primaryLocale.language != "en" || lexicon == null) return SpellingResult.unspecified()
        if (lexicon.contains(word) || WordPredictionEngine.isProtectedDialectWord(word)) {
            return SpellingResult.validWord()
        }
        val corrections = lexicon.corrections(word, maxSuggestionCount.coerceAtLeast(0))
        return if (corrections.isEmpty()) SpellingResult.unspecified()
        else SpellingResult.typo(corrections.map { it.word }.toTypedArray())
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: K3Content,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        if (subtype.primaryLocale.language != "en" || maxCandidateCount <= 0 ||
            !content.selection.isCollapsed()) return emptyList()
        val before = content.surroundingText.textBefore.takeLast(WordPredictionEngine.BEFORE_LIMIT)
        val after = content.surroundingText.textAfter.take(WordPredictionEngine.AFTER_LIMIT)
        // FlorisBoard's completion API replaces only the composing word before the cursor.
        if (after.firstOrNull()?.let { it.isLetter() || it == '\'' || it == '’' } == true) return emptyList()
        val cursor = content.selection.start
        val context = TypingContext(before, after, cursor, cursor)
        val typedWord = before.takeLastWhile { it.isLetter() || it == '\'' || it == '’' }
        val correction = predictionEngine.autocorrection(context)
            ?.takeIf { it.removeBefore == typedWord.length && content.compositionText == typedWord }
        val predictions = predictionEngine.suggest(context)
        return buildList {
            if (correction != null) {
                add(WordSuggestionCandidate(
                    text = correction.word,
                    confidence = 1.0,
                    isEligibleForAutoCommit = true,
                    isEligibleForUserRemoval = false,
                    sourceProvider = this@LatinLanguageProvider,
                    sourceText = typedWord,
                ))
            }
            for (prediction in predictions) {
                if (size >= maxCandidateCount) break
                if (any { it.text.toString().equals(prediction.word, ignoreCase = true) }) continue
                add(WordSuggestionCandidate(
                    text = prediction.word,
                    confidence = if (prediction.correction) 0.75 else 0.5,
                    isEligibleForUserRemoval = false,
                    sourceProvider = this@LatinLanguageProvider,
                    sourceText = typedWord,
                ))
            }
        }.take(maxCandidateCount)
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // We can use flogDebug, flogInfo, flogWarning and flogError for debug logging, which is a wrapper for Logcat
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        flogDebug { candidate.toString() }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return wordData.withLock { it.keys.toList() }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return wordData.withLock { it.getOrDefault(word, 0) / 255.0 }
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }
}
