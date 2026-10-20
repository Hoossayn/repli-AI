package dev.patrickgold.florisboard.repli.openai.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.repli.openai.OpenAiCredential
import dev.patrickgold.florisboard.repli.openai.OpenAiTransport
import dev.patrickgold.florisboard.repli.openai.UrlConnectionOpenAiTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ChatGptAccountState(
    val busy: Boolean = false,
    val signedIn: Boolean = false,
    /** Signed in and `chatgpt.tokens.use.direct` was granted. */
    val planAllowed: Boolean = false,
    val email: String? = null,
    val name: String? = null,
    val registered: Boolean = false,
    val welcomePending: Boolean = false,
    val errorMessage: String? = null,
    /** Set when sign-out could not confirm revocation; the user can disconnect Repli in ChatGPT settings. */
    val revocationUnconfirmed: Boolean = false,
)

/**
 * Owns the Sign in with ChatGPT session on this phone: the browser round trip through the
 * loopback listener, token storage, refresh and sign-out. Tokens never leave the process
 * except as the Authorization header to api.openai.com.
 */
object ChatGptAccountManager {
    const val APP_NAME = "Repli"
    private const val RETURN_LINK = "ui://florisboard/settings/chatgpt-plan"
    private const val SIGN_IN_TIMEOUT_MILLIS = 5 * 60_000L
    private const val REFRESH_MARGIN_SECONDS = 5 * 60L

    private val lock = Mutex()
    private val mutableState = MutableStateFlow(ChatGptAccountState())
    val state: StateFlow<ChatGptAccountState> = mutableState.asStateFlow()

    private lateinit var store: ChatGptAccountStore
    private var transport: OpenAiTransport = UrlConnectionOpenAiTransport
    private val oauth by lazy { ChatGptOAuthClient(transport) }
    private val verifier by lazy { IdTokenVerifier(transport) }
    @Volatile private var record: ChatGptAccountRecord? = null

    fun initialize(context: Context) {
        if (::store.isInitialized) return
        store = ChatGptAccountStore(context.applicationContext)
        record = store.load()
        publish()
    }

    /** Opens the ChatGPT sign-in page in a Custom Tab and completes the flow when the browser returns. */
    suspend fun signIn(context: Context): Result<Unit> = operation {
        // Sockets, files and the token exchange all belong off the main thread.
        withContext(Dispatchers.IO) { signInBlocking(context) }
    }

    private suspend fun signInBlocking(context: Context) {
        val current = record ?: store.load()
        if (current.clientId == null) store.save(current) // the host id must exist before the first authorize call
        LoopbackRedirectServer(RETURN_LINK, APP_NAME).use { server ->
            val pending = oauth.begin(current.clientId, server.redirectUri)
            val url = oauth.authorizationUrl(
                pending, current.hostId, APP_NAME,
                idTokenHint = current.tokens?.idToken, loginHint = current.email,
            )
            withContext(Dispatchers.Main) { openBrowser(context, url) }
            val callback = server.await(SIGN_IN_TIMEOUT_MILLIS)
                ?: throw OAuthException(OAuthFailure.CANCELLED, "no callback before timeout")
            val clientId = oauth.acceptCallback(pending, callback)
            val tokens = oauth.exchangeCode(clientId, callback.code!!, pending)
            val idToken = tokens.idToken ?: throw OAuthException(OAuthFailure.INVALID_TOKEN, "no id token")
            val identity = verifier.verify(idToken, clientId, pending.nonce)
            if (current.subject != null && current.clientId == clientId && current.subject != identity.subject) {
                throw OAuthException(OAuthFailure.INVALID_TOKEN, "a different account answered for this registration")
            }
            if (!tokens.grantsPlanUsage) throw OAuthException(OAuthFailure.PLAN_NOT_GRANTED, "plan scope missing")
            record = ChatGptAccountRecord(
                clientId = clientId, hostId = current.hostId,
                subject = identity.subject, email = identity.email, name = identity.name,
                tokens = tokens, savedAtEpochSeconds = System.currentTimeMillis() / 1_000,
                welcomeShown = current.welcomeShown,
            ).also(store::save)
        }
    }

