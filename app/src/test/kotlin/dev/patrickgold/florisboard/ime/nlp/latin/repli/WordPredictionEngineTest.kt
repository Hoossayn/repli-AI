package dev.patrickgold.florisboard.ime.nlp.latin.repli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordPredictionEngineTest {
    private val engine = WordPredictionEngine().apply {
        val dictionary = File("src/main/assets/ime/dict/repli-en_us.dict")
        val supplement = File("src/main/assets/ime/dict/repli-en_ng.supplement")
        dictionary.inputStream().use { main ->
            supplement.inputStream().use { extra -> installLexicon(BundledKeyboardLexicon.load(main, listOf(extra))) }
        }
        File("src/main/assets/ime/dict/repli-en_chat.ngrams").inputStream().use { installChatModel(ChatNgramModel.load(it)) }
    }

    @Test
    fun `predicts the next word from the chat prior and lets learned phrases win`() {
        assertEquals("you", engine.suggest(TypingContext("how are ", "", 8, 8)).first().word)
        assertEquals("to", engine.suggest(TypingContext("Looking forward ", "", 16, 16)).first().word)
        assertEquals("for", engine.suggest(TypingContext("thanks ", "", 7, 7)).first().word)
        assertTrue(engine.suggest(TypingContext("see you ", "", 8, 8)).map { it.word }.containsAll(listOf("then", "there")))
        // Dictionary rows that only carried the placeholder "the/to/of" column no longer drive predictions.
        assertTrue(engine.suggest(TypingContext("Aachen ", "", 7, 7)).map { it.word.lowercase() } != listOf("the", "to", "of"))

        val learned = AdaptiveLanguageModel().apply { repeat(2) { observe(listOf("how", "are"), "una") } }
        engine.installAdaptiveModel(learned)
        assertEquals("una", engine.suggest(TypingContext("how are ", "", 8, 8)).first().word)
        assertEquals("you", engine.suggest(TypingContext("how are ", "", 8, 8), includeAdaptive = false).first().word)
        engine.installAdaptiveModel(AdaptiveLanguageModel())
    }

    @Test
    fun `opens a message with corpus starters, one per word family`() {
        val starters = engine.suggest(TypingContext("", "", 0, 0)).map { it.word }
        assertEquals(3, starters.size)
        assertEquals("I", starters.first())
        assertTrue(starters.none { it.equals("i'm", ignoreCase = true) })
        assertTrue(engine.suggest(TypingContext("Ok. ", "", 4, 4)).first().word.first().isUpperCase())
    }

    @Test
    fun `never autocorrects Nigerian names, places or Pidgin from the supplement`() {
        assertNull(engine.autocorrection(TypingContext("Tunde", "", 5, 5)))
        assertNull(engine.autocorrection(TypingContext("Ola", "", 3, 3)))
        assertNull(engine.autocorrection(TypingContext("ola", "", 3, 3)))
        assertNull(engine.autocorrection(TypingContext("Lagos", "", 5, 5)))
        assertNull(engine.autocorrection(TypingContext("wahala", "", 6, 6)))
        assertNull(engine.autocorrection(TypingContext("jollof", "", 6, 6)))
        assertTrue(engine.suggest(TypingContext("Tund", "", 4, 4)).any { it.word == "Tunde" })
    }

    @Test
    fun `treats a capitalised word after a sentence start as a name`() {
        assertNull(engine.autocorrection(TypingContext("Hi Zoya", "", 7, 7)))
        assertNull(engine.autocorrection(TypingContext("Hi Teh", "", 6, 6)))
        assertEquals("The", engine.autocorrection(TypingContext("Teh", "", 3, 3))?.word)
        assertEquals("The", engine.autocorrection(TypingContext("Ok. Teh", "", 7, 7))?.word)
        assertEquals("the", engine.autocorrection(TypingContext("Hi teh", "", 6, 6))?.word)
        assertEquals("THE", engine.autocorrection(TypingContext("Hi TEH", "", 6, 6))?.word)
    }

    @Test
    fun `stops correcting a word once the user has undone that correction`() {
        assertEquals("the", engine.autocorrection(TypingContext("teh", "", 3, 3))?.word)
        engine.rejectCorrection("teh")
        assertNull(engine.autocorrection(TypingContext("teh", "", 3, 3)))
        assertNull(engine.autocorrection(TypingContext("Teh", "", 3, 3)))
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

class RichAutocorrectTest {
    private val engine = WordPredictionEngine().apply {
        val dictionary = File("src/main/assets/ime/dict/repli-en_us.dict")
        val supplement = File("src/main/assets/ime/dict/repli-en_ng.supplement")
        val languages = File("src/main/assets/ime/dict/repli-ng_languages.supplement")
        dictionary.inputStream().use { main ->
            supplement.inputStream().use { extra ->
                languages.inputStream().use { more -> installLexicon(BundledKeyboardLexicon.load(main, listOf(extra, more))) }
            }
        }
        File("src/main/assets/ime/dict/repli-en_chat.ngrams").inputStream().use { installChatModel(ChatNgramModel.load(it)) }
    }

    @Test
    fun `prefers chat-common words, leaves shorthand and stretched words alone`() {
        assertEquals("thanks", correct("thnks"))
        assertNull(correct("yeahh"))
        assertNull(correct("helloo"))
        assertNull(correct("pleaseee"))
        assertNull(correct("okk"))
        assertNull(correct("gud"))
        assertNull(correct("yup"))
        assertNull(correct("omw"))
        assertNull(correct("lool"))
    }

    @Test
    fun `local-language words complete but never shield an English typo`() {
        assertTrue(engine.suggest(TypingContext("kaab", "", 4, 4)).any { it.word == "kaabo" })
        assertTrue(engine.suggest(TypingContext("nago", "", 4, 4)).any { it.word == "nagode" })
        assertTrue(engine.suggest(TypingContext("daal", "", 4, 4)).any { it.word == "daalu" })
        // "ise" is Yoruba for work but, before the user has ever kept it, it reads as an English typo.
        assertTrue(correct("ise") != null)
        val learned = AdaptiveLanguageModel().apply { observe(listOf("mo"), "ise") }
        engine.installAdaptiveModel(learned)
        assertNull(correct("ise"))
        engine.installAdaptiveModel(AdaptiveLanguageModel())
    }

    private fun correct(text: String) = engine.autocorrection(TypingContext(text, "", text.length, text.length))?.word

    @Test
    fun `restores missing apostrophes for any common contraction`() {
        assertEquals("why's", correct("whys"))
        assertEquals("where's", correct("wheres"))
        assertEquals("I'm", correct("im"))
        assertEquals("I've", correct("ive"))
        assertEquals("you're", correct("youre"))
        assertEquals("wouldn't", correct("wouldnt"))
        assertEquals("let's", correct("lets"))
        assertEquals("What's", correct("Whats"))
        assertEquals("I", correct("i"))
        // Real words stay: "well", "were", "ill" and "id" are too often meant literally.
        assertNull(correct("well"))
        assertNull(correct("were"))
        assertNull(correct("ill"))
        assertNull(correct("id"))
        // ...but the contraction is still offered as a suggestion for the chat-likely ones.
        assertTrue(engine.suggest(TypingContext("ill", "", 3, 3)).any { it.word == "I'll" })
        assertTrue(engine.suggest(TypingContext("well", "", 4, 4)).none { it.word == "we'll" })
    }

    @Test
    fun `fixes two-edit typos on neighbouring keys when there is a clear winner`() {
        assertEquals("because", correct("becsuse"))
        assertEquals("tomorrow", correct("tomorrpw"))
        assertEquals("morning", correct("mornjng"))
        assertEquals("message", correct("mesaage"))
        assertNull(correct("xqzvbn"))
        assertNull(correct("Tolani"))
    }

    @Test
    fun `offers the favourite emoji after a finished sentence once it is a habit`() {
        val model = AdaptiveLanguageModel().apply { listOf("🙏", "😂", "🙏", "😊").forEach { recordEmoji(it) } }
        engine.installAdaptiveModel(model)
        val afterSentence = engine.suggest(TypingContext("See you tomorrow. ", "", 18, 18)).map { it.word }
        assertEquals("😊", afterSentence.last())
        assertTrue(engine.suggest(TypingContext("", "", 0, 0)).none { it.word == "😊" })
        engine.installAdaptiveModel(AdaptiveLanguageModel().apply { recordEmoji("🙏") })
        assertTrue(engine.suggest(TypingContext("Ok. ", "", 4, 4)).none { it.word == "🙏" })
    }
}

class KeyboardProximityTest {
    @Test
    fun `neighbouring keys cost less than distant ones`() {
        assertTrue(KeyboardProximity.adjacent('a', 's'))
        assertTrue(KeyboardProximity.adjacent('t', 'g'))
        assertTrue(KeyboardProximity.adjacent('q', 'a'))
        assertTrue(!KeyboardProximity.adjacent('q', 'p'))
        assertEquals(0.5, KeyboardProximity.distance("tje", "the"))
        assertEquals(1.0, KeyboardProximity.distance("tze", "the"))
        assertEquals(0.7, KeyboardProximity.distance("teh", "the"))
        assertEquals(1.0, KeyboardProximity.distance("the", "them"))
        assertTrue(KeyboardProximity.distance("abcdef", "zzzzzz", 1.6) > 1.6)
    }
}
