/*
 * Copyright (C) 2025-2026 The Repli Keyboard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.repli.capture

enum class ManualCaptureDecision {
    CONTINUE,
    SHORT_CHAT,
    USER_DONE,
    FRAME_LIMIT,
    TIME_LIMIT,
}

/** Bounds the user-started, explicitly selected page-capture window. */
object ReplyManualCapturePolicy {
    const val MAX_DURATION_MS = 180_000L
    const val MAX_FRAMES = 4

    fun afterFrame(
        framesCaptured: Int,
        totalTurns: Int,
        elapsedMs: Long,
    ): ManualCaptureDecision = when {
        framesCaptured == 1 && totalTurns == 0 -> ManualCaptureDecision.SHORT_CHAT
        framesCaptured >= MAX_FRAMES -> ManualCaptureDecision.FRAME_LIMIT
        elapsedMs >= MAX_DURATION_MS -> ManualCaptureDecision.TIME_LIMIT
        else -> ManualCaptureDecision.CONTINUE
    }
}
