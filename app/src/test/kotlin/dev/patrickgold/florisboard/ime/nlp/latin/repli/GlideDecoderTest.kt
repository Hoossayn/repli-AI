/*
 * Copyright (C) 2026 The Repli Keyboard Contributors
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
import kotlin.test.assertTrue

class GlideDecoderTest {
    private val keys = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").flatMapIndexed { row, letters ->
        letters.mapIndexed { column, letter ->
            GlideKey(letter, 50f + column * 100f + row * 25f, 50f + row * 100f, 100f, 100f)
        }
    }
    private val vocabulary = listOf(
        "hello", "hallo", "help", "hells", "world", "word", "would", "wild", "letter", "later",
        "litter", "tomorrow", "today", "tower", "salt", "slat", "slant", "there", "their",
    ).map { LexiconWord(it, 100) }

    private fun path(word: String, layout: List<GlideKey> = keys, samplesPerSegment: Int = 1): List<GlidePoint> {
        val vertices = word.filterIndexed { index, char -> index == 0 || word[index - 1] != char }.map { letter ->
            layout.first { it.letter == letter }.let { GlidePoint(it.centerX, it.centerY) }
        }
        return vertices.zipWithNext().flatMap { (a, b) ->
            List(samplesPerSegment) { index ->
                val fraction = index.toFloat() / samplesPerSegment
                GlidePoint(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction)
            }
        } + vertices.last()
    }

    @Test fun `ideal hello and world paths rank their words first`() {
        val decoder = GlideDecoder(vocabulary)
        assertEquals("hello", decoder.decode(path("hello"), keys).first().word)
        assertEquals("world", decoder.decode(path("world"), keys).first().word)
    }

    @Test fun `doubled letters need no loop or dwell`() {
        val decoder = GlideDecoder(vocabulary)
        assertEquals("letter", decoder.decode(path("leter"), keys).firstOrNull()?.word)
        assertEquals("tomorrow", decoder.decode(path("tomorow"), keys).firstOrNull()?.word)
    }

    @Test fun `collinear out and back travel remains evidence at every recording density`() {
        val decoder = GlideDecoder(listOf(LexiconWord("tomorrow", 1), LexiconWord("tomow", Int.MAX_VALUE)))
        for (density in listOf(1, 8, 30)) {
            assertEquals("tomorrow", decoder.decode(path("tomorrow", samplesPerSegment = density), keys).firstOrNull()?.word)
            assertEquals("tomow", decoder.decode(path("tomow", samplesPerSegment = density), keys).firstOrNull()?.word)
        }
    }

    @Test fun `sparse dense and differently paced traces retain the same winner`() {
        val decoder = GlideDecoder(vocabulary)
        for (word in listOf("hello", "world", "letter", "tomorrow")) {
            val sparse = path(word)
            val dense = path(word, samplesPerSegment = 30)
            val paced = dense.flatMapIndexed { index, point -> List(if (index % 7 == 0) 5 else 1) { point } }
            assertEquals(word, decoder.decode(sparse, keys).firstOrNull()?.word, "sparse $word")
            assertEquals(word, decoder.decode(dense, keys).firstOrNull()?.word, "dense $word")
            assertEquals(word, decoder.decode(paced, keys).firstOrNull()?.word, "paced $word")
        }
    }

    @Test fun `moderate recording noise and imprecise endpoints remain recognizable`() {
        val decoder = GlideDecoder(vocabulary)
        for (word in listOf("hello", "world", "letter", "tomorrow")) {
            val noisy = path(word, samplesPerSegment = 12).mapIndexed { index, point ->
                GlidePoint(point.x + (index % 3 - 1) * 6f, point.y + (index % 5 - 2) * 4f)
            }.toMutableList()
            noisy[0] = noisy.first().let { GlidePoint(it.x + 22f, it.y - 19f) }
            noisy[noisy.lastIndex] = noisy.last().let { GlidePoint(it.x - 20f, it.y + 18f) }
            assertEquals(word, decoder.decode(noisy, keys).first().word)
        }
    }

    @Test fun `scale offset and different key aspect ratios preserve recognition`() {
        val decoder = GlideDecoder(vocabulary)
        val transformed = keys.map {
            it.copy(centerX = it.centerX * 1.7f + 300f, centerY = it.centerY * .65f + 900f,
                width = it.width * 1.7f, height = it.height * .65f)
        }
        assertEquals("world", decoder.decode(path("world"), keys).first().word)
        assertEquals("world", decoder.decode(path("world", transformed), transformed).first().word)
    }

    @Test fun `changed letter positions invalidate cached word templates`() {
        val decoder = GlideDecoder(vocabulary)
        assertEquals("hello", decoder.decode(path("hello"), keys).first().word)
        val moved = keys.map { key ->
            val replacement = when (key.letter) { 'h' -> 'b'; 'b' -> 'h'; 'o' -> 'q'; 'q' -> 'o'; else -> key.letter }
            key.copy(letter = replacement)
        }
        assertEquals("hello", decoder.decode(path("hello", moved), moved).first().word)
        assertEquals("hello", decoder.decode(path("hello"), keys).first().word)
    }

    @Test fun `path order outweighs frequency for words with identical endpoints`() {
        val decoder = GlideDecoder(listOf(LexiconWord("salt", 1), LexiconWord("slat", Int.MAX_VALUE)))
        assertEquals("salt", decoder.decode(path("salt"), keys).first().word)
        assertEquals("slat", decoder.decode(path("slat"), keys).first().word)
    }

    @Test fun `an incompatible internal path is rejected despite matching endpoints`() {
        val decoder = GlideDecoder(listOf(LexiconWord("salt", 100)))
        assertTrue(decoder.decode(path("sqzqpt"), keys).isEmpty())
    }

    @Test fun `empty stationary tiny and excessively large recordings are refused`() {
        val decoder = GlideDecoder(vocabulary)
        assertTrue(decoder.decode(emptyList(), keys).isEmpty())
        assertTrue(decoder.decode(listOf(GlidePoint(550f, 150f)), keys).isEmpty())
        assertTrue(decoder.decode(List(30) { GlidePoint(550f, 150f) }, keys).isEmpty())
        assertTrue(decoder.decode(listOf(GlidePoint(550f, 150f), GlidePoint(558f, 155f)), keys).isEmpty())
        assertTrue(decoder.decode(List(10_000) { GlidePoint(550f, 150f) }, keys).isEmpty())
    }

    @Test fun `nonfinite overflow and distant points are refused`() {
        val decoder = GlideDecoder(vocabulary)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, -5000f)) {
            assertTrue(decoder.decode(path("hello") + GlidePoint(bad, 150f), keys).isEmpty())
            assertTrue(decoder.decode(path("hello") + GlidePoint(550f, bad), keys).isEmpty())
        }
    }

    @Test fun `corrupt missing and ambiguous geometry is refused`() {
        val decoder = GlideDecoder(vocabulary)
        assertTrue(decoder.decode(path("hello"), emptyList()).isEmpty())
        for (badWidth in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(decoder.decode(path("hello"), keys.map { it.copy(width = badWidth) }).isEmpty())
        }
        assertTrue(decoder.decode(path("hello"), keys + keys.first()).isEmpty())
        assertTrue(decoder.decode(path("hello"), keys.filter { it.letter != 'h' }).none { it.word == "hello" })
    }

    @Test fun `vocabulary misses and unsupported word entries do not fabricate words`() {
        assertTrue(GlideDecoder(emptyList()).decode(path("hello"), keys).isEmpty())
        assertTrue(GlideDecoder(listOf(LexiconWord("world", 100))).decode(path("hello"), keys).isEmpty())
        val invalid = listOf("", "a", "a".repeat(100), "123", "hello!", "héllo").map { LexiconWord(it, 100) }
        assertTrue(GlideDecoder(invalid).decode(path("hello"), keys).isEmpty())
    }

    @Test fun `results are finite unique deterministic and honor bounded limits`() {
        val words = (1..20).map { LexiconWord("he" + "l".repeat(it) + "o", 100) }
        val decoder = GlideDecoder(words + LexiconWord("HELLO", 200))
        val first = decoder.decode(path("hello"), keys, Int.MAX_VALUE)
        assertTrue(first.isNotEmpty())
        assertTrue(first.size <= 10)
        assertEquals(first.size, first.map { it.word }.distinct().size)
        assertTrue(first.all { it.cost.isFinite() })
        assertEquals(first, decoder.decode(path("hello"), keys, Int.MAX_VALUE))
        assertTrue(first.zipWithNext().all { (a, b) -> a.cost <= b.cost })
        assertEquals(first.take(1), decoder.decode(path("hello"), keys, 1))
        assertTrue(decoder.decode(path("hello"), keys, 0).isEmpty())
        assertTrue(decoder.decode(path("hello"), keys, -10).isEmpty())
    }

    @Test fun `frequency and static context break geometric ties without stored user history`() {
        val decoder = GlideDecoder(listOf(LexiconWord("world", 1), LexiconWord("worrld", 100)))
        assertEquals("worrld", decoder.decode(path("world"), keys).first().word)
        assertEquals("world", decoder.decode(path("world"), keys, contextWords = listOf("hello")).first().word)
        assertEquals("worrld", decoder.decode(path("world"), keys).first().word)
    }

    @Test fun `large zigzags cannot force a plausible word from endpoints alone`() {
        val decoder = GlideDecoder(vocabulary)
        val zigzag = List(90) { index -> GlidePoint(if (index % 2 == 0) 50f else 950f, 50f) }
        assertTrue(decoder.decode(zigzag, keys).isEmpty())
    }
}
