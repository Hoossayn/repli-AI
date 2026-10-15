package dev.patrickgold.florisboard.ime.smartbar

import dev.patrickgold.florisboard.ime.nlp.latin.repli.RepliSuggestionState
import dev.patrickgold.florisboard.ime.nlp.latin.repli.WordPrediction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmartbarExpansionPolicyTest {
    private val prediction = WordPrediction("hello", 1, 0, true)
    private fun target(repli: RepliSuggestionState, legacy: Boolean = false, inline: Boolean = false, selection: Boolean = false) =
        SmartbarExpansionPolicy.expanded(repli, legacy, inline, selection)

    @Test fun `pending work does not expand a collapsed toolbar`() {
        assertNull(target(RepliSuggestionState(active = true, pending = true)))
    }
    @Test fun `an empty legacy result cannot expand the active Repli toolbar`() {
        assertEquals(false, target(RepliSuggestionState(active = true, candidates = listOf(prediction))))
    }
    @Test fun `completed empty Repli result can show actions despite legacy candidates`() {
        assertEquals(true, target(RepliSuggestionState(active = true), legacy = true))
    }
    @Test fun `selection exposes editing actions even while prediction is pending`() {
        assertEquals(true, target(RepliSuggestionState(active = true, pending = true), selection = true))
    }
    @Test fun `inline suggestions retain their space while Repli is pending`() {
        assertEquals(false, target(RepliSuggestionState(active = true, pending = true), inline = true))
    }
    @Test fun `inactive Repli uses the legacy candidate state`() {
        assertEquals(false, target(RepliSuggestionState(), legacy = true))
        assertEquals(true, target(RepliSuggestionState()))
    }
    @Test fun `typing cycle collapses once instead of expanding for every pending result`() {
        val ready = RepliSuggestionState(active = true, candidates = listOf(prediction))
        val cycle = listOf(ready, RepliSuggestionState(active = true, pending = true), ready)
        assertEquals(listOf(false, false), cycle.mapNotNull { target(it) })
    }
    @Test fun `retained candidates remain pending rather than triggering a toolbar transition`() {
        assertNull(target(RepliSuggestionState(active = true, pending = true, candidates = listOf(prediction))))
    }

}
