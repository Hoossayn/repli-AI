package dev.patrickgold.florisboard.repli.ime

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.repli.account.RepliAccountSessionRepository
import dev.patrickgold.florisboard.repli.account.RepliFirebaseAccountManager
import dev.patrickgold.florisboard.repli.capture.ReplyCaptureSession
import dev.patrickgold.florisboard.repli.capture.ReplyCaptureState
import dev.patrickgold.florisboard.repli.capture.ReplyPhase
import dev.patrickgold.florisboard.repli.capture.ReviewEvidenceStore
import dev.patrickgold.florisboard.repli.data.LearnedStyleRepository
import dev.patrickgold.florisboard.repli.data.RemoteGenerationPreferences
import dev.patrickgold.florisboard.repli.diagnostics.CaptureTelemetry
import dev.patrickgold.florisboard.repli.suggestions.CloudReplyFailure
import dev.patrickgold.florisboard.repli.suggestions.PreparedRemoteReplyRequest
import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyException
import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyPrivacyPolicy
import dev.patrickgold.florisboard.repli.suggestions.RepliAccountSessionProvider
import dev.patrickgold.florisboard.repli.suggestions.ServerMediatedReplyEngine
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Cloud reply generation for the current capture session: prepares the bounded, approved
 * request from reviewed turns, persona and learned style, calls the first-party backend, and
 * writes the outcome back into [ReplyCaptureSession]. Nothing here touches the UI directly.
 */
internal class CloudReplyGenerator(private val host: ReplyFlowHost) {
    private val engine = BuildConfig.REPLI_BACKEND_URL
        .takeIf(ServerMediatedReplyEngine::isConfigured)
        ?.let { ServerMediatedReplyEngine(it, RepliAccountSessionProvider) }

    private var generation: Job? = null

    /** The exact request shown for approval; only non-null while a session is in APPROVAL. */
    var pendingRemoteRequest: PreparedRemoteReplyRequest? = null
        private set

    /** The last request the backend answered, reused verbatim for "more replies". */
    var lastApprovedRequest: PreparedRemoteReplyRequest? = null
        private set

    var pendingMore: Boolean = false
        private set

    val configured: Boolean get() = engine != null

    fun cancel() {
        generation?.cancel()
    }

    /** Forgets the displayed and answered requests (persona, chat or intent changed). */
    fun clearRequests() {
        pendingRemoteRequest = null
        lastApprovedRequest = null
    }

    fun dropPending() {
        pendingRemoteRequest = null
    }

    fun forgetApproved() {
        lastApprovedRequest = null
    }

    fun invalidate() {
        clearRequests()
        pendingMore = false
    }

    fun reset() {
        cancel()
        invalidate()
    }

    fun generate(state: ReplyCaptureState, approved: PreparedRemoteReplyRequest? = null, more: Boolean = false) {
        val unavailable = unavailableReason()
        if (unavailable != null) {
            ReplyCaptureSession.update(state.id) {
                it.copy(phase = if (more) ReplyPhase.READY else ReplyPhase.REVIEW,
                    message = unavailable, generationError = unavailable)
            }
            return
        }
        if (approved == null) prepareAndGenerate(state, more) else generateNow(state, approved, more)
    }

    fun requestMore(state: ReplyCaptureState) {
        if (state.phase != ReplyPhase.READY || state.replies.isEmpty() || state.busy) return
        generate(state, approved = lastApprovedRequest, more = true)
    }

