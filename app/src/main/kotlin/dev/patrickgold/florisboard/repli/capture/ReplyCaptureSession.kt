package dev.patrickgold.florisboard.repli.capture

import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyPrivacyPolicy
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReplyEditor(val packageName: String, val fieldId: Int, val fieldName: String?)

enum class ReplyPhase { DRAFT, MICROPHONE_PERMISSION, MICROPHONE_RETURNING, CONSENT, RETURNING, READING, CAPTURE_REVIEW, REVIEW, CONTEXT, APPROVAL, GENERATING, READY, ERROR }

/** Full-screen dimensions plus the last chat pixel that may be read.
 * `contentBottom` excludes the host app's composer and system navigation while
 * allowing Repli's keyboard to stay closed during capture. */
data class CaptureViewport(val width: Int, val height: Int, val contentBottom: Int, val readyAt: Long)

data class ReplyCaptureState(
    val id: String,
    val editor: ReplyEditor,
    val phase: ReplyPhase = ReplyPhase.CONSENT,
    val turns: List<ConversationTurn> = emptyList(),
    val replies: List<String> = emptyList(),
    val frames: Int = 0,
    val singleView: Boolean = false,
    val viewport: CaptureViewport? = null,
    val awaitingKeyboardReturn: Boolean = false,
    val consentActivityOpened: Boolean = false,
    val message: String = "Waiting for screen-sharing permission…",
    val generationError: String? = null,
    val instructions: String? = null,
    val microphoneDraft: String? = null,
    val recordAfterPermission: Boolean = false,
) {
    val busy: Boolean get() = phase !in setOf(ReplyPhase.DRAFT, ReplyPhase.REVIEW, ReplyPhase.APPROVAL, ReplyPhase.READY, ReplyPhase.ERROR)
}

/** In-memory, same-process handoff. No screenshot or OCR history goes to disk.
 * Every async completion must match the request ID, preventing old results from
 * appearing after Clear, another capture, or an editor change. */
object ReplyCaptureSession {
    private val mutable = MutableStateFlow<ReplyCaptureState?>(null)
    val state = mutable.asStateFlow()

    fun begin(
        editor: ReplyEditor,
        append: Boolean = false,
        viewport: CaptureViewport? = null,
        singleView: Boolean = false,
    ): ReplyCaptureState {
        val previous = mutable.value?.takeIf { it.editor == editor }
        mutable.value?.id?.let(ReviewEvidenceStore::discard)
        return ReplyCaptureState(
            id = UUID.randomUUID().toString(), editor = editor,
            // The next capture replaces the in-memory source images. Older text stays in
            // context, but its coordinates must not point at a different screenshot.
            turns = if (append) previous?.turns.orEmpty().map { it.copy(source = null) } else emptyList(),
            frames = if (append) previous?.frames ?: 0 else 0,
            singleView = singleView,
            instructions = previous?.instructions,
            viewport = viewport,
            awaitingKeyboardReturn = viewport != null,
        ).also { mutable.value = it }
    }

    fun beginWithContext(editor: ReplyEditor, turns: List<ConversationTurn>): ReplyCaptureState {
        mutable.value?.id?.let(ReviewEvidenceStore::discard)
        return ReplyCaptureState(
            id = UUID.randomUUID().toString(),
            editor = editor,
            phase = ReplyPhase.REVIEW,
            turns = turns.takeLast(ReplyConversation.MAX_TURNS),
            message = "Review this chat, then generate cloud replies",
            instructions = mutable.value?.takeIf { it.editor == editor }?.instructions,
        ).also { mutable.value = it }
    }

    /** Opening the editor revokes the previous approval and invalidates every old async result. */
    fun editGuidance(editor: ReplyEditor): ReplyCaptureState {
        val previous = mutable.value?.takeIf { it.editor == editor }
        return (previous ?: ReplyCaptureState(id = "", editor = editor)).copy(
            id = UUID.randomUUID().toString(), phase = ReplyPhase.DRAFT,
            replies = if (previous?.phase == ReplyPhase.READY) previous.replies else emptyList(),
            viewport = null, microphoneDraft = null, recordAfterPermission = false, message = "Tell Repli what you want to say",
        ).also { mutable.value = it }
    }