    /** A fresh-enough access token for one request, refreshing under the lock when near expiry; null when signed out. */
    suspend fun credentialForRequest(): OpenAiCredential.ChatGptPlan? {
        val current = record ?: return null
        val tokens = current.tokens ?: return null
        val clientId = current.clientId ?: return null
        if (!tokens.grantsPlanUsage) return null
        val now = System.currentTimeMillis() / 1_000
        if (!tokens.expiresWithin(REFRESH_MARGIN_SECONDS, now)) return OpenAiCredential.ChatGptPlan(tokens.accessToken)
        return lock.withLock {
            val latest = record?.tokens ?: return@withLock null
            if (!latest.expiresWithin(REFRESH_MARGIN_SECONDS, System.currentTimeMillis() / 1_000)) {
                return@withLock OpenAiCredential.ChatGptPlan(latest.accessToken)
            }
            try {
                val refreshed = oauth.refresh(clientId, latest)
                record = record?.copy(tokens = refreshed, savedAtEpochSeconds = System.currentTimeMillis() / 1_000)?.also(store::save)
                publish()
                OpenAiCredential.ChatGptPlan(refreshed.accessToken)
            } catch (error: OAuthException) {
                when (error.failure) {
                    OAuthFailure.REFRESH_EXPIRED, OAuthFailure.INVALID_GRANT -> clearTokens(keepRegistration = true, message = error.failure.label)
                    OAuthFailure.INVALID_CLIENT -> clearTokens(keepRegistration = false, message = error.failure.label)
                    else -> publish(errorMessage = error.failure.label)
                }
                if (error.failure == OAuthFailure.CONNECTION || error.failure == OAuthFailure.UNAVAILABLE) {
                    // Transient: let the request try with what we have rather than forcing a sign-in.
                    OpenAiCredential.ChatGptPlan(latest.accessToken)
                } else null
            }
        }
    }

    /** Called when api.openai.com rejected the token (401): drop it so the next tap asks to sign in again. */
    fun invalidateAccessToken() {
        val current = record ?: return
        val tokens = current.tokens ?: return
        record = current.copy(tokens = tokens.copy(expiresAtEpochSeconds = 0)).also(store::save)
    }

    suspend fun signOut(): Result<Unit> = operation {
        val current = record ?: return@operation
        val revoked = current.tokens?.let { tokens -> current.clientId?.let { oauth.revoke(it, tokens.refreshToken) } } ?: true
        record = current.copy(tokens = null, subject = null, email = null, name = null, savedAtEpochSeconds = 0).also(store::save)
        publish(revocationUnconfirmed = !revoked)
    }

    fun markWelcomeShown() {
        record = record?.copy(welcomeShown = true)?.also(store::save)
        publish()
    }

    fun clearError() = publish(errorMessage = null)

    private fun clearTokens(keepRegistration: Boolean, message: String) {
        val current = record ?: return
        record = if (keepRegistration) current.copy(tokens = null) else ChatGptAccountRecord(clientId = null, hostId = current.hostId)
        store.save(record!!)
        publish(errorMessage = message)
    }

    private suspend fun operation(block: suspend () -> Unit): Result<Unit> {
        publish(busy = true, errorMessage = null, revocationUnconfirmed = false)
        return try {
            block()
            publish(busy = false)
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            publish(busy = false)
            throw cancellation
        } catch (error: OAuthException) {
            // Cause class and message only (an IOException message names a host, never a token).
            flogError { "RepliChatGpt: ${error.failure} (${error.message}) cause=${error.cause?.let { it::class.java.name + ": " + it.message }}" }
            publish(busy = false, errorMessage = if (error.failure == OAuthFailure.CANCELLED) null else detailed(error.failure.label, error.cause))
            Result.failure(error)
        } catch (error: Exception) {
            flogError { "RepliChatGpt: ${error::class.java.name}: ${error.message}" }
            publish(busy = false, errorMessage = detailed(OAuthFailure.UNAVAILABLE.label, error))
            Result.failure(error)
        }
    }

    /** Debug builds show the underlying exception so a failing step can be named without a logcat session. */
    private fun detailed(label: String, cause: Throwable?): String =
        if (BuildConfig.DEBUG && cause != null) "$label · ${cause::class.java.simpleName}: ${cause.message}" else label

    private fun openBrowser(context: Context, url: String) {
        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().apply {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.launchUrl(context, uri)
        } catch (_: Exception) {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun publish(
        busy: Boolean = mutableState.value.busy,
        errorMessage: String? = mutableState.value.errorMessage,
        revocationUnconfirmed: Boolean = mutableState.value.revocationUnconfirmed,
    ) {
        val current = record
        val tokens = current?.tokens
        mutableState.value = ChatGptAccountState(
            busy = busy,
            signedIn = current?.signedIn == true,
            planAllowed = tokens?.grantsPlanUsage == true,
            email = current?.email,
            name = current?.name,
            registered = current?.clientId != null,
            welcomePending = current?.signedIn == true && current.welcomeShown.not(),
            errorMessage = errorMessage,
            revocationUnconfirmed = revocationUnconfirmed,
        )
    }
}
