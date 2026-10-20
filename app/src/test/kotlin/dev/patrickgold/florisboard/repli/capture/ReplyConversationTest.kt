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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyConversationTest {
    @Test fun `keeps long incoming messages on the left and removes timestamps and labels`() {
        val regions = listOf(
            OcrTextRegion("Today", 450, 0, 550, 20),
            OcrTextRegion("Want to grab coffee tomorrow?\n10:42 am", 20, 30, 870, 90),
            OcrTextRegion("Let me check\n10:43", 560, 100, 970, 160),
            OcrTextRegion("Message", 30, 180, 150, 200),
        )
        assertEquals(listOf(
            ConversationTurn("Want to grab coffee tomorrow?", false),
            ConversationTurn("Let me check", true),
        ), ReplyConversation.extract(regions, 1000, 250))
    }

    @Test fun `deduplicates frame overlap without losing genuine repeated messages`() {
        val hi = ConversationTurn("Hi", false)
        val okay = ConversationTurn("Okay", true)
        val thanks = ConversationTurn("Thanks", false)
        val old = listOf(hi, okay, thanks)
        val next = listOf(okay, thanks, okay)
        assertEquals(listOf(hi, okay, thanks, okay), ReplyConversation.merge(old, next))
        assertEquals(old, ReplyConversation.merge(old, old))
    }

    @Test fun `infers whether the next frame is older or newer from overlap`() {
        val first = (1..4).map { ConversationTurn("Message $it", false) }
        val older = (0..2).map { ConversationTurn("Message $it", false) }
        val newer = (3..6).map { ConversationTurn("Message $it", false) }

        assertEquals(FrameDirection.OLDER, ReplyConversation.frameDirection(first, older))
        assertEquals(FrameDirection.NEWER, ReplyConversation.frameDirection(first, newer))
        assertNull(ReplyConversation.frameDirection(first, listOf(ConversationTurn("No overlap", true))))
        assertEquals(
            (0..4).map { ConversationTurn("Message $it", false) },
            ReplyConversation.mergeFrame(first, older, FrameDirection.OLDER),
        )
        assertEquals(
            (1..6).map { ConversationTurn("Message $it", false) },
            ReplyConversation.mergeFrame(first, newer, FrameDirection.NEWER),
        )
        assertEquals(
            (0..4).map { ConversationTurn("Message $it", false) },
            ReplyConversation.mergeCapture(first, older),
        )
        assertEquals(
            (1..6).map { ConversationTurn("Message $it", false) },
            ReplyConversation.mergeCapture(first, newer),
        )
    }

    @Test fun `empty and non-text captures produce no conversation`() {
        assertTrue(ReplyConversation.extract(listOf(OcrTextRegion("10:42", 0, 0, 10, 10)), 100, 100).isEmpty())
        assertTrue(ReplyConversation.extract(emptyList(), 0, 100).isEmpty())
    }

    @Test fun `whatsapp capture assigns wide bubbles filters receipt OCR and resolves self quotes`() {
        val longMessage = "except that she and one of our mutual guy been get one 3 weeks situationship kind of thing"
        val regions = listOf(
            OcrTextRegion("lmaoo", 720, 100, 875, 150),
            OcrTextRegion("21:08 VY", 885, 125, 1_025, 155),
            // Text alone is almost centered; the adjacent timestamp completes the
            // right-aligned bubble geometry and makes the speaker unambiguously Me.
            OcrTextRegion(longMessage, 145, 180, 910, 300),
            OcrTextRegion("21:09 v!", 890, 270, 1_030, 310),
            // WhatsApp quote: the indented label and preview reference a Me message;
            // the less-indented line below is the incoming reply body.
            OcrTextRegion("You", 82, 370, 150, 400),
            OcrTextRegion("you remember tolu ?", 84, 410, 480, 445),
            OcrTextRegion("Hmm that babe from your former work place", 55, 470, 820, 510),
            OcrTextRegion("22:05", 800, 490, 920, 520),
            // A second, partly clipped quote repeats a message already visible.
            // It must not become another conversation turn.
            OcrTextRegion("You", 82, 600, 150, 630),
            OcrTextRegion(longMessage, 84, 640, 900, 720),
        )

        assertEquals(
            listOf(
                ConversationTurn("lmaoo", true),
                ConversationTurn(longMessage, true),
                ConversationTurn("you remember tolu ?", true),
                ConversationTurn("Hmm that babe from your former work place", false),
            ),
            ReplyConversation.extract(regions, width = 1_080, height = 1_900),
        )
    }

    @Test fun `removes timestamp receipt suffix without removing a time mentioned in prose`() {
        val regions = listOf(
            OcrTextRegion("Okay 21:09 VV", 650, 20, 980, 60),
            OcrTextRegion("lol, i sure say the guy no really mind like that 23:18 v", 420, 70, 990, 105),
            OcrTextRegion("Meet me at 21:08", 20, 100, 480, 140),
            OcrTextRegion("21:08 v!", 850, 150, 990, 180),
            OcrTextRegion("23:18 VN", 850, 190, 990, 220),
        )
        assertEquals(
            listOf(
                ConversationTurn("Okay", true),
                ConversationTurn("lol, i sure say the guy no really mind like that", true),
                ConversationTurn("Meet me at 21:08", false),
            ),
            ReplyConversation.extract(regions, width = 1_000, height = 800),
        )
    }

    @Test fun `removes named date separators and contact labels from quoted replies`() {
        val repeatedIncoming = "No be say you wan date the girl na"
        val regions = listOf(
            OcrTextRegion(repeatedIncoming, 30, 20, 720, 70),
            OcrTextRegion("Abdulhakeem", 390, 100, 620, 130),
            OcrTextRegion(repeatedIncoming, 392, 135, 850, 175),
            OcrTextRegion("does it matter ?", 360, 200, 850, 240),
            OcrTextRegion("31 August 2026", 410, 280, 670, 320),
            OcrTextRegion("August 31, 2026", 410, 330, 670, 370),
            OcrTextRegion("Shikenan", 35, 400, 300, 440),
        )

        assertEquals(
            listOf(
                ConversationTurn(repeatedIncoming, false),
                ConversationTurn("does it matter ?", true),
                ConversationTurn("Shikenan", false),
            ),
            ReplyConversation.extract(regions, width = 1_080, height = 1_900),
        )
    }

    @Test fun `whatsapp replies to visible self messages keep quoted previews as me and reply bodies as them`() {
        val question = "When be your train tomorrow?"
        val followUp = "Na tomorrow you dey show ba"
        val regions = listOf(
            OcrTextRegion(question, 330, 100, 970, 140),
            OcrTextRegion("22:21 VV", 875, 125, 1_025, 150),
            OcrTextRegion(followUp, 330, 165, 970, 205),
            OcrTextRegion("22:21 VV", 875, 190, 1_025, 215),
            OcrTextRegion("You", 62, 260, 140, 282),
            OcrTextRegion(question, 64, 288, 600, 312),
            OcrTextRegion("2:30", 38, 327, 120, 351),
            OcrTextRegion("22:22", 430, 332, 520, 354),
            OcrTextRegion("You", 62, 390, 140, 412),
            OcrTextRegion(followUp, 64, 418, 600, 442),
            OcrTextRegion("Your side, no", 38, 457, 300, 481),
            OcrTextRegion("22:22", 430, 462, 520, 484),
        )

        assertEquals(
            listOf(
                ConversationTurn(question, true),
                ConversationTurn(followUp, true),
                ConversationTurn("2:30", false),
                ConversationTurn("Your side, no", false),
            ),
            ReplyConversation.extract(regions, width = 1_080, height = 1_900),
        )
    }

    @Test fun `local OCR restores omitted replies and corrects AI speaker ownership`() {
        val local = listOf(
            ConversationTurn("When be your train tomorrow?", true),
            ConversationTurn("Na tomorrow you dey show ba", true),
            ConversationTurn("2:30", false),
            ConversationTurn("Your side, no", false),
        )
        val ai = listOf(
            ConversationTurn("When be your train tomorrow?", false),
            ConversationTurn("Na tomorrow you dey show ba", false),
            ConversationTurn("Your side, no", false),
        )

        assertEquals(local, ReplyConversation.reconcileAiWithLocal(ai, local))
    }

    @Test fun `reconciliation removes WhatsApp chrome and keeps real time replies`() {
        val local = listOf(
            ConversationTurn("E no dey open now", false),
            ConversationTurn("Almustapha Train", false),
            ConversationTurn("Add contact", true),
            ConversationTurn("Send number for the guy abeg", true),
            ConversationTurn("When be your train tomorrow?", true),
            ConversationTurn("2:30", false),
            ConversationTurn("Your side, no", false),
        )
        val ai = listOf(
            ConversationTurn("19:44", false),
            ConversationTurn("E no dey open now", false),
            ConversationTurn("Almustapha Train", false),
            ConversationTurn("Add contact", true),
            ConversationTurn("Send number for the guy abeg 09:33 /", true),
            ConversationTurn("V", true),
            ConversationTurn("When be your train tomorrow?", true),
            ConversationTurn("2:30", false),
            ConversationTurn("Your side, no", false),
        )

        assertEquals(
            listOf(
                ConversationTurn("E no dey open now", false),
                ConversationTurn("Send number for the guy abeg", true),
                ConversationTurn("When be your train tomorrow?", true),
                ConversationTurn("2:30", false),
                ConversationTurn("Your side, no", false),
            ),
            ReplyConversation.reconcileAiWithLocal(ai, local),
        )
    }

    @Test fun `visual bubbles merge overlapping pages without adding OCR fragments`() {
        fun bubble(frame: Int, top: Int, text: String, me: Boolean) = VisualBubble(
            text, me, TurnSource(frame, if (me) 600 else 30, top, if (me) 980 else 430, top + 75),
        )
        val result = ReplyConversation.mergeVisualBubbles(listOf(
            bubble(0, 100, "Interesting", true),
            bubble(0, 700, "If you get 6k I fit link you with who go run the ticket for you", false),
            bubble(1, 100, "If you get 6k I fit link you with who go run the ticket for you", false),
            bubble(1, 260, "For Monday?", true),
            bubble(1, 450, "2:30", false),
        ), frameCount = 2)

        assertEquals(listOf(
            "Interesting", "If you get 6k I fit link you with who go run the ticket for you",
            "For Monday?", "2:30",
        ), result.map { it.text })
        assertEquals(listOf(true, false, true, false), result.map { it.fromMe })
        assertTrue(result.none { it.source?.uncertain == true })
    }

    @Test fun `single short repeated messages are not silently deduplicated`() {
        val result = ReplyConversation.mergeVisualBubbles(listOf(
            VisualBubble("Yeahhh", false, TurnSource(0, 30, 700, 280, 760)),
            VisualBubble("Yeahhh", false, TurnSource(1, 30, 100, 280, 160)),
        ), frameCount = 2)
        assertEquals(2, result.size)
        assertTrue(result.last().source?.uncertain == true)
    }

    @Test fun `capture order reconstructs chronology without model or text permutation`() {
        fun bubble(frame: Int, top: Int, text: String, me: Boolean) = VisualBubble(
            text, me, TurnSource(frame, if (me) 600 else 30, top, if (me) 980 else 430, top + 75),
        )
        val firstBoundary = "If you get 6k I fit link you with who go run the ticket for you"
        val secondBoundary = "Like Saturday and Sunday don't dey fully booked"
        val result = ReplyConversation.mergeVisualBubbles(listOf(
            bubble(0, 100, "Who this", true),
            bubble(0, 700, firstBoundary, false),
            bubble(1, 100, firstBoundary, false),
            bubble(1, 300, "For Monday?", true),
            bubble(1, 700, secondBoundary, false),
            bubble(2, 100, secondBoundary, false),
            bubble(2, 300, "Walai dem don mad finish", false),
        ), frameCount = 3)

        assertEquals(listOf(
            "Who this", firstBoundary, "For Monday?", secondBoundary, "Walai dem don mad finish",
        ), result.map(ConversationTurn::text))
        assertEquals(listOf(0, 0, 1, 1, 2), result.map { it.source?.frameIndex })
    }

    @Test fun `default visual frame order is never changed by repeated text alone`() {
        val repeated = "If you get 6k I fit link you with who go run the ticket for you"
        val result = ReplyConversation.mergeVisualBubbles(listOf(
            VisualBubble("Later page", true, TurnSource(0, 600, 100, 980, 180)),
            VisualBubble(repeated, false, TurnSource(0, 30, 700, 700, 780)),
            VisualBubble("Who this", true, TurnSource(1, 600, 100, 980, 180)),
            VisualBubble(repeated, false, TurnSource(1, 30, 700, 700, 780)),
        ), frameCount = 2)

        assertEquals(listOf(0, 0, 1, 1), result.map { it.source?.frameIndex })
    }

    @Test fun `visual frames without ordering evidence preserve their supplied order`() {
        val result = ReplyConversation.mergeVisualBubbles(listOf(
            VisualBubble("First captured page", false, TurnSource(0, 30, 100, 500, 180)),
            VisualBubble("Second captured page", true, TurnSource(1, 600, 100, 980, 180)),
        ), frameCount = 2)
        assertEquals(listOf(0, 1), result.map { it.source?.frameIndex })
    }

    @Test fun `visual page without overlap keeps turns but marks its seam for review`() {
        val result = ReplyConversation.mergeVisualBubbles(listOf(
            VisualBubble("Old", false, TurnSource(0, 20, 100, 250, 180)),
            VisualBubble("New", true, TurnSource(1, 600, 200, 980, 280)),
        ), frameCount = 2)
        assertEquals(2, result.size)
        assertTrue(result.last().source?.uncertain == true)
    }

    @Test fun `clear bubble-side geometry corrects a mistaken model speaker and flags review`() {
        val result = ReplyConversation.mergeVisualBubbles(listOf(
            VisualBubble("Interesting", false, TurnSource(0, 250, 300, 980, 390)),
        ), frameCount = 1)
        assertEquals(true, result.single().fromMe)
        assertEquals(true, result.single().source?.uncertain)
    }

    @Test fun `caps retained context and preserves newest messages`() {
        val earlier = (0..59).map { ConversationTurn("Message $it", false) }
        val result = ReplyConversation.merge(earlier, listOf(ConversationTurn("Newest", false)))
        assertEquals(60, result.size)
        assertEquals("Message 1", result.first().text)
        assertEquals("Newest", result.last().text)
    }
}
