package dev.patrickgold.florisboard.repli.ime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.repli.account.RepliAccountSessionRepository
import dev.patrickgold.florisboard.repli.account.RepliFirebaseAccountManager
import dev.patrickgold.florisboard.repli.data.LearnedStyleRepository
import dev.patrickgold.florisboard.repli.data.RemoteGenerationPreferences
import dev.patrickgold.florisboard.repli.diagnostics.CaptureTelemetry
import dev.patrickgold.florisboard.repli.suggestions.CloudReplyFailure
import dev.patrickgold.florisboard.repli.suggestions.PreparedRewriteRequest
import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyException
import dev.patrickgold.florisboard.repli.suggestions.RepliAccountSessionProvider
import dev.patrickgold.florisboard.repli.suggestions.RewritePrivacyPolicy
import dev.patrickgold.florisboard.repli.suggestions.RewriteTone
import dev.patrickgold.florisboard.repli.suggestions.ServerMediatedReplyEngine
import dev.patrickgold.florisboard.repli.suggestions.ServerMediatedRewriteEngine
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** What the keyboard captured from the host composer when the user tapped Rewrite. */
data class ComposerSnapshot(val text: String, val start: Int, val length: Int)

data class RewriteUiState(
    val active: Boolean = false,
    val text: String = "",
    val tone: RewriteTone = RewriteTone.CASUAL,
    val busy: Boolean = false,
    val status: String = "",
    val candidates: List<String> = emptyList(),
)

/**
 * Rewrites the text already in the composer in a chosen tone. Independent of capture: no
 * screenshots, no chat context, no memory. Tapping a result replaces the composer text; the
 * user still sends it themselves.
 */
class RewriteController(
    private val appContext: Context,
    private val orchestrator: RepliReplyOrchestrator?,
    private val replaceComposer: (ComposerSnapshot, String) -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(RewriteUiState())
    val uiState: StateFlow<RewriteUiState> = mutable.asStateFlow()
    private val telemetry = CaptureTelemetry(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val engine = BuildConfig.REPLI_BACKEND_URL
        .takeIf(ServerMediatedReplyEngine::isConfigured)
        ?.let { ServerMediatedRewriteEngine(it, RepliAccountSessionProvider) }

    private var snapshot: ComposerSnapshot? = null
    private var job: Job? = null

    /** Opens the panel for [composer] and fetches rewrites in the last-used tone. */
    fun begin(composer: ComposerSnapshot?) {
        job?.cancel()
        val tone = RewriteTone.fromWire(preferences.getString(KEY_TONE, null)) ?: RewriteTone.CASUAL
        val draft = composer?.text?.trim().orEmpty()
        snapshot = composer
        when {
            composer == null -> mutable.value = RewriteUiState(active = true, tone = tone,
                status = appContext.getString(R.string.repli_rewrite__too_long))
            draft.isEmpty() -> mutable.value = RewriteUiState(active = true, tone = tone,
                status = appContext.getString(R.string.repli_rewrite__empty))
            draft.length > RewritePrivacyPolicy.MAX_TEXT_CHARACTERS -> mutable.value = RewriteUiState(
                active = true, text = draft, tone = tone, status = appContext.getString(R.string.repli_rewrite__too_long))
            else -> {
                mutable.value = RewriteUiState(active = true, text = draft, tone = tone)
                fetch(tone)
            }
        }
    }

    fun selectTone(tone: RewriteTone) {
        val current = mutable.value
        if (!current.active || current.text.isEmpty()) return
        preferences.edit().putString(KEY_TONE, tone.wireValue).apply()
        mutable.value = current.copy(tone = tone, candidates = emptyList())
        fetch(tone)
    }

    fun retry() {
        val current = mutable.value
        if (!current.active || current.text.isEmpty() || current.busy) return
        fetch(current.tone)
    }

    fun apply(candidate: String) {
        val target = snapshot ?: return
        if (candidate.isBlank()) return
        if (replaceComposer(target, candidate)) {
            telemetry.count(CaptureTelemetry.REWRITE_APPLIED)
            clear()
        } else {
            mutable.value = mutable.value.copy(status = appContext.getString(R.string.repli_rewrite__text_changed))
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        snapshot = null
        mutable.value = RewriteUiState()
    }

    private fun fetch(tone: RewriteTone) {
        val current = mutable.value
        val unavailable = unavailableReason()
        if (unavailable != null) {
            mutable.value = current.copy(busy = false, status = unavailable, candidates = emptyList())
            return
        }
        val remote = engine ?: return
        job?.cancel()
        mutable.value = current.copy(busy = true, status = appContext.getString(R.string.repli_rewrite__working), candidates = emptyList())
        telemetry.count(CaptureTelemetry.REWRITE_REQUESTED)
        job = scope.launch(Dispatchers.IO) {
            try {
                val profile = orchestrator?.selectedProfile()
                val persona = orchestrator?.selectedPersona()
                val learned = profile?.let { LearnedStyleRepository(appContext).get(it.id) }
                val request: PreparedRewriteRequest = RewritePrivacyPolicy.prepare(
                    current.text, tone, persona?.baseStyle, learned, persona,
                ) ?: throw IllegalArgumentException("Draft is not rewritable")
                val candidates = withTimeout(TIMEOUT_MS) { remote.rewrite(request) }
                withContext(Dispatchers.Main) {
                    if (mutable.value.tone != tone || !mutable.value.active) return@withContext
                    mutable.value = mutable.value.copy(busy = false, candidates = candidates,
                        status = appContext.getString(R.string.repli_rewrite__ready))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                flogError { "RepliRewrite: failed: ${error::class.java.simpleName}" }
                telemetry.count(CaptureTelemetry.REWRITE_FAILED)
                val status = when (error) {
                    is TimeoutCancellationException, is SocketTimeoutException -> appContext.getString(R.string.repli_reply__cloud_timeout)
                    is IOException -> appContext.getString(R.string.repli_reply__no_internet)
                    is RemoteReplyException -> appContext.getString(R.string.repli_reply__failure_try_again, error.reason.label)
                    is IllegalArgumentException -> appContext.getString(R.string.repli_rewrite__too_long)
                    else -> appContext.getString(R.string.repli_reply__failure_try_again, CloudReplyFailure.UNAVAILABLE.label)
                }
                withContext(Dispatchers.Main) {
                    if (mutable.value.tone != tone || !mutable.value.active) return@withContext
                    mutable.value = mutable.value.copy(busy = false, status = status, candidates = emptyList())
                }
            }
        }
    }

    private fun unavailableReason(): String? {
        if (engine == null) return appContext.getString(R.string.repli_reply__cloud_not_configured)
        if (!RemoteGenerationPreferences(appContext).enabled) return appContext.getString(R.string.repli_reply__cloud_disabled)
        if (RepliAccountSessionRepository.bearerToken() == null &&
            !RepliFirebaseAccountManager.state.value.signedIn) return appContext.getString(R.string.repli_reply__cloud_sign_in)
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.activeNetwork?.let(manager::getNetworkCapabilities)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            return appContext.getString(R.string.repli_reply__no_internet)
        }
        return null
    }

    private companion object {
        const val PREFERENCES = "repli_rewrite"
        const val KEY_TONE = "tone"
        const val TIMEOUT_MS = 20_000L
    }
}
