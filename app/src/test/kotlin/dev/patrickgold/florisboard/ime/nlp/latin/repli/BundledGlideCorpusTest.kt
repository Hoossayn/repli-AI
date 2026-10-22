/*
 * Copyright (C) 2026 The Repli Contributors
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

package dev.patrickgold.florisboard.ime.nlp.latin.repli

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real licensed vocabulary, not just a tiny dictionary with one possible answer. */
class BundledGlideCorpusTest {
    private val lexicon = File("src/main/assets/ime/dict/repli-en_us.dict").inputStream().use { BundledKeyboardLexicon.load(it) }
    private val decoder = GlideDecoder(lexicon.glideWords())
    private val keys = buildList {
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { row, letters ->
            val offset = listOf(0f, .5f, 1.5f)[row]
            letters.forEachIndexed { column, char -> add(GlideKey(char, (column + offset) * 100 + 50, row * 160f + 80, 100f, 160f)) }
        }
    }
    private fun trace(word: String, dense: Boolean = false): List<GlidePoint> {
        val centers = word.map { letter -> keys.first { it.letter == letter }.let { GlidePoint(it.centerX, it.centerY) } }
        if (!dense) return centers
        return centers.zipWithNext().flatMap { (a, b) -> (0..8).map { step ->
            val fraction = step / 8f
            GlidePoint(a.x + (b.x-a.x)*fraction, a.y+(b.y-a.y)*fraction)
        } } + centers.last()
    }
    @Test fun `common words rank first against the complete bundled vocabulary`() {
        assertTrue(lexicon.glideWords().size > 10000)
        for (word in listOf("hello", "world", "letter", "tomorrow", "there", "thanks", "keyboard", "swipe")) {
            assertEquals(word, decoder.decode(trace(word), keys).firstOrNull()?.word, word)
        }
    }
    @Test fun `moderate real layout noise and dense sampling keep the intended word`() {
        val random = Random(17)
        val path = trace("hello", dense=true).map { GlidePoint(it.x + random.nextFloat()*12-6, it.y + random.nextFloat()*12-6) }
        assertEquals("hello", decoder.decode(path, keys).firstOrNull()?.word)
        assertEquals("world", decoder.decode(trace("world", dense=true), keys).firstOrNull()?.word)
    }
}
