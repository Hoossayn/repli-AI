package dev.patrickgold.florisboard.repli.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.repli.capture.ReplyCaptureSession
import dev.patrickgold.florisboard.repli.capture.ReplyCaptureState
import dev.patrickgold.florisboard.repli.capture.ReplyEditor
import dev.patrickgold.florisboard.repli.capture.ReplyPhase
import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyPrivacyPolicy
import dev.patrickgold.florisboard.repli.voice.MicrophonePermissionActivity
import dev.patrickgold.florisboard.repli.voice.VoiceDeadline
import dev.patrickgold.florisboard.repli.voice.VoiceFailure
import dev.patrickgold.florisboard.repli.voice.VoiceGuidanceDependencies
import dev.patrickgold.florisboard.repli.voice.VoiceGuidanceRecorder
import dev.patrickgold.florisboard.repli.voice.VoiceGuidanceText
import dev.patrickgold.florisboard.repli.voice.VoicePhase
import dev.patrickgold.florisboard.repli.voice.VoiceRecordingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The "tell Repli what you want to say" draft: typed inline through the keyboard, or dictated
 * with the on-device recogniser. Owns the draft text and the voice recorder; the orchestrator
 * decides when a draft opens and what happens once it is applied.
 */
internal class ReplyGuidanceController(private val host: ReplyFlowHost) {
    var draftId: String? = null
        private set
    var draftText: String = ""
        private set
    private var reviewId: String? = null
    private var forMore = false

    private var voiceRecorder: VoiceGuidanceRecorder? = null
    private var voiceDraftId: String? = null
    private var voiceReturnExpiry: Job? = null
    private var voiceLastTranscript: String? = null
    private var voiceAvailable: Boolean? = null
    var voiceBase: String = ""
        private set
    var voiceAccepted: String? = null
        private set
    var voiceAcceptedRev: Int = 0
        private set

    val voiceState: VoiceRecordingState?
        get() = voiceRecorder?.let { if (voiceDraftId != null) it.state else null }

    val voiceShowSettings: Boolean
        get() {
            val state = voiceState ?: return false
            return voiceAvailable == false || state.failure in
                setOf(VoiceFailure.MODEL_MISSING, VoiceFailure.LANGUAGE, VoiceFailure.SERVICE, VoiceFailure.AUDIO)
        }

    val voicePreview: String
        get() = voiceState?.partial?.let { VoiceGuidanceText.combine(voiceBase, it) } ?: ""

    fun isEditingInline(): Boolean = draftId != null &&
        ReplyCaptureSession.state.value?.phase == ReplyPhase.DRAFT

    val isOpen: Boolean
        get() {
            val id = draftId ?: return false
            val session = ReplyCaptureSession.state.value
            return session?.id == id && session.phase == ReplyPhase.DRAFT
        }

    // Text draft

    fun open(target: ReplyEditor, fromReview: Boolean, forMore: Boolean) {
        closeVoiceRecorder()
        val draft = ReplyCaptureSession.editGuidance(target)
        draftId = draft.id
        reviewId = draft.id.takeIf { fromReview }
        this.forMore = forMore
        draftText = draft.instructions.orEmpty()
    }

    fun type(text: String) {
        if (!isEditingInline()) return
        val next = draftText + text
        if (next.length <= RemoteReplyPrivacyPolicy.MAX_INSTRUCTION_CHARACTERS) {
            draftText = next
            host.publish()
        }
    }

    fun delete() {
        if (!isEditingInline() || draftText.isEmpty()) return
        draftText = draftText.dropLast(1)
        host.publish()
    }

    fun clearText() {
        if (!isEditingInline()) return
        draftText = ""
        host.publish()
    }

    /** The applied draft's session id and whether it was opened to ask for more replies. */
    data class Applied(val sessionId: String, val forMore: Boolean)

    fun apply(text: String): Applied? {
        val id = draftId ?: return null
        val more = forMore
        try {
            ReplyCaptureSession.finishGuidance(id, text, reviewBeforeGenerate = reviewId == id, returnToReplies = more)
        } catch (_: IllegalArgumentException) {
            host.setStatus(host.appContext.getString(R.string.repli_reply__guidance_too_long))
            return null
        }
        resetDraft()
        closeVoiceRecorder()
        return Applied(id, more)
    }

