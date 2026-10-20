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

package dev.patrickgold.florisboard.repli.personalization

import dev.patrickgold.florisboard.repli.profile.VoiceStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextingStyleAnalyzerTest {
    private val analyzer = TextingStyleAnalyzer()

    @Test
    fun `learns lowercase short emoji style and ignores attachment placeholders`() {
        val snapshot = analyzer.analyze(
            listOf(
                "yeahh 😂",
                "i'm coming",
                "okay bet 😂",
                "what time?",
                "😂",
                "<Media omitted>",
            ),
        )

        assertEquals(5, snapshot.style.messagesAnalyzed)
        assertEquals(1.0, snapshot.style.lowercaseStartRatio, 0.001)
        assertEquals(listOf("😂"), snapshot.style.preferredEmojis)
        assertTrue(snapshot.style.averageWords < 3.0)
        assertEquals(VoiceStyle.WARM, analyzer.inferFallbackStyle(snapshot.style))
    }

    @Test
    fun `infers direct fallback from capitalized punctuated messages`() {
        val snapshot = analyzer.analyze(
            listOf(
                "That works for me.",
                "I will confirm tomorrow.",
                "Please send the document.",
                "Thank you for the update.",
            ),
        )

        assertEquals(VoiceStyle.DIRECT, analyzer.inferFallbackStyle(snapshot.style))
        assertTrue(snapshot.style.summary().contains("usually capitalized"))
    }
}
