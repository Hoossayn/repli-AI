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

import dev.patrickgold.florisboard.repli.profile.RecentMessage
import dev.patrickgold.florisboard.repli.profile.VoiceProfile
import dev.patrickgold.florisboard.repli.profile.VoiceStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConversationIdentityResolverTest {
    private val now = 1_000_000L

    @Test fun `group notifications are never offered as a person`() {
        val result = resolve(message(sender = "Family", group = true))
        assertSame(ConversationIdentityResolution.None, result)
    }

    @Test fun `stale notifications are never offered`() {
        val result = resolve(message(sender = "Alex", receivedAt = now - 5 * 60 * 1_000L - 1))
        assertSame(ConversationIdentityResolution.None, result)
    }

    @Test fun `unsupported apps never produce identity suggestions`() {
        val result = ConversationIdentityResolver.resolve(
            "com.instagram.android",
            listOf(message(sender = "Alex", sourcePackage = "com.instagram.android")),
            now,
        )
        assertSame(ConversationIdentityResolution.None, result)
    }

    @Test fun `multiple recent direct conversations are ambiguous`() {
        val result = resolve(
            message(sender = "Alex", conversationId = "alex"),
            message(sender = "Maya", conversationId = "maya"),
        )
        assertTrue(result is ConversationIdentityResolution.Ambiguous)
        assertEquals(2, (result as ConversationIdentityResolution.Ambiguous).conversationCount)
    }

    @Test fun `messages from the same conversation do not create false ambiguity`() {
        val result = resolve(
            message(sender = "Alex", conversationId = "alex", receivedAt = now - 2_000),
            message(sender = "Alex", conversationId = "alex", receivedAt = now - 1_000),
        )
        assertTrue(result is ConversationIdentityResolution.Suggestion)
        assertEquals(now - 1_000, (result as ConversationIdentityResolution.Suggestion).message.receivedAt)
    }

    @Test fun `one recently opened notification wins over other recent chats`() {
        val result = resolve(
            message(sender = "Alex", conversationId = "alex"),
            message(sender = "Maya", conversationId = "maya", openedAt = now - 500),
        )
        assertTrue(result is ConversationIdentityResolution.Suggestion)
        result as ConversationIdentityResolution.Suggestion
        assertEquals("Maya", result.message.sender)
        assertEquals(IdentityConfidence.OPENED_NOTIFICATION, result.confidence)
    }

    @Test fun `opening an older notification is still a strong current signal`() {
        val result = resolve(message(
            sender = "Alex",
            receivedAt = now - 60 * 60 * 1_000L,
            openedAt = now - 500,
        ))
        assertTrue(result is ConversationIdentityResolution.Suggestion)
        assertEquals(
            IdentityConfidence.OPENED_NOTIFICATION,
            (result as ConversationIdentityResolution.Suggestion).confidence,
        )
    }

    @Test fun `a suggestion cannot become trusted context with the wrong profile`() {
        val suggestion = resolve(message(sender = "Alex")) as ConversationIdentityResolution.Suggestion
        assertNull(ConversationIdentityResolver.confirm(suggestion, profile("Maya")))
        val confirmed = ConversationIdentityResolver.confirm(suggestion, profile("Alex"))
        assertEquals("profile-Alex", confirmed?.profileId)
        assertEquals("Need anything?", confirmed?.message?.text)
    }

    @Test fun `even a clicked notification remains a suggestion until confirmed`() {
        val result = resolve(message(sender = "Alex", openedAt = now - 500))
        assertTrue(result is ConversationIdentityResolution.Suggestion)
        assertEquals(IdentityConfidence.OPENED_NOTIFICATION, (result as ConversationIdentityResolution.Suggestion).confidence)
    }

    @Test fun `fresh confirmed message can seed replies for its selected chat`() {
        val incoming = message("Alex")
        val confirmed = ConversationIdentityResolver.confirm(resolve(incoming) as ConversationIdentityResolution.Suggestion, profile("Alex"))
        assertEquals(incoming.text, ConversationIdentityResolver.trustedIncomingText(
            confirmed, "profile-Alex", "com.whatsapp", listOf(incoming), now,
        ))
    }

    @Test fun `confirmation expires even if the keyboard stays open`() {
        val incoming = message("Alex")
        val confirmed = ConversationIdentityResolver.confirm(resolve(incoming) as ConversationIdentityResolution.Suggestion, profile("Alex"))
        assertNull(ConversationIdentityResolver.trustedIncomingText(
            confirmed, "profile-Alex", "com.whatsapp", listOf(incoming), now + 5 * 60 * 1_000L,
        ))
    }

    @Test fun `changing app or selected tone cannot reuse a confirmed incoming message`() {
        val incoming = message("Alex")
        val confirmed = ConversationIdentityResolver.confirm(resolve(incoming) as ConversationIdentityResolution.Suggestion, profile("Alex"))
        assertNull(ConversationIdentityResolver.trustedIncomingText(
            confirmed, "profile-Maya", "com.whatsapp", listOf(incoming), now,
        ))
        assertNull(ConversationIdentityResolver.trustedIncomingText(
            confirmed, "profile-Alex", "org.telegram.messenger", listOf(incoming), now,
        ))
    }

    @Test fun `a new incoming message requires a new confirmation`() {
        val incoming = message("Alex")
        val confirmed = ConversationIdentityResolver.confirm(resolve(incoming) as ConversationIdentityResolution.Suggestion, profile("Alex"))
        val newer = incoming.copy(text = "Changed plans", receivedAt = now)
        assertNull(ConversationIdentityResolver.trustedIncomingText(
            confirmed, "profile-Alex", "com.whatsapp", listOf(incoming, newer), now,
        ))
        assertNull(ConversationIdentityResolver.trustedIncomingText(
            null, "profile-Alex", "com.whatsapp", listOf(incoming), now,
        ))
    }

    private fun resolve(vararg messages: RecentMessage) = ConversationIdentityResolver.resolve(
        "com.whatsapp",
        messages.toList(),
        now,
    )

    private fun message(
        sender: String,
        sourcePackage: String = "com.whatsapp",
        receivedAt: Long = now - 1_000,
        conversationId: String? = sender.lowercase(),
        group: Boolean = false,
        openedAt: Long? = null,
    ) = RecentMessage(
        sourcePackage = sourcePackage,
        sender = sender,
        text = "Need anything?",
        receivedAt = receivedAt,
        notificationKey = "key-$sender-$receivedAt",
        conversationId = conversationId,
        isGroupConversation = group,
        openedAt = openedAt,
    )

    private fun profile(name: String) = VoiceProfile(
        id = "profile-$name",
        name = name,
        relationship = "Friend",
        style = VoiceStyle.CASUAL,
    )
}
