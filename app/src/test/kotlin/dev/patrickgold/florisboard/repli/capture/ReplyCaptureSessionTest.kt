package dev.patrickgold.florisboard.repli.capture

import dev.patrickgold.florisboard.repli.suggestions.ReplyIntent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ReplyCaptureSessionTest {
    private val editor = ReplyEditor("chat.app", 42, "composer")
    @AfterTest fun clear() { ReplyCaptureSession.clear() }

    @Test fun `guidance is volatile and scoped to matching editor across capture and notification seeding`() {
        val draft = ReplyCaptureSession.editGuidance(editor)
        assertFalse(draft.busy)
        ReplyCaptureSession.finishGuidance(draft.id, "  Decline politely\nand suggest next week  ")
        val expected = "Decline politely\nand suggest next week"
        assertEquals(expected, ReplyCaptureSession.state.value?.instructions)
        assertEquals(expected, ReplyCaptureSession.begin(editor).instructions)
        assertEquals(expected, ReplyCaptureSession.beginWithContext(editor, listOf(ConversationTurn("Coffee?", false))).instructions)
        assertNull(ReplyCaptureSession.begin(editor.copy(fieldId = 43)).instructions)
        ReplyCaptureSession.clear()
        assertNull(ReplyCaptureSession.begin(editor).instructions)
    }

    @Test fun `editing guidance invalidates approval replies and late completion even after cancel`() {
        val old = ReplyCaptureSession.beginWithContext(editor, listOf(ConversationTurn("Coffee?", false)))
        ReplyCaptureSession.update(old.id) { it.copy(phase = ReplyPhase.APPROVAL, replies = listOf("old")) }
        val draft = ReplyCaptureSession.editGuidance(editor)
        assertNotEquals(old.id, draft.id)
        assertEquals(ReplyPhase.DRAFT, draft.phase)
        assertTrue(draft.replies.isEmpty())
        assertEquals(old.turns, draft.turns)
        ReplyCaptureSession.update(old.id) { it.copy(phase = ReplyPhase.READY, replies = listOf("late")) }
        assertEquals(draft, ReplyCaptureSession.state.value)
        ReplyCaptureSession.finishGuidance(old.id, "stale edit")
        assertEquals(draft, ReplyCaptureSession.state.value)
        ReplyCaptureSession.finishGuidance(draft.id, null)
        assertEquals(ReplyPhase.CONTEXT, ReplyCaptureSession.state.value?.phase)
        assertNull(ReplyCaptureSession.state.value?.instructions)
        ReplyCaptureSession.finishGuidance(draft.id, "late second Done")
        assertNull(ReplyCaptureSession.state.value?.instructions)
    }

    @Test fun `guidance length is validated without silently truncating and whitespace clears it`() {
        val draft = ReplyCaptureSession.editGuidance(editor)
        try {
            ReplyCaptureSession.finishGuidance(draft.id, "x".repeat(501))
            fail("Oversized guidance must not be truncated")
        } catch (_: IllegalArgumentException) { assertEquals(draft, ReplyCaptureSession.state.value) }
        ReplyCaptureSession.finishGuidance(draft.id, " \n ")
        assertNull(ReplyCaptureSession.state.value?.instructions)
    }

    @Test fun `stale OCR completion cannot update a newer request or revive cleared context`() {
        val old = ReplyCaptureSession.begin(editor)
        val new = ReplyCaptureSession.begin(editor)
        ReplyCaptureSession.fail(old.id, "Late error")
        assertEquals(new, ReplyCaptureSession.state.value)
        ReplyCaptureSession.clear()
        ReplyCaptureSession.update(new.id) { it.copy(replies = listOf("Late reply")) }
        assertNull(ReplyCaptureSession.state.value)
    }

    @Test fun `append preserves only matching editor context and never old replies`() {
        val first = ReplyCaptureSession.begin(editor)
        val turns = listOf(ConversationTurn("Hello", false, TurnSource(0, 10, 10, 100, 100)))
        ReplyCaptureSession.update(first.id) { it.copy(turns = turns, frames = 1, replies = listOf("Hi")) }
        val appended = ReplyCaptureSession.begin(editor, append = true)
        assertEquals(turns.map { it.copy(source = null) }, appended.turns)
        assertEquals(1, appended.frames)
        assertTrue(appended.replies.isEmpty())
        val other = ReplyCaptureSession.begin(editor.copy(packageName = "different.app"), append = true)
        assertTrue(other.turns.isEmpty())
        assertEquals(0, other.frames)
    }

    @Test fun `fresh conversation intent survives guidance and added pages but resets for a new capture`() {
        val first = ReplyCaptureSession.beginWithContext(editor, listOf(ConversationTurn("Okay, settled", false)))
        ReplyCaptureSession.update(first.id) { it.copy(replyIntent = ReplyIntent.FRESH_START) }
        val draft = ReplyCaptureSession.editGuidance(editor)
        assertEquals(ReplyIntent.FRESH_START, draft.replyIntent)
        ReplyCaptureSession.finishGuidance(draft.id, "Ask how their day was", reviewBeforeGenerate = true)
        assertEquals(ReplyIntent.FRESH_START, ReplyCaptureSession.state.value?.replyIntent)
        assertEquals(ReplyPhase.REVIEW, ReplyCaptureSession.state.value?.phase)
        assertEquals(ReplyIntent.FRESH_START, ReplyCaptureSession.begin(editor, append = true).replyIntent)
        assertEquals(ReplyIntent.REPLY, ReplyCaptureSession.begin(editor).replyIntent)
    }

    @Test fun `fresh opening with all old messages removed returns from guidance to review`() {
        val review = ReplyCaptureSession.beginWithContext(editor, emptyList())
        ReplyCaptureSession.update(review.id) { it.copy(replyIntent = ReplyIntent.FRESH_START) }
        val draft = ReplyCaptureSession.editGuidance(editor)
        ReplyCaptureSession.finishGuidance(draft.id, "Ask how their day went", reviewBeforeGenerate = true)
        assertEquals(ReplyPhase.REVIEW, ReplyCaptureSession.state.value?.phase)
        assertEquals(ReplyIntent.FRESH_START, ReplyCaptureSession.state.value?.replyIntent)
        assertTrue(ReplyCaptureSession.state.value!!.turns.isEmpty())
        assertEquals("Ask how their day went", ReplyCaptureSession.state.value?.instructions)
    }

    @Test fun `capture keeps the keyboard-hidden viewport through the consent round trip`() {
        val viewport = CaptureViewport(width = 1_080, height = 2_410, contentBottom = 2_170, readyAt = 0)
        val capture = ReplyCaptureSession.begin(editor, viewport = viewport)
        assertEquals(ReplyPhase.CONSENT, capture.phase)
        assertEquals(viewport, capture.viewport)

        ReplyCaptureSession.update(capture.id) {
            it.copy(phase = ReplyPhase.RETURNING, viewport = it.viewport?.copy(readyAt = 1234))
        }
        assertEquals(2_170, ReplyCaptureSession.state.value?.viewport?.contentBottom)
        assertEquals(1234L, ReplyCaptureSession.state.value?.viewport?.readyAt)
        ReplyCaptureSession.update(capture.id) {
            it.copy(phase = ReplyPhase.READY, replies = listOf("Okay"), viewport = null)
        }
        assertTrue(ReplyCaptureSession.consumeKeyboardReturn(editor))
        assertEquals(listOf("Okay"), ReplyCaptureSession.state.value?.replies)
        assertFalse(ReplyCaptureSession.state.value!!.awaitingKeyboardReturn)
        assertFalse(ReplyCaptureSession.consumeKeyboardReturn(editor))
    }

    @Test fun `keyboard-hidden capture return is scoped to the exact editor`() {
        val viewport = CaptureViewport(1_080, 2_410, 2_170, 0)
        val capture = ReplyCaptureSession.begin(editor, viewport = viewport)
        assertFalse(ReplyCaptureSession.consumeKeyboardReturn(editor.copy(fieldId = 43)))
        assertTrue(ReplyCaptureSession.state.value!!.awaitingKeyboardReturn)
        assertTrue(ReplyCaptureSession.consumeKeyboardReturn(editor))
        ReplyCaptureSession.update(capture.id) { it.copy(phase = ReplyPhase.READY) }
        assertFalse(ReplyCaptureSession.consumeKeyboardReturn(editor))
    }

    @Test fun `fullscreen review return can rebind a recreated composer in the same app`() {
        val approved = ReplyCaptureSession.beginWithContext(
            editor,
            listOf(ConversationTurn("Coffee?", false)),
        )
        ReplyCaptureSession.update(approved.id) { it.copy(phase = ReplyPhase.APPROVAL) }
        val recreated = editor.copy(fieldId = 99, fieldName = null)

        assertTrue(ReplyCaptureSession.rebindEditorForReviewReturn(approved.id, recreated))
        assertEquals(recreated, ReplyCaptureSession.state.value?.editor)
        assertEquals(ReplyPhase.APPROVAL, ReplyCaptureSession.state.value?.phase)
        assertEquals(listOf("Coffee?"), ReplyCaptureSession.state.value?.turns?.map { it.text })
        assertFalse(ReplyCaptureSession.rebindEditorForReviewReturn(
            approved.id,
            recreated.copy(packageName = "another.app"),
        ))
        assertFalse(ReplyCaptureSession.rebindEditorForReviewReturn("stale-request", recreated))
    }

    @Test fun `confirmed notification context enters review without screen capture`() {
        val turns = listOf(ConversationTurn("Are you free tonight?", false))
        val state = ReplyCaptureSession.beginWithContext(editor, turns)
        assertEquals(ReplyPhase.REVIEW, state.phase)
        assertEquals(turns, state.turns)
        assertEquals(0, state.frames)
        assertTrue(state.replies.isEmpty())
    }

    @Test fun `editing ready replies keeps them through guidance and microphone permission`() {
        val previous = ReplyCaptureSession.beginWithContext(editor,
            listOf(ConversationTurn("Coffee?", false)))
        ReplyCaptureSession.update(previous.id) {
            it.copy(phase = ReplyPhase.READY, replies = listOf("Yes, tomorrow works"))
        }
        val draft = ReplyCaptureSession.editGuidance(editor)
        assertEquals(listOf("Yes, tomorrow works"), draft.replies)
        assertTrue(ReplyCaptureSession.beginMicrophonePermission(draft.id, "later in the week"))
        assertEquals(listOf("Yes, tomorrow works"), ReplyCaptureSession.state.value?.replies)
        assertTrue(ReplyCaptureSession.returnFromMicrophonePermission(draft.id, false))
        assertEquals("later in the week", ReplyCaptureSession.resumeMicrophonePermission(draft.id, editor)?.draft)
        ReplyCaptureSession.finishGuidance(draft.id, "later in the week", returnToReplies = true)
        assertEquals(ReplyPhase.READY, ReplyCaptureSession.state.value?.phase)
        assertEquals(listOf("Yes, tomorrow works"), ReplyCaptureSession.state.value?.replies)
    }

    @Test fun `microphone permission preserves only an uncommitted same-editor draft`() {
        val saved = ReplyCaptureSession.editGuidance(editor)
        ReplyCaptureSession.finishGuidance(saved.id, "old intent")
        val draft = ReplyCaptureSession.editGuidance(editor)
        assertTrue(ReplyCaptureSession.beginMicrophonePermission(draft.id, "unsaved"))
        assertTrue(ReplyCaptureSession.state.value!!.busy)
        ReplyCaptureSession.finishGuidance(draft.id, "late Done")
        assertEquals("old intent", ReplyCaptureSession.state.value?.instructions)
        assertTrue(ReplyCaptureSession.returnFromMicrophonePermission(draft.id, true))
        assertNull(ReplyCaptureSession.resumeMicrophonePermission(draft.id, editor.copy(fieldId = 43)))
        assertEquals(MicrophoneGuidanceReturn("unsaved", true), ReplyCaptureSession.resumeMicrophonePermission(draft.id, editor))
        assertNull(ReplyCaptureSession.resumeMicrophonePermission(draft.id, editor))
        assertNull(ReplyCaptureSession.state.value?.microphoneDraft)
        assertFalse(ReplyCaptureSession.state.value!!.recordAfterPermission)
        assertEquals(ReplyPhase.DRAFT, ReplyCaptureSession.state.value?.phase)
        assertEquals("old intent", ReplyCaptureSession.state.value?.instructions)
    }

    @Test fun `denial preserves draft but cannot authorize recording or revive cleared context`() {
        val draft = ReplyCaptureSession.editGuidance(editor)
        ReplyCaptureSession.beginMicrophonePermission(draft.id, "unsaved")
        assertTrue(ReplyCaptureSession.returnFromMicrophonePermission(draft.id, false))
        assertFalse(ReplyCaptureSession.returnFromMicrophonePermission(draft.id, true))
        assertEquals(MicrophoneGuidanceReturn("unsaved", false), ReplyCaptureSession.resumeMicrophonePermission(draft.id, editor))
        ReplyCaptureSession.clear()
        assertFalse(ReplyCaptureSession.returnFromMicrophonePermission(draft.id, true))
        assertNull(ReplyCaptureSession.state.value)
    }

    @Test fun `permission bridge cannot attach to approval or a newer request`() {
        val approved = ReplyCaptureSession.beginWithContext(editor, listOf(ConversationTurn("Coffee?", false)))
        ReplyCaptureSession.update(approved.id) { it.copy(phase = ReplyPhase.APPROVAL) }
        assertFalse(ReplyCaptureSession.beginMicrophonePermission(approved.id, "draft"))
        val draft = ReplyCaptureSession.editGuidance(editor)
        assertFalse(ReplyCaptureSession.beginMicrophonePermission(draft.id, "x".repeat(501)))
        assertTrue(ReplyCaptureSession.beginMicrophonePermission(draft.id, "old"))
        val newer = ReplyCaptureSession.editGuidance(editor.copy(packageName = "another.app"))
        assertNull(newer.microphoneDraft)
        assertFalse(ReplyCaptureSession.returnFromMicrophonePermission(draft.id, true))
        assertEquals(newer, ReplyCaptureSession.state.value)
    }
}
