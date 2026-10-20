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

package dev.patrickgold.florisboard.repli.openai.auth

import dev.patrickgold.florisboard.repli.openai.OpenAiHttpRequest
import dev.patrickgold.florisboard.repli.openai.OpenAiHttpResponse
import dev.patrickgold.florisboard.repli.openai.OpenAiTransport
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/*
 * Sign in with ChatGPT for open-source / locally hosted apps (OIDC + PKCE, no client secret).
 * Pure Kotlin so the whole protocol is unit-tested; the Android layer only supplies the
 * browser, the loopback listener's lifecycle and encrypted storage.
 */

object ChatGptOAuthConfig {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE_ENDPOINT = "$ISSUER/api/accounts/authorize"
    const val TOKEN_ENDPOINT = "$ISSUER/api/accounts/oauth/token"
    const val JWKS_ENDPOINT = "$ISSUER/.well-known/jwks.json"
    const val DISCOVERY_ENDPOINT = "$ISSUER/.well-known/openid-configuration"
    const val REGISTRATION_CLIENT_ID = "dynamic_agent_client"
    const val RESOURCE = "https://api.openai.com/v1"
    const val REDIRECT_HOST = "127.0.0.1"
    const val REDIRECT_PATH = "/auth/callback"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    const val MANAGE_USAGE_URL = "https://chatgpt.com/settings/usage"

    fun redirectUri(port: Int): String = "http://$REDIRECT_HOST:$port$REDIRECT_PATH"
}

/** One authorization attempt's secrets: kept in memory for the life of the browser round trip only. */
class PendingAuthorization(
    val clientId: String,
    val state: String,
    val nonce: String,
    val codeVerifier: String,
    val redirectUri: String,
) {
    val isRegistration: Boolean get() = clientId == ChatGptOAuthConfig.REGISTRATION_CLIENT_ID
}

/** What the browser sends back to the loopback listener. */
data class AuthorizationCallback(
    val code: String?,
    val state: String?,
    val clientId: String?,
    val scope: String?,
    val error: String?,
) {
    companion object {
        /** Parses the query string of the callback request (`code=…&state=…`). */
        fun fromQuery(query: String?): AuthorizationCallback {
            val values = parseForm(query.orEmpty())
            return AuthorizationCallback(values["code"], values["state"], values["client_id"], values["scope"], values["error"])
        }
    }
}

data class TokenSet(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String?,
    val expiresAtEpochSeconds: Long,
    val scopes: Set<String>,
) {
    val grantsPlanUsage: Boolean get() = ChatGptOAuthConfig.PLAN_SCOPE in scopes
    fun expiresWithin(seconds: Long, nowEpochSeconds: Long): Boolean = expiresAtEpochSeconds - nowEpochSeconds <= seconds
}

enum class OAuthFailure(val label: String) {
    CANCELLED("Sign-in was cancelled"),
    STATE_MISMATCH("Sign-in response didn't match the request"),
    REGISTRATION_INCOMPLETE("ChatGPT didn't finish registering this app"),
    INVALID_GRANT("Sign-in expired · please try again"),
    INVALID_CLIENT("This app's ChatGPT registration is no longer valid"),
    REFRESH_EXPIRED("ChatGPT session expired · sign in again"),
    PLAN_NOT_GRANTED("ChatGPT plan usage wasn't allowed for Repli"),
    INVALID_TOKEN("ChatGPT returned a token Repli couldn't verify"),
    UNAVAILABLE("ChatGPT sign-in is unavailable right now"),
    CONNECTION("Couldn't reach ChatGPT · check your connection"),
    /** Not an error: the code is held until the app is in the foreground and can use the network. */
    FINISH_IN_APP("Return to Repli to finish signing in"),
}

