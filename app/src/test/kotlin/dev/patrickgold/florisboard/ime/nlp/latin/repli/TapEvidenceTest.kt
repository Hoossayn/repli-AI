package dev.patrickgold.florisboard.ime.nlp.latin.repli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapEvidenceTest {
    private val keys = listOf(TapKey('f', .2f, .5f, .1f, .2f), TapKey('g', .3f, .5f, .1f, .2f))
    private fun tap(typed: Char = 'f', x: Float = .248f, layout: String = "qwerty") =
        TapEvidence.from(typed, x, .5f, 10, layout, keys)!!

    @Test fun `the same selected letter has different costs for clean and near boundary taps`() {
        val center = tap(x = .2f)
        val boundary = tap()
        assertTrue(KeyboardProximity.distance("foo", "goo", taps = listOf(center)) >
            KeyboardProximity.distance("foo", "goo", taps = listOf(boundary)))
        assertNull(TapEvidence.from('f', Float.NaN, .5f, 10, "qwerty", keys))
    }

    @Test fun `touch evidence permits a first letter alternative in multi edit correction`() {
        val lexicon = BundledKeyboardLexicon.load("found\t180\n".byteInputStream())
        val touch = tap(typed = 'g', x = .252f)
        assertTrue(lexicon.fuzzyCorrections("gopnd", 3).isEmpty())
        assertEquals("found", lexicon.fuzzyCorrections("gopnd", 3, listOf(touch)).first().word)
    }

    @Test fun `word touches follow backspace and reset after cursor moves or layout changes`() {
        val history = WordTouchHistory()
        history.record("", "f", tap())
        history.record("f", "g", tap('g', .3f))
        history.backspace("fg", "f")
        assertEquals(listOf(tap()), history.samples("f"))
        assertTrue(history.samples("other").isEmpty())
        history.record("f", "g", tap('g', .3f, "other-layout"))
        assertEquals(null, history.samples("fg").first())
        history.record("fg", " ", null)
        assertTrue(history.samples("fg ").isEmpty())
    }

    @Test fun `pasted and overlong words cannot reuse geometry from an earlier word`() {
        val history = WordTouchHistory()
        history.record("", "f", tap())
        history.record("f", "pasted", null)
        assertTrue(history.samples("fpasted").isEmpty())
        history.record("a".repeat(20), "f", tap())
        assertTrue(history.samples("a".repeat(20) + "f").isEmpty())
    }
}