    fun cancel() {
        val id = draftId
        val state = ReplyCaptureSession.state.value
        if (id != null && state != null && state.id == id) {
            // Closing keeps the previously applied direction; typed text is discarded.
            ReplyCaptureSession.finishGuidance(id, state.instructions,
                reviewBeforeGenerate = reviewId == id, returnToReplies = forMore)
        }
        resetDraft()
        closeVoiceRecorder()
    }

    /** Drops drafts and recordings that no longer belong to the current session phase. */
    fun onSessionState(state: ReplyCaptureState) {
        if (draftId != null && (state.id != draftId || state.phase != ReplyPhase.DRAFT)) {
            draftId = null
        }
        if (voiceDraftId != null && (state.id != voiceDraftId || state.phase != ReplyPhase.DRAFT)) {
            // Recording only survives inside its own DRAFT; permission round trips
            // are handled by MICROPHONE_RETURNING.
            if (state.phase != ReplyPhase.MICROPHONE_PERMISSION &&
                state.phase != ReplyPhase.MICROPHONE_RETURNING
            ) {
                closeVoiceRecorder()
            }
        }
    }

    fun reset() {
        resetDraft()
        voiceReturnExpiry?.cancel()
        closeVoiceRecorder()
    }

    private fun resetDraft() {
        draftId = null
        reviewId = null
        draftText = ""
        forMore = false
    }

    // Voice (on-device transcription into the draft)

