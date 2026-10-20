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
import kotlin.test.assertTrue

class ContextualPredictionTest {
    private fun engine() = WordPredictionEngine().apply {
        installLexicon(object : KeyboardLexicon {
            override fun completions(prefix: String, limit: Int) = listOf(LexiconWord("foley", 120))
            override fun corrections(word: String, limit: Int) = listOf(LexiconWord("role", 150), LexiconWord("file", 145))
            override fun nextWords(previousWord: String, limit: Int) = emptyList<LexiconWord>()
            override fun contains(word: String) = word in setOf("role", "file", "foley")
        })
        installChatModel(ChatNgramModel.load("""
            U\trole:10|file:10|foley:10
            T\topen the\tfile:100
            T\tplay the\trole:100
        """.trimIndent().replace("\\t", "\t").byteInputStream()))
    }

    @Test fun `the same typo is corrected differently when preceding words change`() {
        val engine = engine()
        fun correct(before: String) = engine.autocorrection(TypingContext(before, "", before.length, before.length))?.word
        assertEquals("file", correct("open the fole"))
        assertEquals("role", correct("play the fole"))
    }

    @Test fun `a completion no longer hides a contextually useful correction`() {
        val input = "open the fole"
        val suggestions = engine().suggest(TypingContext(input, "", input.length, input.length))
        assertEquals("file", suggestions.first().word)
        assertTrue(suggestions.any { it.word == "foley" })
    }

    @Test fun `known intentional words remain protected from automatic replacement`() {
        val input = "open the role"
        assertEquals(null, engine().autocorrection(TypingContext(input, "", input.length, input.length)))
    }
}
