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

import dev.patrickgold.florisboard.repli.profile.VoiceProfile
import dev.patrickgold.florisboard.repli.profile.VoiceStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CapturedContactProfileMatcherTest {
    @Test fun `visible title reuses saved chat despite a different AI spelling`() {
        val saved = profile("Abdulhakeem")
        val match = CapturedContactProfileMatcher.resolve(
            localHeaderName = "Abdulhakeem", aiName = "Abdulkareem", profiles = listOf(saved),
        )
        assertEquals(CapturedContactProfileMatcher.Result.Existing(saved), match)
    }

    @Test fun `one character OCR error reuses a unique existing chat`() {
        val saved = profile("Abdulhakeem")
        val match = CapturedContactProfileMatcher.resolve(
            localHeaderName = "Abdullhakeem", aiName = null, profiles = listOf(saved),
        )
        assertEquals(CapturedContactProfileMatcher.Result.Existing(saved), match)
    }

    @Test fun `two readings reconnect an earlier AI misspelling`() {
        val saved = profile("Abdulkareem")
        assertEquals(CapturedContactProfileMatcher.Result.Existing(saved),
            CapturedContactProfileMatcher.resolve("Abdulhakeem", "Abdulkareem", listOf(saved)))
    }

    @Test fun `new chat uses visible title instead of AI guess`() {
        val match = CapturedContactProfileMatcher.resolve(
            localHeaderName = "Abdulhakeem", aiName = "Abdulkareem", profiles = emptyList(),
        )
        assertEquals(CapturedContactProfileMatcher.Result.New("Abdulhakeem"), match)
    }

    @Test fun `short names and ambiguous long names are not fuzzy merged`() {
        val ann = profile("Ann")
        assertEquals(CapturedContactProfileMatcher.Result.New("Anna"),
            CapturedContactProfileMatcher.resolve("Anna", null, listOf(ann)))

        val one = profile("Abdulhakeem")
        val two = profile("Abdulhakeen")
        assertNull(CapturedContactProfileMatcher.resolve("Abdulhakeer", null, listOf(one, two)))
    }

    @Test fun `AI name is used when header reading found no title`() {
        val saved = profile("Abdulhakeem")
        assertEquals(CapturedContactProfileMatcher.Result.Existing(saved),
            CapturedContactProfileMatcher.resolve(null, "Abdulhakeem", listOf(saved)))
    }

    @Test fun `unrelated AI name cannot override a new visible title`() {
        val saved = profile("Christopher")
        assertEquals(CapturedContactProfileMatcher.Result.New("Abdulhakeem"),
            CapturedContactProfileMatcher.resolve("Abdulhakeem", "Christopher", listOf(saved)))
    }

    @Test fun `clock readings cannot become a saved chat`() {
        assertNull(CapturedContactProfileMatcher.resolve(
            localHeaderName = "10:48 00:00", aiName = "10:48", profiles = emptyList(),
        ))
    }

    @Test fun `old captured spelling finds renamed chat with the same profile ID`() {
        val renamed = profile("Abdulhakeem").copy(name = "Abdul Hakeem",
            nameAliases = listOf("Abdulkareem"))
        assertEquals(CapturedContactProfileMatcher.Result.Existing(renamed),
            CapturedContactProfileMatcher.resolve("Abdulkareem", null, listOf(renamed)))
        assertEquals(CapturedContactProfileMatcher.Result.Existing(renamed),
            CapturedContactProfileMatcher.resolve("Abdul Hakeem", null, listOf(renamed)))
    }

    @Test fun `ambiguous captured alias is not silently merged`() {
        val one = profile("Alex").copy(nameAliases = listOf("Alec"))
        val two = profile("Alec")
        assertNull(CapturedContactProfileMatcher.resolve("Alec", null, listOf(one, two)))
    }

    private fun profile(name: String) = VoiceProfile(
        id = "profile-$name", name = name, relationship = "Added from AI capture",
        style = VoiceStyle.CASUAL,
    )
}
