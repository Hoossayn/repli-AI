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

package dev.patrickgold.florisboard.ime.keyboard3.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard3.interaction.PointerTracker
import dev.patrickgold.jetpref.datastore.model.collectAsState

/** Only this overlay observes high-frequency stroke data; key composition stays independent. */
@Composable
fun BoxScope.GlideTrail(pointerTracker: PointerTracker) {
    val prefs by FlorisPreferenceStore
    val showTrail by prefs.glide.showTrail.collectAsState()
    val duration by prefs.glide.trailDuration.collectAsState()
    val trail by pointerTracker.glideTrail.collectAsState()
    val alpha = remember { Animatable(1f) }
    val color = MaterialTheme.colorScheme.primary
    LaunchedEffect(trail.version, showTrail, duration) {
        if (trail.active) {
            alpha.snapTo(1f)
        } else {
            if (showTrail && trail.points.isNotEmpty()) {
                alpha.snapTo(1f)
                alpha.animateTo(0f, tween(duration.coerceIn(0, 500)))
            }
            pointerTracker.clearGlideTrail(trail.version)
        }
    }
    if (showTrail && trail.points.size > 1) {
        val path = remember(trail.points) {
            Path().apply {
                moveTo(trail.points.first().x, trail.points.first().y)
                trail.points.drop(1).forEach { lineTo(it.x, it.y) }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            drawPath(path, color.copy(alpha = 0.85f * alpha.value),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
