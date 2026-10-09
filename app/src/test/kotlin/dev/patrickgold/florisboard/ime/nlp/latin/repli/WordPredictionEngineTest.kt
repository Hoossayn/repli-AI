package dev.patrickgold.florisboard.ime.nlp.latin.repli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordPredictionEngineTest {
    private val engine = WordPredictionEngine().apply {
        val dictionary = File("src/main/assets/ime/dict/repli-en_us.dict")
        dictionary.inputStream().use { installLexicon(BundledKeyboardLexicon.load(it)) }
    }

    @Test
    fun `commits a common typo but leaves dialect and mid-word text alone`() {
        assertEquals("the", engine.autocorrection(TypingContext("teh", "", 3, 3))?.word)
        assertEquals("don't", engine.autocorrection(TypingContext("dont", "", 4, 4))?.word)
        assertNull(engine.autocorrection(TypingContext("abeg", "", 4, 4)))
        assertNull(engine.autocorrection(TypingContext("teh", "re", 3, 3)))
        assertNull(engine.autocorrection(TypingContext("name@dont", "", 9, 9)))
    }

    @Test
    fun `offers and autocorrects the chat contraction`() {
        val context = TypingContext("its", "", 3, 3)
        assertEquals("it's", engine.suggest(context).first().word)
        assertEquals("it's", engine.autocorrection(context)?.word)
    }

    @Test
    fun `suggests a completion without exposing email text`() {
        assertTrue(engine.suggest(TypingContext("hell", "", 4, 4)).any { it.word == "hello" })
        assertTrue(engine.suggest(TypingContext("name@example", "", 12, 12)).isEmpty())
    }

    @Test
    fun `learned completions survive a snapshot and obey the learning switch`() {
        val model = AdaptiveLanguageModel().apply {
            observe(listOf("good"), "replify")
            observe(listOf("good"), "replify")
        }
        engine.installAdaptiveModel(AdaptiveLanguageModel.from(model.snapshot()))
        assertEquals("replify", engine.suggest(TypingContext("repl", "", 4, 4)).first().word)
        engine.setAdaptiveEnabled(false)
        assertTrue(engine.suggest(TypingContext("repl", "", 4, 4)).none { it.word == "replify" })
    }

    @Test
    fun `private predictions use the bundled lexicon without learned words`() {
        val model = AdaptiveLanguageModel().apply { repeat(3) { observe(listOf("good"), "replify") } }
        engine.installAdaptiveModel(model)
        assertTrue(engine.suggest(TypingContext("repl", "", 4, 4)).any { it.word == "replify" })
        assertTrue(engine.suggest(TypingContext("repl", "", 4, 4), includeAdaptive = false).none { it.word == "replify" })
        assertTrue(engine.suggest(TypingContext("hell", "", 4, 4), includeAdaptive = false).any { it.word == "hello" })
    }
}
