package dev.patrickgold.florisboard.ime.nlp.latin.repli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalizationConfidenceTest {
    @Test fun `a single accidental phrase does not override corpus evidence but repeated usage can`() {
        val adaptive = AdaptiveLanguageModel()
        val engine = WordPredictionEngine().apply {
            installAdaptiveModel(adaptive)
            installChatModel(ChatNgramModel.load("T\thow are\tyou:200|things:50\n".byteInputStream()))
        }
        val context = TypingContext("how are ", "", 8, 8)
        adaptive.observe(listOf("how", "are"), "una")
        assertEquals("you", engine.suggest(context).first().word)
        adaptive.observe(listOf("how", "are"), "una")
        assertEquals("una", engine.suggest(context).first().word)
        assertEquals("you", engine.suggest(context, includeAdaptive = false).first().word)
        engine.setAdaptiveEnabled(false)
        assertTrue(engine.suggest(context).none { it.word == "una" })
    }

    @Test fun `equally plausible correction alternatives are suggestions rather than forced edits`() {
        val engine = WordPredictionEngine().apply {
            installLexicon(object : KeyboardLexicon {
                override fun completions(prefix: String, limit: Int) = emptyList<LexiconWord>()
                override fun corrections(word: String, limit: Int) = listOf(LexiconWord("file", 150), LexiconWord("role", 150))
                override fun nextWords(previousWord: String, limit: Int) = emptyList<LexiconWord>()
                override fun contains(word: String) = false
            })
        }
        val context = TypingContext("fole", "", 4, 4)
        assertNull(engine.autocorrection(context))
        assertTrue(engine.suggest(context).isNotEmpty())
    }
}
