package dev.patrickgold.florisboard.repli.ime

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.repli.account.RepliAccountSessionRepository
import dev.patrickgold.florisboard.repli.account.RepliFirebaseAccountManager
import dev.patrickgold.florisboard.repli.capture.CaptureViewport
import dev.patrickgold.florisboard.repli.capture.ConversationTurn
import dev.patrickgold.florisboard.repli.capture.PendingVisionCaptureStore
import dev.patrickgold.florisboard.repli.capture.ReplyCaptureSession
import dev.patrickgold.florisboard.repli.capture.ReplyCaptureState
import dev.patrickgold.florisboard.repli.capture.ReplyConversation
import dev.patrickgold.florisboard.repli.capture.ReplyEditor
import dev.patrickgold.florisboard.repli.capture.ReplyManualCapturePolicy
import dev.patrickgold.florisboard.repli.capture.ReplyPhase
import dev.patrickgold.florisboard.repli.capture.ReplyCaptureConsentActivity
import dev.patrickgold.florisboard.repli.capture.ReviewEvidenceStore
import dev.patrickgold.florisboard.repli.data.LearnedStyleRepository
import dev.patrickgold.florisboard.repli.data.ProfileRepository
import dev.patrickgold.florisboard.repli.data.RecentMessageRepository
import dev.patrickgold.florisboard.repli.data.RemoteGenerationPreferences
import dev.patrickgold.florisboard.repli.diagnostics.CaptureTelemetry
import android.os.SystemClock
import dev.patrickgold.florisboard.repli.identity.ConfirmedConversationIdentity
import dev.patrickgold.florisboard.repli.identity.ConversationIdentityResolution
import dev.patrickgold.florisboard.repli.identity.ConversationIdentityResolver
import dev.patrickgold.florisboard.repli.identity.SavedChatAutoSuggestion
import dev.patrickgold.florisboard.repli.identity.SavedChatAutoSuggestionPolicy
import dev.patrickgold.florisboard.repli.identity.CapturedContactProfileMatcher
import dev.patrickgold.florisboard.repli.identity.CapturedContactName
import dev.patrickgold.florisboard.repli.profile.ProfileMatcher
import dev.patrickgold.florisboard.repli.profile.RecentMessage
import dev.patrickgold.florisboard.repli.profile.VoiceProfile
import dev.patrickgold.florisboard.repli.profile.VoiceStyle
import dev.patrickgold.florisboard.repli.persona.BuiltInPersonas
import dev.patrickgold.florisboard.repli.persona.Persona
import dev.patrickgold.florisboard.repli.persona.PersonaRepository
import dev.patrickgold.florisboard.repli.suggestions.PreparedRemoteReplyRequest
import dev.patrickgold.florisboard.repli.suggestions.ReplyIntent
import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyPrivacyPolicy
import dev.patrickgold.florisboard.repli.suggestions.RepliAccountSessionProvider
import dev.patrickgold.florisboard.repli.suggestions.ServerMediatedContextEngine
import dev.patrickgold.florisboard.repli.suggestions.ServerMediatedReplyEngine
import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyException
import dev.patrickgold.florisboard.repli.suggestions.CloudReplyFailure
import dev.patrickgold.florisboard.repli.review.FullScreenContextReviewActivity
import dev.patrickgold.florisboard.repli.review.FullScreenContextReviewSession
import dev.patrickgold.florisboard.repli.voice.MicrophonePermissionActivity
import dev.patrickgold.florisboard.repli.voice.VoiceDeadline
import dev.patrickgold.florisboard.repli.voice.VoiceFailure
import dev.patrickgold.florisboard.repli.voice.VoiceGuidanceDependencies
import dev.patrickgold.florisboard.repli.voice.VoiceGuidanceRecorder
import dev.patrickgold.florisboard.repli.voice.VoiceGuidanceText
import dev.patrickgold.florisboard.repli.voice.VoicePhase
import dev.patrickgold.florisboard.repli.voice.VoiceRecordingState
import android.Manifest
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class RepliConfirmChip(val label: String, val description: String)

data class RepliApprovalCard(
    val turnCount: Int,
    val instructions: String?,
    val stylePreset: String,
    val exampleCount: Int,
)

data class ChatOption(val id: String, val name: String, val styleName: String)
data class PersonaOption(val id: String, val name: String, val description: String)
data class RepliQuickReplyPrompt(val chatName: String, val personaName: String, val message: String)

data class RepliReplyUiState(
    val active: Boolean = false,
    val status: String = "",
    val generationError: String? = null,
    val busy: Boolean = false,
    val generating: Boolean = false,
    val reading: Boolean = false,
    val confirm: RepliConfirmChip? = null,
    val suggestions: List<String> = emptyList(),
    val explanation: String? = null,
    val canGenerateMore: Boolean = false,
    val reviewing: Boolean = false,
    val awaitingReview: Boolean = false,
    val reviewTurns: List<ConversationTurn> = emptyList(),
    val reviewFrames: Int = 0,
    val capturedChatName: String? = null,
    val captureWarning: String? = null,
    val canUndoReviewRemoval: Boolean = false,
    val contextTurns: List<ConversationTurn> = emptyList(),
    val instructions: String? = null,
    val replyIntent: ReplyIntent = ReplyIntent.REPLY,
    val approval: RepliApprovalCard? = null,
    val guidanceOpen: Boolean = false,
    val guidanceText: String = "",
    val showChatPicker: Boolean = false,
    val chatOptions: List<ChatOption> = emptyList(),
    val personaOptions: List<PersonaOption> = emptyList(),
    val selectedProfileName: String? = null,
    val selectedPersonaId: String = "casual",
    val selectedPersonaName: String = "Easy Breezy",
    val quickReplyPrompt: RepliQuickReplyPrompt? = null,
    val voice: VoiceRecordingState? = null,
    val voicePreview: String = "",
    val voiceAccepted: String? = null,
    val voiceAcceptedRev: Int = 0,
    val voiceBase: String = "",
    val voiceStatus: String? = null,
    val voiceShowSettings: Boolean = false,
)

/**
 * FlorisBoard-native reply orchestrator. Owns the ported Repli engines and session,
 * exposes UI-agnostic [StateFlow] state, and never touches Views directly.
 * Cloud generation starts when the user confirms the edited review context.
 */
