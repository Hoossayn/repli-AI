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
import kotlin.math.hypot

/** Bounded physical-pixel gesture admission. Word ranking is deliberately separate. */
class GlideStroke(
    start: GlidePoint,
    private val startedAt: Long,
    private val firstLetter: Char,
    private val keyWidth: Float,
    private val surfaceWidth: Int,
    private val surfaceHeight: Int,
) {
    private val samples = mutableListOf(start)
    var active: Boolean = false
        private set
    var rejected: Boolean = !valid(start) || !keyWidth.isFinite() || keyWidth <= 0
        private set
    private var lastTime = startedAt
    val points: List<GlidePoint> get() = samples.toList()

    fun add(point: GlidePoint, uptimeMillis: Long, letter: Char?) {
        if (rejected || uptimeMillis < lastTime) return
        if (!valid(point) || uptimeMillis - startedAt > 15_000) {
            rejected = true
            return
        }
        lastTime = uptimeMillis
        if (samples.last() != point) {
            if (samples.size >= MAX_POINTS) {
                // Keep ordered curvature and both endpoints rather than truncate a long word's end.
                val reduced = samples.filterIndexed { index, _ -> index % 2 == 0 || index == samples.lastIndex }
                samples.clear()
                samples.addAll(reduced)
            }
            samples.add(point)
        }
        val start = samples.first()
        if (!active && uptimeMillis - startedAt >= 30 && letter != null && letter != firstLetter &&
            hypot((point.x - start.x).toDouble(), (point.y - start.y).toDouble()) >= keyWidth * 0.75
        ) active = true
    }

    private fun valid(point: GlidePoint): Boolean = surfaceWidth > 0 && surfaceHeight > 0 &&
        point.x.isFinite() && point.y.isFinite() && point.x >= 0 && point.y >= 0 &&
        point.x < surfaceWidth && point.y < surfaceHeight

    companion object { const val MAX_POINTS = 256 }
}
