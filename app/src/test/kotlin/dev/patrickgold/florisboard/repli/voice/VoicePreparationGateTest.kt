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

package dev.patrickgold.florisboard.repli.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class VoicePreparationGateTest {
    @Test fun `warm metadata completion never starts the microphone`() {
        val gate = VoicePreparationGate(); var queries = 0; var starts = 0
        gate.prepare { queries++ }; gate.prepare { queries++ }; gate.complete()
        assertEquals(1, queries); assertEquals(0, starts)
        gate.listen({ queries++ }) { starts++ }
        assertEquals(1, queries); assertEquals(1, starts)
    }
    @Test fun `tap during preflight shares one query then starts exactly once`() {
        val gate = VoicePreparationGate(); var queries = 0; var starts = 0
        gate.prepare { queries++ }
        gate.listen({ queries++ }) { starts++ }; gate.listen({ queries++ }) { starts++ }
        assertEquals(1, queries); assertEquals(0, starts)
        gate.complete(); gate.complete(); gate.listen({ queries++ }) { starts++ }
        assertEquals(1, starts)
    }
    @Test fun `cold explicit tap starts a query but cannot listen before it completes`() {
        val gate = VoicePreparationGate(); var starts = 0
        gate.listen({}) { starts++ }; assertEquals(0, starts)
        gate.complete(); assertEquals(1, starts)
    }
    @Test fun `synchronous support callback dispatches an explicit tap once`() {
        val gate = VoicePreparationGate(); var starts = 0
        gate.listen({ gate.complete() }) { starts++ }
        assertEquals(1, starts)
    }
    @Test fun `editor cleanup invalidates queued and late support callbacks`() {
        val gate = VoicePreparationGate(); var starts = 0
        gate.listen({}) { starts++ }; gate.close(); gate.complete()
        gate.listen({ fail("closed driver queried") }) { starts++ }
        assertEquals(0, starts)
    }
}
