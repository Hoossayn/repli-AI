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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyScreenCaptureServiceTest {
    @Test
    fun completedCaptureIsNotReportedAsInterruptedWhileAiReads() {
        assertFalse(shouldReportCaptureInterruption(resultDelivered = true, ReplyPhase.READING))
    }

    @Test
    fun unfinishedCaptureIsReportedAsInterrupted() {
        assertTrue(shouldReportCaptureInterruption(resultDelivered = false, ReplyPhase.RETURNING))
        assertTrue(shouldReportCaptureInterruption(resultDelivered = false, ReplyPhase.READING))
    }

    @Test
    fun finishedOrInactivePhasesAreNotReportedAsInterrupted() {
        assertFalse(shouldReportCaptureInterruption(resultDelivered = false, ReplyPhase.CAPTURE_REVIEW))
        assertFalse(shouldReportCaptureInterruption(resultDelivered = false, ReplyPhase.CONTEXT))
        assertFalse(shouldReportCaptureInterruption(resultDelivered = false, ReplyPhase.ERROR))
        assertFalse(shouldReportCaptureInterruption(resultDelivered = false, null))
    }
}