    fun startVoice(fieldText: String) {
        val target = host.editor ?: return
        val state = ReplyCaptureSession.state.value ?: return
        if (state.phase != ReplyPhase.DRAFT || state.id != draftId || host.sensitive || state.editor != target) return
        if (voiceRecorder?.busy == true) return
        closeVoiceRecorder()
        val base = fieldText.take(RemoteReplyPrivacyPolicy.MAX_INSTRUCTION_CHARACTERS)
        voiceBase = base
        voiceDraftId = state.id
        voiceAvailable = runCatching { VoiceGuidanceDependencies.factory(host.appContext).available() }.getOrDefault(false)
        val recorder = makeVoiceRecorder(state.id)
        voiceRecorder = recorder
        if (isMicGranted()) {
            recorder.start(true)
        } else {
            if (!ReplyCaptureSession.beginMicrophonePermission(state.id, base)) {
                host.setStatus(host.appContext.getString(R.string.repli_reply__guidance_too_long))
                closeVoiceRecorder()
                return
            }
            draftId = null
            host.publish()
            voiceReturnExpiry?.cancel()
            voiceReturnExpiry = host.scope.launch {
                delay(MICROPHONE_PERMISSION_TIMEOUT_MS)
                val current = ReplyCaptureSession.state.value
                if (current?.id == state.id && current.phase == ReplyPhase.MICROPHONE_PERMISSION) host.clearFlow()
            }
            try {
                host.launchActivity(
                    Intent(host.appContext, MicrophonePermissionActivity::class.java)
                        .putExtra(MicrophonePermissionActivity.EXTRA_REQUEST_ID, state.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
                ReplyCaptureSession.returnFromMicrophonePermission(state.id, false)
            }
        }
    }

    fun pauseVoice() {
        voiceRecorder?.pause()
    }

    fun resumeVoice(fieldText: String) {
        val id = voiceDraftId ?: return
        if (ReplyCaptureSession.state.value?.id != id) return
        voiceBase = fieldText.take(RemoteReplyPrivacyPolicy.MAX_INSTRUCTION_CHARACTERS)
        voiceRecorder?.start(true)
    }

    fun stopVoice() {
        voiceRecorder?.stop()
    }

    fun discardVoiceSegment() {
        // Drops only the provisional preview; accepted text stays in the field.
        voiceRecorder?.cancel()
    }

    fun openVoiceSettings() {
        try {
            host.launchActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            host.setStatus(host.appContext.getString(R.string.repli_voice__settings_unavailable))
        }
    }

    /** The permission activity handed control back: restore the draft and resume recording if asked. */
    fun onMicrophoneReturning() {
        voiceReturnExpiry?.cancel()
        voiceReturnExpiry = host.scope.launch {
            delay(MICROPHONE_RETURN_SETTLE_MS)
            val current = ReplyCaptureSession.state.value ?: return@launch
            val target = host.editor ?: return@launch
            val resumed = ReplyCaptureSession.resumeMicrophonePermission(current.id, target)
            if (resumed == null) {
                host.clearFlow()
                return@launch
            }
            draftId = current.id
            draftText = resumed.draft
            voiceBase = resumed.draft
            host.showTyping()
            if (resumed.startRecording && isMicGranted()) {
                closeVoiceRecorder()
                voiceDraftId = current.id
                val recorder = makeVoiceRecorder(current.id)
                voiceRecorder = recorder
                recorder.start(true)
            } else if (!isMicGranted()) {
                host.setStatus(host.appContext.getString(R.string.repli_voice__permission), activate = true)
            }
            host.publish()
        }
    }

    fun voiceStatus(): String? {
        val state = voiceState ?: return null
        val res = host.appContext.resources
        return when (state.phase) {
            VoicePhase.PREPARING -> res.getString(R.string.repli_voice__preparing)
            VoicePhase.LISTENING -> res.getString(R.string.repli_voice__listening)
            VoicePhase.PAUSING -> res.getString(R.string.repli_voice__pausing)
            VoicePhase.PAUSED -> res.getString(R.string.repli_voice__paused)
            VoicePhase.PROCESSING -> res.getString(R.string.repli_voice__processing)
            VoicePhase.ERROR -> when (state.failure) {
                VoiceFailure.NO_SPEECH -> res.getString(R.string.repli_voice__no_speech)
                VoiceFailure.TOO_LONG -> res.getString(R.string.repli_voice__too_long)
                VoiceFailure.UNAVAILABLE -> res.getString(R.string.repli_voice__unavailable)
                VoiceFailure.PERMISSION -> res.getString(R.string.repli_voice__permission)
                VoiceFailure.TIMEOUT -> res.getString(R.string.repli_voice__timeout)
                else -> res.getString(R.string.repli_voice__failed)
            }
            else -> null
        }
    }

    private fun makeVoiceRecorder(draftId: String): VoiceGuidanceRecorder {
        val factory = VoiceGuidanceDependencies.factory(host.appContext)
        return VoiceGuidanceRecorder(
            factory = factory,
            schedule = { ms, action ->
                val job = host.scope.launch {
                    delay(ms)
                    if (voiceDraftId == draftId) action()
                }
                VoiceDeadline { job.cancel() }
            },
            changed = { vs -> onVoiceChanged(draftId, vs) },
        )
    }

    private fun onVoiceChanged(draftId: String, vs: VoiceRecordingState) {
        if (voiceDraftId != draftId) return
        if (vs.phase == VoicePhase.REVIEW || vs.phase == VoicePhase.PAUSED) {
            val transcript = vs.transcript
            if (transcript != null && transcript != voiceLastTranscript) {
                voiceLastTranscript = transcript
                val accepted = VoiceGuidanceText.combine(voiceBase, transcript)
                if (accepted == null) {
                    host.setStatus(host.appContext.getString(R.string.repli_voice__too_long))
                } else {
                    voiceAcceptedRev += 1
                    voiceAccepted = accepted
                    draftText = accepted
                    voiceBase = accepted
                }
            }
        }
        host.publish()
    }

    private fun closeVoiceRecorder() {
        voiceRecorder?.close()
        voiceRecorder = null
        voiceDraftId = null
        voiceReturnExpiry?.cancel()
        voiceBase = ""
        voiceAccepted = null
        voiceLastTranscript = null
    }

    private fun isMicGranted(): Boolean =
        host.appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MICROPHONE_PERMISSION_TIMEOUT_MS = 30_000L
        const val MICROPHONE_RETURN_SETTLE_MS = 150L
    }
}
