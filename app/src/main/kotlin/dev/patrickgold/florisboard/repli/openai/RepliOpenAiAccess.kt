package dev.patrickgold.florisboard.repli.openai

import android.content.Context
import dev.patrickgold.florisboard.repli.openai.auth.ChatGptAccountManager

enum class OpenAiRoute(val label: String) {
    CHATGPT_PLAN("Using ChatGPT plan"),
    API_KEY("Using your OpenAI API key"),
}

/**
 * The single place the keyboard asks "who pays for AI calls?". Preference order: the user's
 * ChatGPT plan when signed in with plan usage granted, else their own API key. There is no
 * Repli-paid route and no silent switch between the two when one fails.
 */
object RepliOpenAiAccess : OpenAiCredentialProvider {
    @Volatile private lateinit var appContext: Context
    @Volatile private var apiKeyStore: OpenAiApiKeyStore? = null
    @Volatile private var cachedEngines: DirectReplyEngines? = null
    @Volatile private var cachedMemory: ProfileMemoryStore? = null

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        ChatGptAccountManager.initialize(appContext)
        apiKeyStore = OpenAiApiKeyStore(appContext)
    }

    /** Which route the next call would take, or null when nothing is set up. */
    fun route(): OpenAiRoute? = when {
        ChatGptAccountManager.state.value.planAllowed -> OpenAiRoute.CHATGPT_PLAN
        apiKeyStore?.load() != null -> OpenAiRoute.API_KEY
        else -> null
    }

    val configured: Boolean get() = route() != null

    override suspend fun credentialForRequest(): OpenAiCredential? {
        ChatGptAccountManager.credentialForRequest()?.let { return it }
        return apiKeyStore?.load()?.let { OpenAiCredential.ApiKey(it.key, it.model) }
    }

    fun apiKeys(): OpenAiApiKeyStore = checkNotNull(apiKeyStore) { "RepliOpenAiAccess.initialize first" }

    /** On-device chat memory shared by the reply flow and the Chats screen; needs [initialize] to have run. */
    fun memory(): ProfileMemoryStore = cachedMemory ?: synchronized(this) {
        cachedMemory ?: ProfileMemoryStore(EncryptedProfileMemoryStorage(appContext)).also { cachedMemory = it }
    }

    /** Safe to call before [initialize] (the IME controller is built early in Application.onCreate). */
    fun engines(): DirectReplyEngines = cachedEngines ?: synchronized(this) {
        cachedEngines ?: DirectReplyEngines(
            credentials = this,
            memoryProvider = { if (::appContext.isInitialized) memory() else null },
            onPlanTokenRejected = ChatGptAccountManager::invalidateAccessToken,
        ).also { cachedEngines = it }
    }
}
