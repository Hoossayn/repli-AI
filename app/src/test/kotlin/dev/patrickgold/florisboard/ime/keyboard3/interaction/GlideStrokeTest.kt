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

package dev.patrickgold.florisboard.ime.keyboard3.interaction

import dev.patrickgold.florisboard.ime.nlp.latin.repli.GlidePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlideStrokeTest {
    private fun stroke() = GlideStroke(GlidePoint(50f, 50f), 100, 'a', 100f, 1000, 500)

    @Test fun `jitter and same letter motion remain ordinary taps`() {
        val stroke = stroke()
        stroke.add(GlidePoint(65f, 54f), 150, 'b')
        stroke.add(GlidePoint(135f, 50f), 180, 'a')
        assertFalse(stroke.active)
        assertFalse(stroke.rejected)
    }

    @Test fun `deliberate movement crosses a different letter before activation`() {
        val stroke = stroke()
        stroke.add(GlidePoint(130f, 50f), 115, 'b')
        assertFalse(stroke.active)
        stroke.add(GlidePoint(130f, 50f), 150, 'b')
        assertTrue(stroke.active)
    }

    @Test fun `physical width scales admission instead of display density or normalized axes`() {
        val small = GlideStroke(GlidePoint(50f, 50f), 100, 'a', 50f, 1000, 500)
        val large = GlideStroke(GlidePoint(50f, 50f), 100, 'a', 200f, 1000, 500)
        small.add(GlidePoint(100f, 50f), 150, 'b')
        large.add(GlidePoint(100f, 50f), 150, 'b')
        assertTrue(small.active)
        assertFalse(large.active)
    }

    @Test fun `long strokes retain their beginning and latest endpoint within fixed memory`() {
        val stroke = stroke()
        repeat(2000) { index ->
            stroke.add(GlidePoint(150f + index % 600, 60f + index % 200), 150L + index, 'b')
            assertTrue(stroke.points.size <= GlideStroke.MAX_POINTS)
        }
        assertTrue(stroke.active)
        assertEquals(GlidePoint(50f, 50f), stroke.points.first())
        assertEquals(GlidePoint(349f, 259f), stroke.points.last())
    }

    @Test fun `leaving keyboard or nonfinite motion permanently invalidates active stroke`() {
        listOf(GlidePoint(-1f, 50f), GlidePoint(1000f, 50f), GlidePoint(150f, Float.NaN)).forEach { outside ->
            val stroke = stroke()
            stroke.add(GlidePoint(150f, 50f), 150, 'b')
            stroke.add(outside, 170, 'c')
            stroke.add(GlidePoint(250f, 50f), 200, 'c')
            assertTrue(stroke.rejected)
            assertEquals(GlidePoint(150f, 50f), stroke.points.last())
        }
    }

    @Test fun `old historical events cannot reorder the stroke or replace its endpoint`() {
        val stroke = stroke()
        stroke.add(GlidePoint(150f, 50f), 150, 'b')
        stroke.add(GlidePoint(200f, 50f), 140, 'c')
        assertEquals(GlidePoint(150f, 50f), stroke.points.last())
        assertFalse(stroke.rejected)
    }

    @Test fun `zero sized surfaces and excessive duration are refused`() {
        assertTrue(GlideStroke(GlidePoint(1f, 1f), 0, 'a', 100f, 0, 500).rejected)
        val stroke = stroke()
        stroke.add(GlidePoint(150f, 50f), 15200, 'b')
        assertTrue(stroke.rejected)
    }
}
