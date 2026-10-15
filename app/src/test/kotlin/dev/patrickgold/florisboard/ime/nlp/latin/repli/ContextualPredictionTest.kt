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
