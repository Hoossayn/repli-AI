package dev.patrickgold.florisboard.ime.keyboard3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExpectedContentQueueTest {
    @Test fun `duplicate intermediate cursor acknowledgement preserves pending separator`() {
        val queue = ExpectedContentQueue<Int>()
        queue.push(6) // corrected word
        queue.push(7) // separator
        assertEquals(6, queue.popUntilOrNull { it == 6 })
        assertNull(queue.popUntilOrNull { it == 6 }) // duplicate seen on Pixel
        assertEquals(7, queue.popUntilOrNull { it == 7 })
        assertNull(queue.popUntilOrNull { it == 7 })
    }

    @Test fun `coalesced final acknowledgement consumes earlier pending edits`() {
        val queue = ExpectedContentQueue<Int>()
        queue.push(3)
        queue.push(4)
        assertEquals(4, queue.popUntilOrNull { it == 4 })
        assertNull(queue.popUntilOrNull { it == 3 })
    }

    @Test fun `external selection can clear old expectations`() {
        val queue = ExpectedContentQueue<Int>()
        queue.push(4)
        assertNull(queue.popUntilOrNull { it == 1 })
        queue.clear()
        assertNull(queue.popUntilOrNull { it == 4 })
        queue.push(1)
        assertEquals(1, queue.popUntilOrNull { it == 1 })
    }
}
