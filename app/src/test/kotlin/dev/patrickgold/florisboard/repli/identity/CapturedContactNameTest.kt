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

package dev.patrickgold.florisboard.repli.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CapturedContactNameTest {
    @Test fun `normalizes a person title and keeps phone number identities`() {
        assertEquals("Abdulhakeem", CapturedContactName.prepare("  Abdulhakeem  "))
        assertEquals("+234 801 234 5678", CapturedContactName.prepare("+234  801 234 5678"))
    }

    @Test fun `rejects missing generic group and oversized titles`() {
        listOf(null, "", "WhatsApp", "online", "last seen today", "18 participants",
            "10:48 00:00", "10:48", "00:00", "10 48 00 00", "5G", "x".repeat(81))
            .forEach { assertNull(CapturedContactName.prepare(it)) }
    }

    @Test fun `uses a chat title from the locally read header`() {
        assertEquals("Abdulhakeem", CapturedContactName.fromHeaderLines(listOf("Abdulhakeem", "online")))
        assertEquals("Alex", CapturedContactName.fromHeaderLines(listOf("Alex · practice chat")))
        assertEquals("Abdulhakeem", CapturedContactName.fromHeaderLines(
            listOf("10:48 00:00", "Abdulhakeem", "online")))
        assertNull(CapturedContactName.fromHeaderLines(listOf("WhatsApp")))
        assertNull(CapturedContactName.fromHeaderLines(listOf("Weekend plans", "Ada, Ben, Chika")))
    }
}
