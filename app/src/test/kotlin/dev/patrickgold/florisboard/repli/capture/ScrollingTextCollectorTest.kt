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

class ScrollingTextCollectorTest {
    @Test
    fun `collects selected side and deduplicates overlapping frames`() {
        val collector = ScrollingTextCollector(MessageSide.RIGHT)
        val frame = listOf(
            OcrTextRegion("incoming", 20, 300, 220, 340),
            OcrTextRegion("yeah i'll be there", 560, 400, 980, 450),
            OcrTextRegion("10:42 PM", 700, 455, 790, 480),
            OcrTextRegion("Message…", 600, 930, 900, 970),
        )

        assertEquals(1, collector.addFrame(frame, 1_000, 1_000))
        assertEquals(0, collector.addFrame(frame, 1_000, 1_000))
        assertEquals(listOf("yeah i'll be there"), collector.messages())
    }

    @Test
    fun `supports chats where own messages appear on the left`() {
        val collector = ScrollingTextCollector(MessageSide.LEFT)
        collector.addFrame(
            listOf(
                OcrTextRegion("mine", 20, 300, 200, 350),
                OcrTextRegion("theirs", 700, 400, 980, 450),
            ),
            1_000,
            1_000,
        )

        assertEquals(listOf("mine"), collector.messages())
    }

    @Test
    fun `ignores the entire notification shade frame containing capture controls`() {
        val collector = ScrollingTextCollector(MessageSide.RIGHT)
        val added = collector.addFrame(
            listOf(
                OcrTextRegion("Learning from visible messages", 120, 500, 650, 560),
                OcrTextRegion("Bluetooth", 500, 200, 680, 250),
                OcrTextRegion("Stop and review", 350, 700, 650, 760),
            ),
            720,
            1_280,
        )

        assertEquals(0, added)
        assertEquals(emptyList<String>(), collector.messages())
    }
}