class OAuthException(val failure: OAuthFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

class ChatGptOAuthClient(
    private val transport: OpenAiTransport,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val tokenEndpoint: String = ChatGptOAuthConfig.TOKEN_ENDPOINT,
    private val authorizeEndpoint: String = ChatGptOAuthConfig.AUTHORIZE_ENDPOINT,
    private val revocationEndpoint: String? = null,
) {
    /**
     * Starts an attempt. [savedClientId] null means first registration (`dynamic_agent_client`
     * + `agent_name_hint`); otherwise a returning sign-in for that issued client.
     */
    fun begin(savedClientId: String?, redirectUri: String): PendingAuthorization = PendingAuthorization(
        clientId = savedClientId ?: ChatGptOAuthConfig.REGISTRATION_CLIENT_ID,
        state = randomToken(32),
        nonce = randomToken(32),
        codeVerifier = randomToken(64),
        redirectUri = redirectUri,
    )

    fun authorizationUrl(
        pending: PendingAuthorization,
        hostId: String,
        appName: String,
        idTokenHint: String? = null,
        loginHint: String? = null,
    ): String {
        require(hostId.isNotBlank() && appName.isNotBlank())
        val params = linkedMapOf(
            "client_id" to pending.clientId,
            "ext_agent_host_id" to hostId,
            "response_type" to "code",
            "redirect_uri" to pending.redirectUri,
            "scope" to ChatGptOAuthConfig.SCOPES,
            "resource" to ChatGptOAuthConfig.RESOURCE,
            "state" to pending.state,
            "nonce" to pending.nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to codeChallenge(pending.codeVerifier),
        )
        if (pending.isRegistration) params["agent_name_hint"] = appName
        else {
            idTokenHint?.let { params["id_token_hint"] = it }
            loginHint?.let { params["login_hint"] = it }
        }
        return authorizeEndpoint + "?" + encodeForm(params)
    }

    /** Validates the callback against [pending] and returns the issued client id to exchange with. */
    fun acceptCallback(pending: PendingAuthorization, callback: AuthorizationCallback): String {
        if (callback.state == null || callback.state != pending.state) throw OAuthException(OAuthFailure.STATE_MISMATCH, "state mismatch")
        if (callback.error != null) {
            throw OAuthException(if (callback.error == "access_denied") OAuthFailure.CANCELLED else OAuthFailure.UNAVAILABLE, "authorize error")
        }
        if (callback.code.isNullOrEmpty()) throw OAuthException(OAuthFailure.UNAVAILABLE, "callback without code")
        return if (pending.isRegistration) {
            val issued = callback.clientId
            if (issued.isNullOrEmpty() || issued == ChatGptOAuthConfig.REGISTRATION_CLIENT_ID || !issued.startsWith("oaiapp_")) {
                throw OAuthException(OAuthFailure.REGISTRATION_INCOMPLETE, "no issued client id")
            }
            issued
        } else {
            if (callback.clientId != null && callback.clientId != pending.clientId) {
                throw OAuthException(OAuthFailure.STATE_MISMATCH, "client id changed")
            }
            pending.clientId
        }
    }

    suspend fun exchangeCode(clientId: String, code: String, pending: PendingAuthorization): TokenSet = tokenRequest(
        mapOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "code_verifier" to pending.codeVerifier,
            "redirect_uri" to pending.redirectUri,
            "resource" to ChatGptOAuthConfig.RESOURCE,
        ),
        previous = null,
    )

    /** Rotating refresh: the returned set replaces the old one entirely; keep the old id token when none is returned. */
    suspend fun refresh(clientId: String, current: TokenSet): TokenSet = tokenRequest(
        mapOf(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to current.refreshToken,
            "resource" to ChatGptOAuthConfig.RESOURCE,
        ),
        previous = current,
    )

    /** Best-effort revocation on sign-out; true when ChatGPT confirmed it (200, even for an already-invalid token). */
    suspend fun revoke(clientId: String, refreshToken: String): Boolean {
        val endpoint = revocationEndpoint ?: discoverRevocationEndpoint() ?: return false
        val body = encodeForm(mapOf("token" to refreshToken, "token_type_hint" to "refresh_token", "client_id" to clientId))
        val response = try {
            transport.send(OpenAiHttpRequest("POST", endpoint, formHeaders(), body.toByteArray(Charsets.UTF_8), 15_000))
        } catch (_: IOException) {
            return false
        }
        return response.status in 200..299
    }

    private suspend fun discoverRevocationEndpoint(): String? = try {
        val response = transport.send(OpenAiHttpRequest("GET", ChatGptOAuthConfig.DISCOVERY_ENDPOINT, mapOf("Accept" to "application/json"), null, 15_000))
        if (response.status in 200..299) JSONObject(response.body).optString("revocation_endpoint").takeIf { it.startsWith("https://") } else null
    } catch (_: Exception) {
        null
    }

    private suspend fun tokenRequest(form: Map<String, String>, previous: TokenSet?): TokenSet {
        val response = try {
            transport.send(OpenAiHttpRequest("POST", tokenEndpoint, formHeaders(), encodeForm(form).toByteArray(Charsets.UTF_8), 20_000))
        } catch (error: IOException) {
            throw OAuthException(OAuthFailure.CONNECTION, "token endpoint unreachable", error)
        }
        if (response.status !in 200..299) throw tokenFailure(response, refreshing = previous != null)
        val json = runCatching { JSONObject(response.body) }.getOrElse { throw OAuthException(OAuthFailure.INVALID_TOKEN, "token response was not JSON") }
        val access = json.optString("access_token").takeIf(String::isNotEmpty) ?: throw OAuthException(OAuthFailure.INVALID_TOKEN, "no access token")
        val refreshToken = json.optString("refresh_token").takeIf(String::isNotEmpty) ?: previous?.refreshToken
            ?: throw OAuthException(OAuthFailure.INVALID_TOKEN, "no refresh token")
        val expiresIn = json.optLong("expires_in", 3_600L).coerceIn(60L, 86_400L)
        val scopes = json.optString("scope").split(' ').filter(String::isNotEmpty).toSet().ifEmpty { previous?.scopes.orEmpty() }
        return TokenSet(
            accessToken = access,
            refreshToken = refreshToken,
            idToken = json.optString("id_token").takeIf(String::isNotEmpty) ?: previous?.idToken,
            expiresAtEpochSeconds = clock() + expiresIn,
            scopes = scopes,
        )
    }

    /** Maps OAuth error codes to the closed failure set; the error description is never kept. */
    internal fun tokenFailure(response: OpenAiHttpResponse, refreshing: Boolean): OAuthException {
        val code = runCatching { JSONObject(response.body).optString("error") }.getOrDefault("")
        val failure = when (code) {
            "invalid_client" -> OAuthFailure.INVALID_CLIENT
            "invalid_grant" -> if (refreshing) OAuthFailure.REFRESH_EXPIRED else OAuthFailure.INVALID_GRANT
            "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused" ->
                OAuthFailure.REFRESH_EXPIRED
            else -> if (response.status in 500..599) OAuthFailure.UNAVAILABLE else if (refreshing) OAuthFailure.REFRESH_EXPIRED else OAuthFailure.UNAVAILABLE
        }
        return OAuthException(failure, "token endpoint returned HTTP ${response.status}")
    }

    private fun formHeaders() = mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json")

    private fun randomToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::base64Url)

    companion object {
        fun codeChallenge(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

internal fun encodeForm(values: Map<String, String>): String = values.entries.joinToString("&") { (key, value) ->
    URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
}

internal fun parseForm(query: String): Map<String, String> = query.split('&').filter(String::isNotEmpty).associate { pair ->
    val separator = pair.indexOf('=')
    val key = if (separator < 0) pair else pair.substring(0, separator)
    val value = if (separator < 0) "" else pair.substring(separator + 1)
    URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
}
