package dev.patrickgold.florisboard.repli.suggestions

import dev.patrickgold.florisboard.repli.capture.ConversationTurn
import dev.patrickgold.florisboard.repli.persona.Persona
import dev.patrickgold.florisboard.repli.profile.VoiceStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplySituationContractTest {
    @Test fun `new direction and timezone travel with the user's persona and guidance`() {
        val persona = Persona("casual", "Easy Breezy", "Relaxed and casual", emptyList(), VoiceStyle.CASUAL)
        val prepared = RemoteReplyPrivacyPolicy.prepare(
            turns = listOf(ConversationTurn("Good morning", fromMe = false)),
            fallbackStyle = VoiceStyle.CASUAL, learnedSnapshot = null,
            persona = persona, instructions = "Ask how their day went",
            replyIntent = ReplyIntent.FRESH_START, timeZoneId = "Africa/Lagos",
        )
        assertEquals(SharedReplySituation(ReplyIntent.FRESH_START, "Africa/Lagos"), prepared.replySituation)
        assertEquals("fresh_start", prepared.replySituation.intent.wireValue)
        assertEquals("Ask how their day went", prepared.instructions)
        assertEquals("Easy Breezy", prepared.style.personaName)
        assertEquals("Good morning", prepared.context.single().text)
    }

    @Test fun `default replies still answer context and a fresh opening can omit old messages`() {
        val reply = RemoteReplyPrivacyPolicy.prepare(
            listOf(ConversationTurn("Coffee?", false)), VoiceStyle.CASUAL, null, timeZoneId = "GMT+05:30",
        )
        assertEquals(ReplyIntent.REPLY, reply.replySituation.intent)
        assertEquals("GMT+05:30", reply.replySituation.timeZoneId)
        val fresh = RemoteReplyPrivacyPolicy.prepare(
            emptyList(), VoiceStyle.CASUAL, null, replyIntent = ReplyIntent.FRESH_START, timeZoneId = "Africa/Lagos",
        )
        assertTrue(fresh.context.isEmpty())
        assertEquals(ReplyIntent.FRESH_START, fresh.replySituation.intent)
    }
}
