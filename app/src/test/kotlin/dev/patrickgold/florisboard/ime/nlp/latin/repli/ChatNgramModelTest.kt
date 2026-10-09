package dev.patrickgold.florisboard.ime.nlp.latin.repli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatNgramModelTest {
    private val model = File("src/main/assets/ime/dict/repli-en_chat.ngrams").inputStream().use(ChatNgramModel::load)

    @Test
    fun `trigram context outranks bigram context`() {
        // "you" after "how are" (trigram) must beat whatever follows "are" alone.
        assertEquals("you", model.nextWords(listOf("how", "are"), 3).first().word)
        assertEquals("know", model.nextWords(listOf("let", "me"), 3).first().word)
        assertTrue(model.nextWords(listOf("zzzz"), 3).isEmpty())
        assertTrue(model.nextWords(emptyList(), 3).isEmpty())
    }

    @Test
    fun `parses the bundled file and normalises case and apostrophes`() {
        assertTrue(!model.isEmpty())
        assertEquals(model.nextWords(listOf("I\u2019m"), 2), model.nextWords(listOf("i'm"), 2))
        assertEquals("i", model.sentenceStarts(1).single().word)
        assertTrue(model.sentenceStarts(5).all { it.count > 0 })
    }

    @Test
    fun `reads plain text as well as gzip`() {
        val plain = "# c\nS\thi:5|yo:2\nB\thi\tthere:3\nT\thi there\tfriend:2\n".byteInputStream()
        val small = ChatNgramModel.load(plain)
        assertEquals(listOf("there"), small.nextWords(listOf("there", "hi"), 5).map { it.word })
        assertEquals(listOf("friend"), small.nextWords(listOf("hi", "there"), 5).map { it.word })
        assertEquals(listOf("hi", "yo"), small.sentenceStarts(5).map { it.word })
    }
}