    /** Re-prepares the request and generates only if it still matches what the user approved. */
    fun approve(state: ReplyCaptureState) {
        val displayed = pendingRemoteRequest ?: return
        if (state.phase != ReplyPhase.APPROVAL) return
        host.scope.launch(Dispatchers.IO) {
            val profile = host.selectedProfile()
            val snapshot = profile?.let { LearnedStyleRepository(host.appContext).get(it.id) }
            val current = ReplyCaptureSession.state.value ?: return@launch
            val persona = host.selectedPersona()
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

    fun unavailableReason(): String? {
        val context = host.appContext
        if (engine == null) return context.getString(R.string.repli_reply__cloud_not_configured)
        if (!RemoteGenerationPreferences(context).enabled) return context.getString(R.string.repli_reply__cloud_disabled)
        if (RepliAccountSessionRepository.bearerToken() == null &&
            !RepliFirebaseAccountManager.state.value.signedIn) return context.getString(R.string.repli_reply__cloud_sign_in)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.activeNetwork?.let(manager::getNetworkCapabilities)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            return context.getString(R.string.repli_reply__no_internet)
        }
        return null
    }

    private fun prepareAndGenerate(state: ReplyCaptureState, more: Boolean) {
        generation?.cancel()
        pendingMore = more
        ReplyCaptureSession.update(state.id) {
            it.copy(phase = ReplyPhase.GENERATING, replies = if (more) state.replies else emptyList(),
                message = host.appContext.getString(R.string.repli_reply__preparing), generationError = null)
        }
        generation = host.scope.launch(Dispatchers.IO) {
            try {
                val profile = host.selectedProfile()
                val snapshot = profile?.let { LearnedStyleRepository(host.appContext).get(it.id) }
                val persona = host.selectedPersona()
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
                            val reason = host.appContext.getString(R.string.repli_reply__prepare_failed)
                            it.copy(phase = if (more) ReplyPhase.READY else ReplyPhase.REVIEW,
                                message = reason, generationError = reason)
                        }
                    }
                }
            }
        }
    }

    private fun generateNow(state: ReplyCaptureState, approved: PreparedRemoteReplyRequest, more: Boolean) {
        val unavailable = unavailableReason()
        if (unavailable != null) {
            ReplyCaptureSession.update(state.id) {
                it.copy(phase = if (more) ReplyPhase.READY else ReplyPhase.REVIEW,
                    message = unavailable, generationError = unavailable)
            }
            return
        }
        val remote = engine ?: return
        val context = host.appContext
        generation?.cancel()
        ReviewEvidenceStore.discard(state.id)
        val keep = if (more) state.replies else emptyList()
        ReplyCaptureSession.update(state.id) {
            it.copy(phase = ReplyPhase.GENERATING, replies = keep,
                message = context.getString(R.string.repli_reply__finding), generationError = null)
        }
        generation = host.scope.launch(Dispatchers.IO) {
            try {
                val batch = withTimeout(GENERATION_TIMEOUT_MS) { remote.suggestDetailed(approved) }
                withContext(Dispatchers.Main) {
                    if (ReplyCaptureSession.state.value?.id != state.id) return@withContext
                    pendingRemoteRequest = null
                    lastApprovedRequest = approved
                    pendingMore = false
                    val combined = (keep + batch.replies).distinct().take(MAX_REPLIES)
                    host.telemetry.count(CaptureTelemetry.GENERATION_OK)
                    if (!more) host.onRepliesReady()
                    val memoryTurns = batch.memoryTurns
                    val message = when {
                        more && combined.size == keep.size -> context.getString(R.string.repli_reply__no_new_replies)
                        approved.profileId != null && batch.memorySaved != true -> context.getString(R.string.repli_reply__memory_not_saved)
                        memoryTurns != null && memoryTurns > 0 -> context.resources.getQuantityString(
                            R.plurals.repli_reply__based_on_history, memoryTurns, memoryTurns)
                        memoryTurns == 0 && state.frames == 0 -> context.getString(R.string.repli_reply__first_reply_for_chat)
                        else -> context.getString(R.string.repli_reply__tap_to_insert)
                    }
                    ReplyCaptureSession.update(state.id) {
                        it.copy(phase = ReplyPhase.READY, replies = combined, message = message, generationError = null)
                    }
                    flogDebug { "RepliReply: READY cloud replies=${batch.replies.size}" }
                }
            } catch (_: TimeoutCancellationException) {
                host.telemetry.count(CaptureTelemetry.GENERATION_FAILED)
                withContext(Dispatchers.Main) {
                    if (ReplyCaptureSession.state.value?.id == state.id) {
                        ReplyCaptureSession.update(state.id) {
                            val reason = context.getString(R.string.repli_reply__cloud_timeout)
                            it.copy(phase = if (more) ReplyPhase.READY else ReplyPhase.REVIEW,
                                message = reason, generationError = reason)
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                flogError { "RepliReply: cloud generation failed: ${error::class.java.simpleName}" }
                host.telemetry.count(CaptureTelemetry.GENERATION_FAILED)
                val status = when (error) {
                    is SocketTimeoutException -> context.getString(R.string.repli_reply__cloud_timeout)
                    is IOException -> context.getString(R.string.repli_reply__no_internet)
                    is RemoteReplyException -> context.getString(R.string.repli_reply__failure_try_again, error.reason.label)
                    else -> context.getString(R.string.repli_reply__failure_try_again, CloudReplyFailure.UNAVAILABLE.label)
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

    private companion object {
        const val GENERATION_TIMEOUT_MS = 25_000L
        const val MAX_REPLIES = 9
    }
}