    fun finishGuidance(id: String, instructions: String?, reviewBeforeGenerate: Boolean = false,
        returnToReplies: Boolean = false) {
        val prepared = RemoteReplyPrivacyPolicy.prepareInstructions(instructions)
        update(id) {
            if (it.phase != ReplyPhase.DRAFT) return@update it
            it.copy(instructions = prepared,
                phase = when {
                    it.turns.isEmpty() || returnToReplies && it.replies.isNotEmpty() -> ReplyPhase.READY
                    reviewBeforeGenerate -> ReplyPhase.REVIEW
                    else -> ReplyPhase.CONTEXT
                },
                message = when {
                    reviewBeforeGenerate && it.turns.isNotEmpty() -> "Review the chat, then generate with what you want to say"
                    prepared == null -> "Tap the reply icon when you're ready"
                    else -> "What you want to say is saved · tap the reply icon"
                },
            )
        }
    }

    /** Only the OS microphone permission round trip preserves an uncommitted Guide draft. */
    fun beginMicrophonePermission(id: String, draft: String): Boolean {
        val current = mutable.value ?: return false
        if (current.id != id || current.phase != ReplyPhase.DRAFT ||
            draft.length > RemoteReplyPrivacyPolicy.MAX_INSTRUCTION_CHARACTERS) return false
        mutable.value = current.copy(phase = ReplyPhase.MICROPHONE_PERMISSION, microphoneDraft = draft, recordAfterPermission = false)
        return true
    }

    fun returnFromMicrophonePermission(id: String, granted: Boolean): Boolean {
        val current = mutable.value ?: return false
        if (current.id != id || current.phase != ReplyPhase.MICROPHONE_PERMISSION || current.microphoneDraft == null) return false
        mutable.value = current.copy(phase = ReplyPhase.MICROPHONE_RETURNING, recordAfterPermission = granted)
        return true
    }

    fun resumeMicrophonePermission(id: String, editor: ReplyEditor): MicrophoneGuidanceReturn? {
        val current = mutable.value ?: return null
        if (current.id != id || current.editor != editor || current.phase != ReplyPhase.MICROPHONE_RETURNING) return null
        val draft = current.microphoneDraft ?: return null
        mutable.value = current.copy(phase = ReplyPhase.DRAFT, microphoneDraft = null, recordAfterPermission = false)
        return MicrophoneGuidanceReturn(draft, current.recordAfterPermission)
    }

    /** Consume the one keyboard return owned by a capture that deliberately hid it.
     * This lets OCR/generation finish off-screen without the next input-view start
     * mistaking the result for stale context. Every later input start still clears. */
    fun consumeKeyboardReturn(editor: ReplyEditor): Boolean {
        val current = mutable.value ?: return false
        if (!current.awaitingKeyboardReturn || current.editor != editor) return false
        mutable.value = current.copy(awaitingKeyboardReturn = false)
        return true
    }

    /** Rebinds one fullscreen-review return when the same host app recreated its composer.
     * Callers must first validate the request-scoped return token. */
    fun rebindEditorForReviewReturn(id: String, editor: ReplyEditor): Boolean {
        val current = mutable.value ?: return false
        if (current.id != id || current.editor.packageName != editor.packageName) return false
        mutable.value = current.copy(editor = editor)
        return true
    }

    fun update(id: String, transform: (ReplyCaptureState) -> ReplyCaptureState) {
        val current = mutable.value ?: return
        if (current.id == id) mutable.value = transform(current)
    }

    fun fail(id: String, message: String) = update(id) {
        it.copy(phase = ReplyPhase.ERROR, replies = emptyList(), viewport = null, message = message)
    }

    fun clear() {
        mutable.value?.id?.let(PendingVisionCaptureStore::discard)
        mutable.value?.id?.let(ReviewEvidenceStore::discard)
        mutable.value = null
    }
}

data class MicrophoneGuidanceReturn(val draft: String, val startRecording: Boolean)
