/*
 * Copyright (C) 2025-2026 The Repli Keyboard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.latin.repli

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** A repeatable JVM processing workload, not an Android touch-to-screen benchmark. */
class TypingWorkloadTest {
    @Test fun `cached display validation and commit preserve results with fewer calculations`() {
        val engine = WordPredictionEngine().apply {
            File("src/main/assets/ime/dict/repli-en_us.dict").inputStream().use {
                installLexicon(BundledKeyboardLexicon.load(it))
            }
            File("src/main/assets/ime/dict/repli-en_chat.ngrams").inputStream().use {
                installChatModel(ChatNgramModel.load(it))
            }
        }
        val cache = PredictionCache(
            predict = { context, private -> engine.suggest(context, includeAdaptive = !private) },
            correct = { context, private -> engine.autocorrection(context, includeAdaptive = !private) },
        )
        val inputs = listOf("teh", "hello", "looking forward ", "thnks", "tomorrpw", "becsuse", "see you ", "qzxvbn")
        fun originalSuggestions(context: TypingContext): List<WordPrediction> =
            (listOfNotNull(engine.autocorrection(context)?.copy(space = true)) + engine.suggest(context))
                .distinctBy { it.word.lowercase(Locale.ROOT) }.take(3)
        val oldTimes = mutableListOf<Long>()
        val newTimes = mutableListOf<Long>()
        repeat(24) { round ->
            for (input in inputs) {
                val context = TypingContext(input, "", input.length, input.length)
                var start = System.nanoTime()
                val expected = originalSuggestions(context)
                assertEquals(expected, originalSuggestions(context))
                val correction = engine.autocorrection(context)
                val oldTime = System.nanoTime() - start
                cache.invalidate()
                start = System.nanoTime()
                assertEquals(expected, cache.suggestions(context, false))
                assertEquals(expected, cache.suggestions(context, false))
                assertEquals(correction, cache.autocorrection(context, false))
                val newTime = System.nanoTime() - start
                if (round >= 4) { oldTimes += oldTime; newTimes += newTime }
            }
        }
        val queries = 24L * inputs.size
        val metrics = cache.metrics.snapshot()
        assertEquals(queries, metrics.correctionComputations)
        assertEquals(queries, metrics.suggestionComputations)
        assertEquals(queries * 2, metrics.cacheHits)
        fun p95(samples: List<Long>) = samples.sorted()[(samples.size * 95 + 99) / 100 - 1] / 1_000
        println("TYPING_WORKLOAD: samples=${oldTimes.size}; original_p95_us=${p95(oldTimes)}; cached_p95_us=${p95(newTimes)}; original_engine_calls=${queries * 5}; cached_engine_calls=${queries * 2}")
    }
}
