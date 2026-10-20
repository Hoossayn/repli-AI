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

import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyManualCapturePolicyTest {
    @Test fun `only a blank chat stops after the first frame`() {
        assertEquals(
            ManualCaptureDecision.SHORT_CHAT,
            ReplyManualCapturePolicy.afterFrame(1, 0, 0),
        )
        for (turns in 1..3) assertEquals(
            ManualCaptureDecision.CONTINUE,
            ReplyManualCapturePolicy.afterFrame(1, turns, 0),
        )
    }

    @Test fun `longer first frame opens the manual scrolling window`() {
        assertEquals(ManualCaptureDecision.CONTINUE, ReplyManualCapturePolicy.afterFrame(1, 4, 0))
    }

    @Test fun `explicit page capture remains bounded by time and frame count`() {
        assertEquals(ManualCaptureDecision.CONTINUE, ReplyManualCapturePolicy.afterFrame(2, 10, 60_000))
        assertEquals(ManualCaptureDecision.TIME_LIMIT, ReplyManualCapturePolicy.afterFrame(2, 10, 180_000))
        assertEquals(ManualCaptureDecision.FRAME_LIMIT, ReplyManualCapturePolicy.afterFrame(4, 15, 6_000))
    }

    @Test fun `visual difference ignores equal frames and detects moved content`() {
        assertEquals(0, visualDifference(intArrayOf(10, 20, 30), intArrayOf(10, 20, 30)))
        assertEquals(10, visualDifference(intArrayOf(10, 20, 30), intArrayOf(20, 30, 40)))
    }
}
