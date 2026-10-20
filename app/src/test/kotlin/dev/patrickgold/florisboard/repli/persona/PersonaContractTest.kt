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

package dev.patrickgold.florisboard.repli.persona

import dev.patrickgold.florisboard.repli.capture.ConversationTurn
import dev.patrickgold.florisboard.repli.data.VoiceProfileCodec
import dev.patrickgold.florisboard.repli.profile.VoiceProfile
import dev.patrickgold.florisboard.repli.profile.VoiceStyle
import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyPrivacyPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonaContractTest {
    @Test fun `old saved chats inherit their matching built-in persona`() {
        val oldRecord = "profile-id|Alex|Friend|WARM"
        assertEquals("warm", VoiceProfileCodec.decode(oldRecord)?.personaId)
    }

    @Test fun `custom persona selection survives chat storage`() {
        val profile = VoiceProfile("profile-id", "Alex", "Friend", VoiceStyle.CASUAL,
            "17f96e1d-a12f-4e4c-8cbc-b5e11d7507ec")
        assertEquals(profile, VoiceProfileCodec.decode(VoiceProfileCodec.encode(profile)))
    }

    @Test fun `previous captured names survive chat storage`() {
        val profile = VoiceProfile("same-profile-id", "Abdul Hakeem", "Friend",
            VoiceStyle.WARM, "warm", listOf("Abdulkareem", "Abdul, Hakeem"))
        assertEquals(profile, VoiceProfileCodec.decode(VoiceProfileCodec.encode(profile)))
    }

    @Test fun `selected persona travels with approved reply context`() {
        val persona = Persona("flirty", "The Spark", "Playful, but respectful.",
            listOf("You just made me smile 😂"), VoiceStyle.CASUAL)
        val request = RemoteReplyPrivacyPolicy.prepare(
            turns = listOf(ConversationTurn("Hi there", fromMe = false)),
            fallbackStyle = VoiceStyle.CASUAL,
            learnedSnapshot = null,
            persona = persona,
        )
        assertEquals("The Spark", request.style.personaName)
        assertEquals(persona.description, request.style.personaDescription)
        assertEquals(persona.examples, request.style.personaExamples)
        assertEquals(null, request.profileId)
    }
}
