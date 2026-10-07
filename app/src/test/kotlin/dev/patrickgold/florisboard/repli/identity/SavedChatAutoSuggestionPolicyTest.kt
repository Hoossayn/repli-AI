package dev.patrickgold.florisboard.repli.identity

import dev.patrickgold.florisboard.repli.profile.RecentMessage
import dev.patrickgold.florisboard.repli.profile.VoiceProfile
import dev.patrickgold.florisboard.repli.profile.VoiceStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedChatAutoSuggestionPolicyTest {
    private val now = 1_000_000L
    private val alex = VoiceProfile("alex", "Alex", "Friend", VoiceStyle.CASUAL)
    private val incoming = RecentMessage(
        sourcePackage = "com.whatsapp", sender = "Alex", text = "Are we still on?",
        receivedAt = now - 1_000, notificationKey = "notification-alex",
        conversationId = "alex", openedAt = now - 500,
    )

    private fun select(message: RecentMessage = incoming, profiles: List<VoiceProfile> = listOf(alex),
        composerEmpty: Boolean = true, messages: List<RecentMessage> = listOf(message)) =
        SavedChatAutoSuggestionPolicy.select(
            ConversationIdentityResolver.resolve("com.whatsapp", messages, now),
            profiles, messages, composerEmpty, now,
        )

    @Test fun `just opened notification for exactly one saved chat is offered`() {
        assertEquals(alex.id, select()?.profile?.id)
        assertEquals(incoming.text, select()?.message?.text)
    }

    @Test fun `recent but unopened or stale notification is not offered`() {
        assertNull(select(incoming.copy(openedAt = null)))
        assertNull(select(incoming.copy(openedAt = now - 15_001)))
    }

    @Test fun `an existing composer draft is not interrupted`() {
        assertNull(select(composerEmpty = false))
    }

    @Test fun `partial or ambiguous contact names cannot select a profile`() {
        assertNull(select(incoming.copy(sender = "Alex Smith")))
        assertNull(select(profiles = listOf(alex, alex.copy(id = "other"))))
    }

    @Test fun `a newer message in the same conversation invalidates the opened one`() {
        val newer = incoming.copy(text = "Actually tomorrow?", receivedAt = now,
            notificationKey = "notification-new", openedAt = null)
        assertNull(select(messages = listOf(incoming, newer)))
    }
}
