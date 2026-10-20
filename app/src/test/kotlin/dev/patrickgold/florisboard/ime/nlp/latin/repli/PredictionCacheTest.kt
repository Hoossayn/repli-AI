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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PredictionCacheTest {
    private val context = TypingContext("teh", "", 3, 3)
    private val correction = WordPrediction("the", 3, 0, false, true)

    @Test fun `display validation and space share the same completed calculation`() {
        var predicts = 0
        var corrects = 0
        val cache = PredictionCache(
            predict = { _, _ -> predicts++; listOf(correction.copy(space = true)) },
            correct = { _, _ -> corrects++; correction },
        )
        val displayed = cache.suggestions(context, false)
        assertEquals(displayed, cache.suggestions(context, false))
        assertEquals(correction, cache.autocorrection(context, false))
        assertEquals(1, predicts)
        assertEquals(1, corrects)
        assertEquals(2L, cache.metrics.snapshot().cacheHits)
    }

    @Test fun `a cached no correction result is reusable and private input has a separate key`() {
        var corrects = 0
        val cache = PredictionCache(predict = { _, _ -> emptyList() }, correct = { _, _ -> corrects++; null })
        assertNull(cache.autocorrection(context, false))
        cache.suggestions(context, false)
        assertNull(cache.autocorrection(context, true))
        assertEquals(2, corrects)
    }

    @Test fun `model changes and cursor changes invalidate results`() {
        var corrects = 0
        val cache = PredictionCache(predict = { _, _ -> emptyList() }, correct = { _, _ -> corrects++; correction })
        cache.suggestions(context, false)
        cache.invalidate()
        cache.suggestions(context, false)
        cache.suggestions(context.copy(selectionStart = 2, selectionEnd = 2), false)
        assertEquals(3, corrects)
    }

    @Test fun `invalidation during a query prevents stale data from entering the cache`() {
        var predicts = 0
        lateinit var cache: PredictionCache
        cache = PredictionCache(
            predict = { _, _ -> predicts++; if (predicts == 1) cache.invalidate(); emptyList() },
            correct = { _, _ -> null },
        )
        cache.suggestions(context, false)
        cache.suggestions(context, false)
        assertEquals(2, predicts)
    }

    @Test fun `metrics retain a bounded timing window without input data`() {
        var clock = 0L
        val cache = PredictionCache(
            predict = { _, _ -> emptyList() }, correct = { _, _ -> null },
            nanoTime = { clock.also { clock += 2_000 } },
        )
        repeat(200) { cache.invalidate(); cache.suggestions(context, false) }
        val metrics = cache.metrics.snapshot()
        assertEquals(200L, metrics.suggestionComputations)
        assertEquals(200L, metrics.correctionComputations)
        assertEquals(2L, metrics.recentP95Micros)
        assertEquals(2L, metrics.worstMicros)
    }

    @Test fun `fast separator fallback does not poison a later full prediction result`() {
        var fullQueries = 0
        var fastQueries = 0
        val cache = PredictionCache(
            predict = { _, _ -> emptyList() },
            correct = { _, _ -> fullQueries++; correction },
            fastCorrect = { _, _ -> fastQueries++; null },
        )
        assertNull(cache.autocorrection(context, false))
        assertEquals(correction.copy(space = true), cache.suggestions(context, false).first())
        assertEquals(correction, cache.autocorrection(context, false))
        assertEquals(1, fastQueries)
        assertEquals(1, fullQueries)
    }

    @Test fun `an older query cannot evict a more recent input snapshot`() {
        val other = context.copy(before = "hello", selectionStart = 5, selectionEnd = 5)
        var predicts = 0
        lateinit var cache: PredictionCache
        cache = PredictionCache(
            predict = { input, _ -> predicts++; if (input == context) cache.suggestions(other, false); emptyList() },
            correct = { _, _ -> null },
        )
        cache.suggestions(context, false)
        cache.suggestions(other, false)
        assertEquals(2, predicts)
    }
}