class RepliReplyOrchestrator(
    private val appContext: Context,
    private val insertText: (String) -> Unit,
    private val hideKeyboard: () -> Unit,
    private val showKeyboard: () -> Unit,
    private val startActivity: (Intent) -> Unit,
    private val showTypingPanel: () -> Unit,
    private val showRepliesPanel: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(RepliReplyUiState())
    val uiState: StateFlow<RepliReplyUiState> = mutable.asStateFlow()

    private val remoteEngine = BuildConfig.REPLI_BACKEND_URL
        .takeIf(ServerMediatedReplyEngine::isConfigured)
        ?.let { ServerMediatedReplyEngine(it, RepliAccountSessionProvider) }
    private val contextEngine = BuildConfig.REPLI_BACKEND_URL
        .takeIf(ServerMediatedReplyEngine::isConfigured)
        ?.let { ServerMediatedContextEngine(it, RepliAccountSessionProvider) }

    private val telemetry = CaptureTelemetry(appContext)
    /** elapsedRealtime of the tap that started the current flow; null once replies are ready. */
    private var flowStartedAt: Long? = null
    private var flowIsQuick = false
    private var generation: Job? = null
    private var captureLaunch: Job? = null
    private var readingJob: Job? = null
    private var editor: ReplyEditor? = null
    private var sensitive = true
    private var profiles: List<VoiceProfile> = emptyList()
    private var personas: List<Persona> = BuiltInPersonas.all
    private var recentMessages: List<RecentMessage> = emptyList()
    private var resolution: ConversationIdentityResolution = ConversationIdentityResolution.None
    private var suggestion: ConversationIdentityResolution.Suggestion? = null
    private var selectedProfileId: String? = null
    private var defaultPersonaId = "casual"
    private var confirmedIdentity: ConfirmedConversationIdentity? = null
    private var contextLoadGeneration = 0
    private var preparedNotification: SavedChatAutoSuggestion? = null
    private var lastOfferedNotificationKey: String? = null
    private var pendingRemoteRequest: PreparedRemoteReplyRequest? = null
    private var lastApprovedRequest: PreparedRemoteReplyRequest? = null
    private var pendingMore = false
    private var moreRepliesExpanded = false
    private var guidanceDraftText = ""
    private var guidanceForMore = false
    private var reviewingTurns: MutableList<ConversationTurn>? = null
    private data class RemovedReviewTurn(val sessionId: String, val index: Int, val turn: ConversationTurn)
    private var removedReviewTurn: RemovedReviewTurn? = null
    private var capturedChatName: String? = null
    private var captureWarning: String? = null
    private var guidanceDraftId: String? = null
    private var guidanceReviewId: String? = null
    private var guidanceOverride: String? = null
    private var fsOriginal: ReplyEditor? = null
    private var voiceRecorder: VoiceGuidanceRecorder? = null
    private var voiceBase: String = ""
    private var voiceDraftId: String? = null
    private var voiceReturnExpiry: Job? = null
    private var voiceAcceptedRev = 0
    private var voiceAccepted: String? = null
    private var voiceLastTranscript: String? = null
    private var voiceAvailable: Boolean? = null

    init {
        scope.launch {
            ReplyCaptureSession.state.collect { state ->
                onSessionState(state)
            }
        }
    }

    // Editor tracking: called synchronously from the IME input-start handler.
    // Returns true when this start is the deliberate capture return that must
    // restore the reply panel; every other start invalidates prior context.

    fun onStartInput(info: FlorisEditorInfo, composerEmpty: Boolean): Boolean {
        val incoming = ReplyEditor(info.packageName ?: "", info.base.fieldId, info.base.fieldName)
        val nowSensitive = !RepliFieldPolicy.allows(info)
        val current = ReplyCaptureSession.state.value
        flogDebug { "RepliReply: onStartInput pkg=${incoming.packageName} field=${incoming.fieldId} sensitive=$nowSensitive session=${current?.id} phase=${current?.phase}" }
        if (current != null && FullScreenContextReviewSession.isActive(current.id) &&
            incoming.packageName == appContext.packageName && incoming != current.editor) {
            // Editing inside our full-screen review must not replace the host chat editor.
            return false
        }
        if (current != null && fsOriginal != null && FullScreenContextReviewSession.isReturning(current.id)) {
            val original = fsOriginal
            if (original != null && FullScreenContextReviewSession.acceptsEditor(current.id, original, incoming, appContext.packageName)) {
                ReplyCaptureSession.rebindEditorForReviewReturn(current.id, incoming)
                FullScreenContextReviewSession.markRestored(current.id)
                editor = incoming
                sensitive = nowSensitive
                fsOriginal = null
                return true
            }
            FullScreenContextReviewSession.end(current.id)
            fsOriginal = null
        }
        if (current != null && incoming == current.editor && !nowSensitive &&
            current.viewport != null && current.phase in setOf(ReplyPhase.RETURNING, ReplyPhase.READING)
        ) {
            editor = incoming
            sensitive = false
            scope.launch { delay(50); hideKeyboard() }
            return false
        }
        if (current != null && incoming == current.editor && !nowSensitive &&
            (current.awaitingKeyboardReturn || current.phase == ReplyPhase.MICROPHONE_PERMISSION ||
                current.phase == ReplyPhase.MICROPHONE_RETURNING)
        ) {
            val consumed = ReplyCaptureSession.consumeKeyboardReturn(incoming)
            flogDebug { "RepliReply: round-trip restore consumed=$consumed id=${current.id}" }
            editor = incoming
            sensitive = false
            return current.phase != ReplyPhase.READING
        }
        if (current != null) {
            flogDebug { "RepliReply: clearing session id=${current.id} phase=${current.phase}" }
            clear()
        }
        val changed = incoming != editor || nowSensitive != sensitive
        editor = incoming
        sensitive = nowSensitive
        selectedProfileId = null
        defaultPersonaId = "casual"
        confirmedIdentity = null
        preparedNotification = null
        if (nowSensitive) {
            profiles = emptyList()
            recentMessages = emptyList()
            resolution = ConversationIdentityResolution.None
            suggestion = null
            publish()
        } else if (changed) {
            loadContext(incoming.packageName, composerEmpty)
        }
        return false
    }

    private fun loadContext(packageName: String, composerEmpty: Boolean) {
        val generation = ++contextLoadGeneration
        scope.launch(Dispatchers.IO) {
            val loadedProfiles = ProfileRepository(appContext).profiles()
            val loadedPersonas = PersonaRepository(appContext).personas()
            val recent = RecentMessageRepository(appContext).recentFor(packageName)
            withContext(Dispatchers.Main) {
                if (generation != contextLoadGeneration) return@withContext
                profiles = loadedProfiles
                personas = loadedPersonas
                recentMessages = recent
                if (selectedProfileId != null && loadedProfiles.none { it.id == selectedProfileId }) {
                    selectedProfileId = null
                    confirmedIdentity = null
                }
                resolution = ConversationIdentityResolver.resolve(packageName, recent)
                suggestion = resolution as? ConversationIdentityResolution.Suggestion
                val candidate = SavedChatAutoSuggestionPolicy.select(
                    resolution, loadedProfiles, recent, composerEmpty,
                )
                if (candidate != null && candidate.key != lastOfferedNotificationKey &&
                    editor?.packageName == packageName && !sensitive &&
                    ReplyCaptureSession.state.value == null) {
                    preparedNotification = candidate
                    lastOfferedNotificationKey = candidate.key
                }
                publish()
            }
        }
    }

    // Entry points

    /** Called only by the user's Generate tap on the prepared notification prompt. */
    fun generateFromRecentMessage() {
        val offered = preparedNotification ?: return
        val target = editor ?: return
        if (sensitive || ReplyCaptureSession.state.value != null) return
        preparedNotification = null
        publish()
        scope.launch(Dispatchers.IO) {
            val latest = RecentMessageRepository(appContext).recentFor(target.packageName)
            val savedProfiles = ProfileRepository(appContext).profiles()
            val fresh = SavedChatAutoSuggestionPolicy.select(
                ConversationIdentityResolver.resolve(target.packageName, latest),
                savedProfiles, latest, composerEmpty = true,
            )
            withContext(Dispatchers.Main) {
                if (editor != target || sensitive || ReplyCaptureSession.state.value != null) return@withContext
                if (fresh?.key != offered.key) {
                    update { it.copy(active = true, status = "This message changed. Read the latest chat to continue.") }
                    return@withContext
                }
                profiles = savedProfiles
                selectedProfileId = fresh.profile.id
                suggestion = null
                confirmedIdentity = null
                telemetry.count(CaptureTelemetry.QUICK_REPLY)
                flowStartedAt = SystemClock.elapsedRealtime()
                flowIsQuick = true
                val session = ReplyCaptureSession.beginWithContext(
                    target, listOf(ConversationTurn(fresh.message.text, fromMe = false)),
                )
                generate(session)
            }
        }
    }

    fun dismissRecentMessagePrompt() {
        preparedNotification = null
        publish()
    }

    fun beginSuggestion() {
        val target = editor ?: return
        if (sensitive) {
            update { it.copy(active = true, status = "Assistance is off for this field") }
            return
        }
        val state = ReplyCaptureSession.state.value
        if (state != null && state.busy) return
        flogDebug { "RepliReply: beginSuggestion turns=${state?.turns?.size} seed=${trustedSeed() != null}" }
        if (state != null && state.turns.isNotEmpty() && state.editor == target) {
            beginCapture(append = false, singleView = true)
            return
        }
        val seed = trustedSeed()
        if (seed != null) {
            update { it.copy(active = true) }
            ReplyCaptureSession.beginWithContext(target, listOf(ConversationTurn(seed, fromMe = false)))
        } else {
            beginCapture(append = false, singleView = true)
        }
    }

    fun confirmSuggestedSender() {
        val target = editor ?: return
        if (sensitive) return
        val current = suggestion ?: return
        if (ReplyCaptureSession.state.value?.busy == true) return
        // Stale-tap guard: re-resolve before trusting anything.
        val fresh = ConversationIdentityResolver.resolve(target.packageName, recentMessages)
        if (fresh != current) {
            resolution = fresh
            suggestion = fresh as? ConversationIdentityResolution.Suggestion
            publish()
            return
        }
        scope.launch(Dispatchers.IO) {
            val repository = ProfileRepository(appContext)
            val existing = repository.profiles().firstOrNull { ProfileMatcher.matches(current.message.sender, it) }
            val profile = existing ?: repository.add(current.message.sender, "Added from confirmed chat", VoiceStyle.CASUAL)
            val confirmed = ConversationIdentityResolver.confirm(current, profile)
            withContext(Dispatchers.Main) {
                if (confirmed == null) return@withContext
                profiles = repository.profiles()
                selectedProfileId = profile.id
                confirmedIdentity = confirmed
                update { it.copy(active = true) }
                ReplyCaptureSession.beginWithContext(target, listOf(ConversationTurn(current.message.text, fromMe = false)))
            }
        }
    }

    fun insertSuggestion(text: String) {
        flogDebug { "RepliReply: insertSuggestion len=${text.length}" }
        telemetry.count(CaptureTelemetry.REPLY_INSERTED)
        insertText(text)
        // The reply is only a draft. Return to normal typing so it can be edited
        // in the host chat composer before the user decides to send it.
        clear()
    }

    fun openMoreReplies() {
        val state = ReplyCaptureSession.state.value ?: return
        if (state.replies.isEmpty()) return
        moreRepliesExpanded = true
        showRepliesPanel()
        publish()
        // The strip's More suggestions tap is itself a request for another cloud batch.
        if (state.phase == ReplyPhase.READY && !state.busy && state.replies.size <= 3) {
            requestMoreSuggestions()
        }
    }

    fun backToKeyboard() {
        moreRepliesExpanded = false
        showTypingPanel()
        publish()
    }

    fun requestMoreSuggestions() {
        val state = ReplyCaptureSession.state.value ?: return
        if (state.phase != ReplyPhase.READY || state.replies.isEmpty() || state.busy) return
        generate(state, approved = lastApprovedRequest, more = true)
    }

    fun beginCapture(append: Boolean, singleView: Boolean = false) {
        val target = editor ?: return
        if (sensitive) return
        if (append && (ReplyCaptureSession.state.value?.frames ?: 0) >= ReplyManualCapturePolicy.MAX_FRAMES) {
            update { it.copy(status = "All four views are captured. Review this context before generating.") }
            return
        }
        if (append) {
            val current = ReplyCaptureSession.state.value
            val edited = reviewingTurns
            if (current?.phase == ReplyPhase.REVIEW && edited != null) {
                ReplyCaptureSession.update(current.id) { it.copy(turns = edited.toList()) }
            }
        }
        generation?.cancel()
        captureLaunch?.cancel()
        readingJob?.cancel()
        closeVoiceRecorder()
        pendingRemoteRequest = null
        lastApprovedRequest = null
        reviewingTurns = null
        removedReviewTurn = null
        if (!append) {
            capturedChatName = null
            captureWarning = null
        }
        guidanceDraftId = null
        guidanceOverride = null
        val metrics: DisplayMetrics = appContext.resources.displayMetrics
        // Full display size (not the app window): must match the capture service,
        // which sizes its virtual display from maximumWindowMetrics.
        val windowManager = appContext.getSystemService(WindowManager::class.java)
        val displayWidth: Int
        val displayHeight: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            displayWidth = bounds.width()
            displayHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val real = DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
            displayWidth = real.widthPixels
            displayHeight = real.heightPixels
        }
        val viewport = CaptureViewport(
            width = displayWidth,
            height = displayHeight,
            contentBottom = (displayHeight - (80 * metrics.density).toInt())
                .coerceAtLeast(displayHeight / 2),
            readyAt = 0L,
        )
        val captureDisabled = runCatching {
            appContext.getSystemService(DevicePolicyManager::class.java)
                ?.getScreenCaptureDisabled(null) == true
        }.getOrDefault(false)
        if (captureDisabled) {
            update { it.copy(active = true, status = "Screen capture is disabled for this phone profile by its administrator.") }
            return
        }
        val begun = ReplyCaptureSession.begin(target, append, viewport, singleView)
        telemetry.count(CaptureTelemetry.CAPTURE_STARTED)
        if (!append) {
            flowStartedAt = SystemClock.elapsedRealtime()
            flowIsQuick = false
        }
        update { it.copy(active = true) }
        try {
            // Start while the IME is still visible. Hiding first can remove Android's
            // background-activity-launch allowance before the consent host is started.
            startActivity(
                Intent(appContext, ReplyCaptureConsentActivity::class.java)
                    .putExtra(ReplyCaptureConsentActivity.EXTRA_REQUEST_ID, begun.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            hideKeyboard()
        } catch (error: Exception) {
            flogError { "RepliCapture: consent activity launch failed: ${error::class.java.simpleName}" }
            telemetry.count(CaptureTelemetry.CAPTURE_CONSENT_FAILED)
            ReplyCaptureSession.fail(begun.id, "Couldn't open screen sharing here. Tap the chat field and try again.")
            return
        }
        captureLaunch = scope.launch {
            delay(CONSENT_ACTIVITY_OPEN_TIMEOUT_MS)
            val pending = ReplyCaptureSession.state.value
            if (pending?.id == begun.id && pending.phase == ReplyPhase.CONSENT &&
                !pending.consentActivityOpened) {
                telemetry.count(CaptureTelemetry.CAPTURE_CONSENT_FAILED)
                ReplyCaptureSession.fail(begun.id,
                    "Android didn't open screen sharing in this phone profile. Tap the chat field to retry.")
            }
        }
    }

    fun clear() {
        preparedNotification = null
        generation?.cancel()
        captureLaunch?.cancel()
        readingJob?.cancel()
        voiceReturnExpiry?.cancel()
        closeVoiceRecorder()
        pendingRemoteRequest = null
        lastApprovedRequest = null
        pendingMore = false
        moreRepliesExpanded = false
        reviewingTurns = null
        removedReviewTurn = null
        capturedChatName = null
        captureWarning = null
        guidanceDraftId = null
        guidanceReviewId = null
        guidanceDraftText = ""
        guidanceForMore = false
        guidanceOverride = null
        fsOriginal = null
        ReplyCaptureSession.state.value?.id?.let { FullScreenContextReviewSession.end(it) }
        ReplyCaptureSession.clear()
        mutable.value = RepliReplyUiState()
    }

    // Manual chat picker

    fun openChatPicker() {
        if (ReplyCaptureSession.state.value?.busy == true) return
        update { it.copy(active = true, showChatPicker = true) }
        showRepliesPanel()
    }

    fun closeChatPicker() {
        update { it.copy(showChatPicker = false) }
        publish()
    }

    fun selectChat(id: String?) {
        if (ReplyCaptureSession.state.value?.busy == true) return
        if (id != null && profiles.none { it.id == id }) return
        if (id == selectedProfileId) {
            publish()
            return
        }
        selectedProfileId = id
        confirmedIdentity = null
        invalidateRepliesForPersonaChange()
        publish()
    }

    fun selectPersona(id: String) {
        if (ReplyCaptureSession.state.value?.busy == true) return
        val persona = personas.firstOrNull { it.id == id } ?: return
        val profile = selectedProfile()
        if (profile == null) {
            if (defaultPersonaId == id) return
            defaultPersonaId = id
        } else {
            if (profile.personaId == id) return
            val repository = ProfileRepository(appContext)
            if (repository.updatePersona(profile.id, persona) == null) return
            profiles = repository.profiles()
        }
        invalidateRepliesForPersonaChange()
        publish()
    }

    private fun invalidateRepliesForPersonaChange() {
        lastApprovedRequest = null
        pendingRemoteRequest = null
        pendingMore = false
        moreRepliesExpanded = false
        val current = ReplyCaptureSession.state.value ?: return
        if ((current.turns.isEmpty() && current.replyIntent != ReplyIntent.FRESH_START) || current.busy ||
            current.phase !in setOf(ReplyPhase.APPROVAL, ReplyPhase.READY)) return
        reviewingTurns = current.turns.toMutableList()
        ReplyCaptureSession.update(current.id) {
            it.copy(phase = ReplyPhase.REVIEW, replies = emptyList(), generationError = null,
                message = "Persona changed · review before generating")
        }
    }

    // Review

    fun openReview() {
        val state = ReplyCaptureSession.state.value ?: return
        if ((state.turns.isEmpty() && state.replyIntent != ReplyIntent.FRESH_START) || state.busy) return
        if (state.phase == ReplyPhase.CAPTURE_REVIEW) return
        reviewingTurns = state.turns.toMutableList()
        removedReviewTurn = null
        publish()
    }

    fun closeReview() {
        if (ReplyCaptureSession.state.value?.phase == ReplyPhase.REVIEW) return
        reviewingTurns = null
        removedReviewTurn = null
        publish()
    }

    fun removeReviewTurn(index: Int) {
        val turns = reviewingTurns ?: return
        if (index !in turns.indices) return
        val updated = turns.toMutableList()
        val removed = updated.removeAt(index)
        telemetry.count(CaptureTelemetry.TURNS_REMOVED)
        removedReviewTurn = ReplyCaptureSession.state.value?.id?.let { RemovedReviewTurn(it, index, removed) }
        saveReviewTurns(updated)
    }

    fun undoReviewRemoval() {
        val removed = removedReviewTurn ?: return
        val state = ReplyCaptureSession.state.value ?: return
        val turns = reviewingTurns ?: return
        if (state.id != removed.sessionId) return
        val updated = turns.toMutableList()
        updated.add(removed.index.coerceIn(0, updated.size), removed.turn)
        removedReviewTurn = null
        saveReviewTurns(updated)
    }

    private fun saveReviewTurns(turns: MutableList<ConversationTurn>) {
        reviewingTurns = turns
        val state = ReplyCaptureSession.state.value
        if (state?.phase == ReplyPhase.REVIEW) {
            ReplyCaptureSession.update(state.id) { it.copy(turns = turns.toList()) }
        }
        publish()
    }

    fun setReplyIntent(intent: ReplyIntent) {
        val state = ReplyCaptureSession.state.value ?: return
        if (state.busy || reviewingTurns == null) return
        pendingRemoteRequest = null
        lastApprovedRequest = null
        ReplyCaptureSession.update(state.id) { it.copy(replyIntent = intent, generationError = null) }
        publish()
    }

    fun useReviewedContext() {
        val state = ReplyCaptureSession.state.value ?: return
        val edited = reviewingTurns ?: return
        if (edited.isEmpty() && state.replyIntent != ReplyIntent.FRESH_START) return
        reviewingTurns = null
        removedReviewTurn = null
        pendingRemoteRequest = null
        lastApprovedRequest = null
        ReplyCaptureSession.update(state.id) {
            it.copy(turns = edited.toList(), phase = ReplyPhase.CONTEXT, replies = emptyList(), generationError = null)
        }
    }

    // Guidance (text only; voice mic wiring follows with the IME voice work)

    fun openGuidance() {
        val target = editor ?: return
        if (sensitive) return
        val current = ReplyCaptureSession.state.value
        if (current?.busy == true) return
        val fromReview = current?.phase == ReplyPhase.REVIEW
        guidanceForMore = current?.phase == ReplyPhase.READY && current.replies.isNotEmpty()
        val editedReview = reviewingTurns?.toList()
        if (fromReview && editedReview?.isNotEmpty() == true) {
            ReplyCaptureSession.update(current.id) { it.copy(turns = editedReview) }
        }
        generation?.cancel()
        pendingRemoteRequest = null
        closeVoiceRecorder()
        val draft = ReplyCaptureSession.editGuidance(target)
        guidanceDraftId = draft.id
        guidanceReviewId = draft.id.takeIf { fromReview }
        guidanceOverride = null
        guidanceDraftText = draft.instructions.orEmpty()
        reviewingTurns = null
        update { it.copy(active = true) }
        showTypingPanel()
    }

    fun isEditingInlineGuidance(): Boolean = guidanceDraftId != null &&
        ReplyCaptureSession.state.value?.phase == ReplyPhase.DRAFT

    fun typeGuidance(text: String) {
        if (!isEditingInlineGuidance()) return
        val next = guidanceDraftText + text
        if (next.length <= RemoteReplyPrivacyPolicy.MAX_INSTRUCTION_CHARACTERS) {
            guidanceDraftText = next
            publish()
        }
    }

    fun deleteGuidance() {
        if (!isEditingInlineGuidance() || guidanceDraftText.isEmpty()) return
        guidanceDraftText = guidanceDraftText.dropLast(1)
        publish()
    }

    fun clearGuidanceText() {
        if (!isEditingInlineGuidance()) return
        guidanceDraftText = ""
        publish()
    }

    fun applyInlineGuidance() = applyGuidance(guidanceDraftText)

    fun applyGuidance(text: String) {
        val id = guidanceDraftId ?: return
        val forMore = guidanceForMore
        try {
            ReplyCaptureSession.finishGuidance(id, text,
                reviewBeforeGenerate = guidanceReviewId == id, returnToReplies = forMore)
        } catch (_: IllegalArgumentException) {
            update { it.copy(status = "What you want to say is too long (500 characters max)") }
            return
        }
        guidanceDraftId = null
        guidanceReviewId = null
        guidanceDraftText = ""
        guidanceForMore = false
        guidanceOverride = null
        closeVoiceRecorder()
        showRepliesPanel()
        if (forMore) {
            lastApprovedRequest = null
            ReplyCaptureSession.state.value?.takeIf { it.id == id }?.let { generate(it, more = true) }
        }
        publish()
    }

    fun cancelGuidance() {
        val id = guidanceDraftId
        val state = ReplyCaptureSession.state.value
        if (id != null && state != null && state.id == id) {
            // Closing keeps the previously applied direction; typed text is discarded.
            ReplyCaptureSession.finishGuidance(id, state.instructions,
                reviewBeforeGenerate = guidanceReviewId == id, returnToReplies = guidanceForMore)
        }
        guidanceDraftId = null
        guidanceReviewId = null
        guidanceDraftText = ""
        guidanceForMore = false
        guidanceOverride = null
        closeVoiceRecorder()
        showRepliesPanel()
        publish()
    }

    // Voice guidance (on-device transcription into the internal direction field)

    fun startVoice(fieldText: String) {
        val target = editor ?: return
        val state = ReplyCaptureSession.state.value ?: return
        if (state.phase != ReplyPhase.DRAFT || state.id != guidanceDraftId || sensitive || state.editor != target) return
        if (voiceRecorder?.busy == true) return
        closeVoiceRecorder()
        val base = fieldText.take(RemoteReplyPrivacyPolicy.MAX_INSTRUCTION_CHARACTERS)
        voiceBase = base
        voiceDraftId = state.id
        voiceAvailable = runCatching { VoiceGuidanceDependencies.factory(appContext).available() }.getOrDefault(false)
        val recorder = makeVoiceRecorder(state.id)
        voiceRecorder = recorder
        if (isMicGranted()) {
            recorder.start(true)
        } else {
            if (!ReplyCaptureSession.beginMicrophonePermission(state.id, base)) {
                update { it.copy(status = "What you want to say is too long (500 characters max)") }
                closeVoiceRecorder()
                return
            }
            guidanceDraftId = null
            guidanceOverride = null
            publish()
            voiceReturnExpiry?.cancel()
            voiceReturnExpiry = scope.launch {
                delay(MICROPHONE_PERMISSION_TIMEOUT_MS)
                val current = ReplyCaptureSession.state.value
                if (current?.id == state.id && current.phase == ReplyPhase.MICROPHONE_PERMISSION) clear()
            }
            try {
                startActivity(
                    Intent(appContext, MicrophonePermissionActivity::class.java)
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

    fun consumeVoiceAccepted() {
        update { it.copy(voiceAccepted = null) }
    }

    fun openVoiceSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            update { it.copy(status = appContext.getString(R.string.repli_voice__settings_unavailable)) }
        }
    }

    private fun makeVoiceRecorder(draftId: String): VoiceGuidanceRecorder {
        val factory = VoiceGuidanceDependencies.factory(appContext)
        return VoiceGuidanceRecorder(
            factory = factory,
            schedule = { ms, action ->
                val job = scope.launch {
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
                    update { it.copy(status = appContext.getString(R.string.repli_voice__too_long)) }
                } else {
                    voiceAcceptedRev += 1
                    voiceAccepted = accepted
                    guidanceDraftText = accepted
                    voiceBase = accepted
                }
            }
        }
        publish()
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
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // Approval

    fun approveGenerate() {
        val state = ReplyCaptureSession.state.value ?: return
        val displayed = pendingRemoteRequest ?: return
        if (state.phase != ReplyPhase.APPROVAL) return
        scope.launch(Dispatchers.IO) {
            val profile = selectedProfile()
            val snapshot = profile?.let { LearnedStyleRepository(appContext).get(it.id) }
            val current = ReplyCaptureSession.state.value ?: return@launch
            val persona = selectedPersona()
            val fresh = RemoteReplyPrivacyPolicy.prepare(
                current.turns, persona.baseStyle, snapshot, instructions = current.instructions,
                profileId = profile?.id, persona = persona, replyIntent = current.replyIntent,
            )
            if (fresh != displayed) return@launch
            withContext(Dispatchers.Main) {
                if (ReplyCaptureSession.state.value?.id != state.id) return@withContext
                generate(state, approved = displayed, more = pendingMore)
            }
        }
    }

    fun openFullScreenReview() {
        val target = editor ?: return
        val state = ReplyCaptureSession.state.value ?: return
        if (state.editor != target || state.phase !in setOf(ReplyPhase.REVIEW, ReplyPhase.APPROVAL)) return
        val reviewIntent = if (state.phase == ReplyPhase.REVIEW) {
            val turns = reviewingTurns?.toList() ?: state.turns
            if (turns.isEmpty()) return
            ReplyCaptureSession.update(state.id) { it.copy(turns = turns) }
            FullScreenContextReviewActivity.intent(
                appContext, state.id, turns, state.instructions,
                selectedPersona().name,
            )
        } else {
            val request = pendingRemoteRequest ?: return
            FullScreenContextReviewActivity.intent(appContext, state.id, request)
        }
        removedReviewTurn = null
        publish()
        fsOriginal = state.editor
        FullScreenContextReviewSession.begin(state.id)
        try {
            startActivity(reviewIntent)
        } catch (_: Exception) {
            FullScreenContextReviewSession.end(state.id)
            fsOriginal = null
            update { it.copy(status = "Couldn't open full-screen review") }
        }
    }

    // Session dispatch

    private fun onSessionState(state: ReplyCaptureState?) {
        flogDebug { "RepliReply: session id=${state?.id} phase=${state?.phase} turns=${state?.turns?.size} replies=${state?.replies?.size}" }
        if (state == null) {
            generation?.cancel()
            captureLaunch?.cancel()
            readingJob?.cancel()
            voiceReturnExpiry?.cancel()
            closeVoiceRecorder()
            pendingRemoteRequest = null
            reviewingTurns = null
            removedReviewTurn = null
            capturedChatName = null
            captureWarning = null
            guidanceDraftId = null
            guidanceReviewId = null
            guidanceOverride = null
            mutable.value = RepliReplyUiState()
            return
        }
        if (guidanceDraftId != null && (state.id != guidanceDraftId || state.phase != ReplyPhase.DRAFT)) {
            guidanceDraftId = null
        }
        if (voiceDraftId != null && (state.id != voiceDraftId || state.phase != ReplyPhase.DRAFT)) {
            // Recording only survives inside its own DRAFT; permission round trips
            // are handled by MICROPHONE_RETURNING below.
            if (state.phase != ReplyPhase.MICROPHONE_PERMISSION &&
                state.phase != ReplyPhase.MICROPHONE_RETURNING
            ) {
                closeVoiceRecorder()
            }
        }
        when (state.phase) {
            ReplyPhase.RETURNING -> {
                if (state.viewport != null) hideKeyboard() else if (state.awaitingKeyboardReturn) showKeyboard()
            }
            ReplyPhase.READING -> {
                if (state.viewport != null) {
                    hideKeyboard()
                } else {
                    if (state.awaitingKeyboardReturn) showKeyboard()
                    showTypingPanel()
                }
            }
            ReplyPhase.MICROPHONE_RETURNING -> resumeAfterMicrophonePermission(state)
            ReplyPhase.CAPTURE_REVIEW -> {
                showKeyboard()
                showTypingPanel()
                handleCaptureReview(state)
            }
            ReplyPhase.REVIEW -> {
                reviewingTurns = state.turns.toMutableList()
                if (state.awaitingKeyboardReturn) showKeyboard()
                showRepliesPanel()
            }
            ReplyPhase.CONTEXT -> {
                reviewingTurns = null
                if (state.awaitingKeyboardReturn) showKeyboard()
                generate(state)
            }
            ReplyPhase.GENERATING -> {
                if (moreRepliesExpanded) showRepliesPanel() else showTypingPanel()
            }
            ReplyPhase.ERROR -> {
                if (state.awaitingKeyboardReturn) showKeyboard()
                showRepliesPanel()
            }
            ReplyPhase.READY -> {
                if (moreRepliesExpanded) showRepliesPanel() else showTypingPanel()
            }
            else -> Unit
        }
        publish()
    }

    private fun resumeAfterMicrophonePermission(state: ReplyCaptureState) {
        voiceReturnExpiry?.cancel()
        voiceReturnExpiry = scope.launch {
            delay(MICROPHONE_RETURN_SETTLE_MS)
            val current = ReplyCaptureSession.state.value ?: return@launch
            val target = editor ?: return@launch
            val resumed = ReplyCaptureSession.resumeMicrophonePermission(current.id, target)
            if (resumed == null) {
                clear()
                return@launch
            }
            guidanceDraftId = current.id
            guidanceOverride = resumed.draft
            guidanceDraftText = resumed.draft
            voiceBase = resumed.draft
            showTypingPanel()
            if (resumed.startRecording && isMicGranted()) {
                closeVoiceRecorder()
                voiceDraftId = current.id
                val recorder = makeVoiceRecorder(current.id)
                voiceRecorder = recorder
                recorder.start(true)
            } else if (!isMicGranted()) {
                update { it.copy(active = true, status = appContext.getString(R.string.repli_voice__permission)) }
            }
            publish()
        }
    }

    private fun handleCaptureReview(state: ReplyCaptureState) {
        val pending = PendingVisionCaptureStore.peek(state.id)
        val canUseVision = pending != null && remoteEnabled() && contextEngine != null
        if (!canUseVision) {
            val taken = PendingVisionCaptureStore.take(state.id)
            taken?.eraseImages()
            telemetry.count(CaptureTelemetry.VISION_UNAVAILABLE)
            telemetry.count(CaptureTelemetry.FRAMES_CAPTURED, taken?.images?.size ?: 0)
            val turns = ReplyConversation.mergeCapture(
                state.turns, taken?.localTurns.orEmpty(),
            )
            if (turns.isEmpty() && state.turns.isEmpty()) {
                ReplyCaptureSession.fail(state.id, "This looks like a new or empty chat. Send the first message before capturing context.")
            } else {
                selectCapturedContact(taken?.contactName)
                ReplyCaptureSession.update(state.id) {
                    it.copy(phase = ReplyPhase.REVIEW, viewport = null, turns = turns, message = "On-device text · AI reading unavailable. Check messages before cloud generation.")
                }
            }
            return
        }
        readingJob?.cancel()
        readingJob = scope.launch(Dispatchers.IO) {
            val taken = PendingVisionCaptureStore.take(state.id) ?: return@launch
            ReplyCaptureSession.update(state.id) {
                it.copy(phase = ReplyPhase.READING, viewport = null, message = "AI is reading this chat image…")
            }
            try {
                ReviewEvidenceStore.put(state.id, taken.images)
                val ai = withTimeout(AI_READING_TIMEOUT_MS) { contextEngine.extract(taken.images) }
                if (ReplyCaptureSession.state.value?.id != state.id) {
                    ReviewEvidenceStore.discard(state.id)
                    return@launch
                }
                val captured = ReplyConversation.mergeVisualBubbles(ai.bubbles.orEmpty(), taken.images.size)
                    .ifEmpty { ai.turns }
                // AI visual bubbles are authoritative. Local OCR can introduce duplicate or
                // misread text; use it only when AI returned no usable bubbles.
                val usedLocalText = captured.isEmpty()
                val readTurns = captured.ifEmpty { taken.localTurns }
                val merged = ReplyConversation.mergeCapture(taken.baseTurns, readTurns)
                telemetry.count(if (usedLocalText) CaptureTelemetry.VISION_EMPTY else CaptureTelemetry.VISION_READ)
                telemetry.count(CaptureTelemetry.FRAMES_CAPTURED, taken.images.size)
                telemetry.count(CaptureTelemetry.TURNS_READ, readTurns.size)
                withContext(Dispatchers.Main) {
                    if (merged.isEmpty()) {
                        ReviewEvidenceStore.discard(state.id)
                        ReplyCaptureSession.fail(state.id, "This looks like a new or empty chat. Send the first message before capturing context.")
                    } else {
                        selectCapturedContact(taken.contactName, ai.contactName)
                        ReplyCaptureSession.update(state.id) {
                            it.copy(phase = ReplyPhase.REVIEW, turns = merged, message =
                                if (usedLocalText) "AI found no messages · review the on-device text"
                                else "AI read ${taken.images.size} captured view(s) · check speakers and text before cloud generation")
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                ReviewEvidenceStore.discard(state.id)
                throw cancellation
            } catch (_: Exception) {
                ReviewEvidenceStore.discard(state.id)
                telemetry.count(CaptureTelemetry.VISION_FAILED)
                telemetry.count(CaptureTelemetry.FRAMES_CAPTURED, taken.images.size)
                val fallback = ReplyConversation.mergeCapture(taken.baseTurns, taken.localTurns)
                withContext(Dispatchers.Main) {
                    if (fallback.isEmpty()) {
                        ReplyCaptureSession.fail(state.id, "AI reading was unavailable and on-device reading found no messages. Try a clearer capture.")
                    } else {
                        selectCapturedContact(taken.contactName)
                        ReplyCaptureSession.update(state.id) {
                            it.copy(phase = ReplyPhase.REVIEW, turns = fallback, message = "AI reading unavailable · review the on-device text")
                        }
                    }
                }
            } finally {
                taken.eraseImages()
            }
        }
    }

    private fun generate(state: ReplyCaptureState, approved: PreparedRemoteReplyRequest? = null, more: Boolean = false) {
        val unavailable = cloudUnavailableReason()
        if (unavailable != null) {
            ReplyCaptureSession.update(state.id) {
                it.copy(phase = if (more) ReplyPhase.READY else ReplyPhase.REVIEW,
                    message = unavailable, generationError = unavailable)
            }
            return
        }
        if (approved == null) prepareAndGenerate(state, more) else generateNow(state, approved, more)
    }

    private fun prepareAndGenerate(state: ReplyCaptureState, more: Boolean = false) {
        generation?.cancel()
        pendingMore = more
        ReplyCaptureSession.update(state.id) {
            it.copy(phase = ReplyPhase.GENERATING, replies = if (more) state.replies else emptyList(),
                message = "Preparing your Repli ideas…", generationError = null)
        }
        generation = scope.launch(Dispatchers.IO) {
            try {
                val profile = selectedProfile()
                val snapshot = profile?.let { LearnedStyleRepository(appContext).get(it.id) }
                val persona = selectedPersona()
                val prepared = RemoteReplyPrivacyPolicy.prepare(
                    state.turns, persona.baseStyle, snapshot, instructions = state.instructions,
                    profileId = profile?.id, persona = persona, replyIntent = state.replyIntent,
                )
                withContext(Dispatchers.Main) {
                    if (ReplyCaptureSession.state.value?.id != state.id) return@withContext
                    generation = null
                    generateNow(state, prepared, more)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (ReplyCaptureSession.state.value?.id == state.id) {
                        ReplyCaptureSession.update(state.id) {
                            val reason = "Couldn't prepare cloud context. Check the reviewed messages and retry."
                            it.copy(phase = if (more) ReplyPhase.READY else ReplyPhase.REVIEW,
                                message = reason, generationError = reason)
                        }
                    }
                }
            }
        }
    }

    private fun generateNow(state: ReplyCaptureState, approved: PreparedRemoteReplyRequest, more: Boolean) {
        val unavailable = cloudUnavailableReason()
        if (unavailable != null) {
            ReplyCaptureSession.update(state.id) {
                it.copy(phase = if (more) ReplyPhase.READY else ReplyPhase.REVIEW,
                    message = unavailable, generationError = unavailable)
            }
            return
        }
        val engine = remoteEngine ?: return
        generation?.cancel()
        ReviewEvidenceStore.discard(state.id)
        val keep = if (more) state.replies else emptyList()
        ReplyCaptureSession.update(state.id) {
            it.copy(phase = ReplyPhase.GENERATING, replies = keep,
                message = "Finding your Repli ideas…", generationError = null)
        }
        generation = scope.launch(Dispatchers.IO) {
            try {
                val batch = withTimeout(GENERATION_TIMEOUT_MS) { engine.suggestDetailed(approved) }
                withContext(Dispatchers.Main) {
                    if (ReplyCaptureSession.state.value?.id != state.id) return@withContext
                    pendingRemoteRequest = null
                    lastApprovedRequest = approved
                    pendingMore = false
                    val combined = (keep + batch.replies).distinct().take(9)
                    telemetry.count(CaptureTelemetry.GENERATION_OK)
                    if (!more) {
                        telemetry.recordReplyLatency(flowStartedAt, flowIsQuick)
                        flowStartedAt = null
                    }
                    val memoryTurns = batch.memoryTurns
                    ReplyCaptureSession.update(state.id) {
                        it.copy(phase = ReplyPhase.READY,
                            replies = combined,
                            message = if (more && combined.size == keep.size) {
                                "No new replies this time · tell Repli what to change or try again"
                            } else if (approved.profileId != null && batch.memorySaved != true) {
                                "Replies ready · couldn't save chat memory"
                            } else if (memoryTurns != null && memoryTurns > 0) {
                                "Tap to insert · based on $memoryTurns earlier message${if (memoryTurns == 1) "" else "s"}"
                            } else if (memoryTurns == 0 && state.frames == 0) {
                                "Tap to insert · first reply for this chat · read the chat once for more context"
                            } else "Tap to insert, then edit",
                            generationError = null)
                    }
                    flogDebug { "RepliReply: READY cloud replies=${batch.replies.size}" }
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                telemetry.count(CaptureTelemetry.GENERATION_FAILED)
                withContext(Dispatchers.Main) {
                    if (ReplyCaptureSession.state.value?.id == state.id) {
                        ReplyCaptureSession.update(state.id) {
                            val reason = "Cloud timed out. Check your connection and retry."
                            it.copy(phase = if (more) ReplyPhase.READY else ReplyPhase.REVIEW,
                                message = reason, generationError = reason)
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                flogError { "RepliReply: cloud generation failed: ${error.message}" }
                telemetry.count(CaptureTelemetry.GENERATION_FAILED)
                val status = when (error) {
                    is kotlinx.coroutines.TimeoutCancellationException, is SocketTimeoutException -> "Cloud timed out. Check your connection and retry."
                    is IOException -> "No internet connection. Connect and try again."
                    is RemoteReplyException -> error.reason.label + " · try again"
                    else -> CloudReplyFailure.UNAVAILABLE.label + " · try again"
                }
                withContext(Dispatchers.Main) {
                    if (ReplyCaptureSession.state.value?.id != state.id) return@withContext
                    ReplyCaptureSession.update(state.id) {
                        it.copy(phase = if (more) ReplyPhase.READY else ReplyPhase.REVIEW,
                            message = status, generationError = status)
                    }
                }
            }
        }
    }

    private fun cloudUnavailableReason(): String? {
        if (remoteEngine == null) return "Cloud replies aren't configured in this build."
        if (!RemoteGenerationPreferences(appContext).enabled) return "Enable cloud replies in Repli settings to generate."
        if (RepliAccountSessionRepository.bearerToken() == null &&
            !RepliFirebaseAccountManager.state.value.signedIn) return "Sign in to Repli to generate cloud replies."
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.activeNetwork?.let(manager::getNetworkCapabilities)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            return "No internet connection. Connect and try again."
        }
        return null
    }

    // Helpers

    private fun trustedSeed(): String? {
        val target = editor ?: return null
        return ConversationIdentityResolver.trustedIncomingText(
            confirmedIdentity, selectedProfileId, target.packageName, recentMessages,
        )
    }

    private fun selectedProfile(): VoiceProfile? =
        profiles.firstOrNull { it.id == selectedProfileId }

    private fun selectedPersona(): Persona {
        val id = selectedProfile()?.personaId ?: defaultPersonaId
        return PersonaRepository(appContext).get(id) ?: BuiltInPersonas.all.first()
    }

    private fun remoteEnabled(): Boolean =
        RemoteGenerationPreferences(appContext).enabled &&
            (RepliAccountSessionRepository.bearerToken() != null ||
                RepliFirebaseAccountManager.state.value.signedIn)

    private fun selectCapturedContact(localHeaderName: String?, aiName: String? = null) {
        val sessionId = ReplyCaptureSession.state.value?.id ?: return
        val newName = CapturedContactName.prepare(localHeaderName) ?: CapturedContactName.prepare(aiName)
        if (newName != null && capturedChatName != null &&
            !newName.equals(capturedChatName, ignoreCase = true)) {
            captureWarning = "Captured chat changed from $capturedChatName to $newName. Check the messages before generating."
        }
        capturedChatName = newName ?: capturedChatName
        publish()
        if (localHeaderName == null && aiName == null) return
        scope.launch(Dispatchers.IO) {
            val repository = ProfileRepository(appContext)
            val match = CapturedContactProfileMatcher.resolve(
                localHeaderName, aiName, repository.profiles(),
            ) ?: return@launch
            val profile = when (match) {
                is CapturedContactProfileMatcher.Result.Existing -> match.profile
                is CapturedContactProfileMatcher.Result.New ->
                    repository.add(match.name, "Added from AI capture", VoiceStyle.CASUAL)
            }
            withContext(Dispatchers.Main) {
                if (ReplyCaptureSession.state.value?.id != sessionId) return@withContext
                profiles = repository.profiles()
                selectedProfileId = profile.id
                confirmedIdentity = null
                resolution = ConversationIdentityResolution.None
                suggestion = null
                publish()
            }
        }
    }

    private fun update(transform: (RepliReplyUiState) -> RepliReplyUiState) {
        mutable.value = transform(mutable.value)
        publish()
    }

    private fun publish() {
        val session = ReplyCaptureSession.state.value
        val current = mutable.value
        val active = current.active || session != null
        val review = reviewingTurns
        val approval = pendingRemoteRequest?.takeIf { session?.phase == ReplyPhase.APPROVAL }?.let {
            RepliApprovalCard(
                turnCount = it.context.size,
                instructions = it.instructions,
                stylePreset = it.style.personaName ?: it.style.preset,
                exampleCount = it.style.examples.size + it.style.personaExamples.size,
            )
        }
        val confirm = suggestion?.takeIf { session == null || !session.busy }?.let {
            val candidate = it.message.sender.take(PROFILE_NAME_PREVIEW_LIMIT)
            val existing = profiles.firstOrNull { profile -> ProfileMatcher.matches(it.message.sender, profile) }
            if (existing == null) {
                RepliConfirmChip(
                    label = "Save $candidate?",
                    description = "Confirm this chat is with ${it.message.sender}, save its persona, and use its recent message",
                )
            } else {
                RepliConfirmChip(
                    label = "Use $candidate?",
                    description = "Confirm this chat is with ${it.message.sender} and use its recent message",
                )
            }
        }
        val draftId = guidanceDraftId
        val guidanceOpen = draftId != null && session?.id == draftId && session.phase == ReplyPhase.DRAFT
        val voiceState = voiceRecorder?.let { if (voiceDraftId != null) it.state else null }
        val reviewChatName = (capturedChatName ?: selectedProfile()?.name)?.let { name ->
            if (session?.editor?.packageName == appContext.packageName &&
                !name.endsWith("practice chat", ignoreCase = true)) "$name · practice chat" else name
        }
        mutable.value = current.copy(
            active = active,
            busy = session?.busy == true,
            generating = session?.phase == ReplyPhase.GENERATING,
            reading = session?.phase == ReplyPhase.READING && session.viewport == null,
            status = when {
                session == null && !current.active -> ""
                session == null -> current.status.ifBlank { defaultIdleStatus() }
                else -> session.message
            },
            generationError = session?.generationError,
            confirm = confirm,
            suggestions = session?.replies.orEmpty(),
            explanation = null,
            canGenerateMore = session?.phase == ReplyPhase.READY && session.replies.isNotEmpty(),
            reviewing = review != null,
            awaitingReview = session?.phase == ReplyPhase.REVIEW,
            reviewTurns = review.orEmpty(),
            reviewFrames = session?.frames ?: 0,
            capturedChatName = reviewChatName,
            captureWarning = captureWarning,
            canUndoReviewRemoval = removedReviewTurn?.sessionId == session?.id && review != null,
            contextTurns = session?.turns.orEmpty(),
            instructions = session?.instructions,
            replyIntent = session?.replyIntent ?: ReplyIntent.REPLY,
            approval = approval,
            guidanceOpen = guidanceOpen,
            guidanceText = if (guidanceOpen) guidanceDraftText else "",
            showChatPicker = current.showChatPicker,
            chatOptions = profiles.map { ChatOption(it.id, it.name,
                personas.firstOrNull { persona -> persona.id == it.personaId }?.name ?: "Easy Breezy") },
            personaOptions = personas.map { PersonaOption(it.id, it.name, it.description) },
            selectedProfileName = selectedProfile()?.name,
            selectedPersonaId = selectedProfile()?.personaId ?: defaultPersonaId,
            selectedPersonaName = personas.firstOrNull {
                it.id == (selectedProfile()?.personaId ?: defaultPersonaId)
            }?.name ?: "Easy Breezy",
            quickReplyPrompt = preparedNotification?.takeIf { session == null }?.let { candidate ->
                RepliQuickReplyPrompt(
                    chatName = candidate.profile.name,
                    personaName = personas.firstOrNull { it.id == candidate.profile.personaId }?.name
                        ?: "Easy Breezy",
                    message = candidate.message.text.take(160),
                )
            },
            voice = voiceState,
            voicePreview = voiceState?.partial?.let { VoiceGuidanceText.combine(voiceBase, it) } ?: "",
            voiceAccepted = voiceAccepted,
            voiceAcceptedRev = voiceAcceptedRev,
            voiceBase = voiceBase,
            voiceStatus = voiceStatusFor(voiceState),
            voiceShowSettings = voiceState != null &&
                (voiceAvailable == false || voiceState.failure in
                    setOf(VoiceFailure.MODEL_MISSING, VoiceFailure.LANGUAGE, VoiceFailure.SERVICE, VoiceFailure.AUDIO)),
        )
    }

    private fun defaultIdleStatus(): String = when (val r = resolution) {        is ConversationIdentityResolution.Suggestion -> "Recent message · confirm before use"
        is ConversationIdentityResolution.Ambiguous -> "Several recent chats · use screen capture"
        else -> if (selectedProfile() != null) "Capture this chat to suggest replies"
        else "Suggest replies · saving a chat is optional"
    }

    private fun voiceStatusFor(state: VoiceRecordingState?): String? {
        if (state == null) return null
        val res = appContext.resources
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

    private companion object {
        const val CONSENT_ACTIVITY_OPEN_TIMEOUT_MS = 8_000L
        const val AI_READING_TIMEOUT_MS = 50_000L
        const val GENERATION_TIMEOUT_MS = 25_000L
        const val MICROPHONE_PERMISSION_TIMEOUT_MS = 30_000L
        const val MICROPHONE_RETURN_SETTLE_MS = 150L
        const val PROFILE_NAME_PREVIEW_LIMIT = 14
    }
}
