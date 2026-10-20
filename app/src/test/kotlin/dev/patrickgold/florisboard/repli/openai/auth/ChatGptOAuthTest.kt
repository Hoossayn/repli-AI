package dev.patrickgold.florisboard.repli.openai.auth

import dev.patrickgold.florisboard.repli.openai.OpenAiHttpRequest
import dev.patrickgold.florisboard.repli.openai.OpenAiHttpResponse
import dev.patrickgold.florisboard.repli.openai.OpenAiTransport
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private class RecordingTransport(private val handler: (OpenAiHttpRequest) -> OpenAiHttpResponse) : OpenAiTransport {
    val requests = ArrayList<OpenAiHttpRequest>()
    override suspend fun send(request: OpenAiHttpRequest): OpenAiHttpResponse { requests += request; return handler(request) }
    fun form(index: Int) = parseForm(String(requests[index].body!!))
}

private fun tokenJson(access: String = "at", refresh: String? = "rt", id: String? = "idt", scope: String? = ChatGptOAuthConfig.SCOPES) =
    JSONObject().put("access_token", access).put("token_type", "Bearer").put("expires_in", 3600).apply {
        refresh?.let { put("refresh_token", it) }; id?.let { put("id_token", it) }; scope?.let { put("scope", it) }
    }.toString()

class ChatGptOAuthClientTest {
    private val seeded = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7) }
    private val client = ChatGptOAuthClient(RecordingTransport { OpenAiHttpResponse(200, tokenJson()) }, seeded, clock = { 1_000L })

    @Test fun registrationAuthorizeUrlCarriesEveryParameter() {
        val pending = client.begin(null, "http://127.0.0.1:1455/auth/callback")
        assertTrue(pending.isRegistration)
        val url = client.authorizationUrl(pending, "urn:uuid:123e4567-e89b-42d3-a456-426614174000", "Repli", idTokenHint = "ignored")
        assertTrue(url.startsWith(ChatGptOAuthConfig.AUTHORIZE_ENDPOINT + "?"))
        val query = parseForm(url.substringAfter('?'))
        assertEquals("dynamic_agent_client", query["client_id"])
        assertEquals("Repli", query["agent_name_hint"])
        assertEquals("urn:uuid:123e4567-e89b-42d3-a456-426614174000", query["ext_agent_host_id"])
        assertEquals("code", query["response_type"])
        assertEquals("http://127.0.0.1:1455/auth/callback", query["redirect_uri"])
        assertEquals(ChatGptOAuthConfig.SCOPES, query["scope"])
        assertEquals("https://api.openai.com/v1", query["resource"])
        assertEquals(pending.state, query["state"])
        assertEquals(pending.nonce, query["nonce"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(ChatGptOAuthClient.codeChallenge(pending.codeVerifier), query["code_challenge"])
        assertNull(query["id_token_hint"], "no hint during registration")
        assertTrue(pending.codeVerifier.length in 43..128 && pending.codeVerifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun returningAuthorizeUrlUsesIssuedClientAndHints() {
        val pending = client.begin("oaiapp_abc", "http://127.0.0.1:2000/auth/callback")
        val query = parseForm(client.authorizationUrl(pending, "urn:uuid:x", "Repli", idTokenHint = "tok", loginHint = "a@b.c").substringAfter('?'))
        assertEquals("oaiapp_abc", query["client_id"])
        assertNull(query["agent_name_hint"])
        assertEquals("tok", query["id_token_hint"])
        assertEquals("a@b.c", query["login_hint"])
    }

    @Test fun pkceChallengeMatchesRfcExample() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptOAuthClient.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun callbackValidation() {
        val registration = client.begin(null, "http://127.0.0.1:1/auth/callback")
        assertEquals("oaiapp_new", client.acceptCallback(registration, AuthorizationCallback("c", registration.state, "oaiapp_new", "openid", null)))
        fun failure(pending: PendingAuthorization, callback: AuthorizationCallback) =
            try { client.acceptCallback(pending, callback); null } catch (e: OAuthException) { e.failure }
        assertEquals(OAuthFailure.STATE_MISMATCH, failure(registration, AuthorizationCallback("c", "other", "oaiapp_new", null, null)))
        assertEquals(OAuthFailure.REGISTRATION_INCOMPLETE, failure(registration, AuthorizationCallback("c", registration.state, null, null, null)))
        assertEquals(OAuthFailure.REGISTRATION_INCOMPLETE, failure(registration, AuthorizationCallback("c", registration.state, "dynamic_agent_client", null, null)))
        assertEquals(OAuthFailure.CANCELLED, failure(registration, AuthorizationCallback(null, registration.state, null, null, "access_denied")))
        assertEquals(OAuthFailure.UNAVAILABLE, failure(registration, AuthorizationCallback(null, registration.state, null, null, "server_error")))
        val returning = client.begin("oaiapp_saved", "http://127.0.0.1:1/auth/callback")
        assertEquals("oaiapp_saved", client.acceptCallback(returning, AuthorizationCallback("c", returning.state, null, null, null)))
        assertEquals("oaiapp_saved", client.acceptCallback(returning, AuthorizationCallback("c", returning.state, "oaiapp_saved", null, null)))
        assertEquals(OAuthFailure.STATE_MISMATCH, failure(returning, AuthorizationCallback("c", returning.state, "oaiapp_other", null, null)))
    }

    @Test fun callbackQueryParsing() {
        val callback = AuthorizationCallback.fromQuery("code=abc%2Fdef&state=s1&client_id=oaiapp_1&scope=openid%20profile")
        assertEquals("abc/def", callback.code)
        assertEquals("oaiapp_1", callback.clientId)
        assertEquals("openid profile", callback.scope)
        assertNull(callback.error)
        assertEquals("access_denied", AuthorizationCallback.fromQuery("error=access_denied&state=s").error)
    }

    @Test fun exchangeAndRefreshSendFormBodiesAndRotate() = runBlocking {
        val transport = RecordingTransport { OpenAiHttpResponse(200, tokenJson(refresh = "rt2", id = null, scope = null)) }
        val client = ChatGptOAuthClient(transport, seeded, clock = { 5_000L })
        val pending = client.begin("oaiapp_x", "http://127.0.0.1:9/auth/callback")
        val first = TokenSet("old", "rt1", "idt1", 100, setOf("openid", ChatGptOAuthConfig.PLAN_SCOPE))
        val refreshed = client.refresh("oaiapp_x", first)
        val form = transport.form(0)
        assertEquals("application/x-www-form-urlencoded", transport.requests[0].headers["Content-Type"])
        assertEquals(ChatGptOAuthConfig.TOKEN_ENDPOINT, transport.requests[0].url)
        assertEquals("refresh_token", form["grant_type"]); assertEquals("oaiapp_x", form["client_id"])
        assertEquals("rt1", form["refresh_token"]); assertEquals("https://api.openai.com/v1", form["resource"]); assertNull(form["scope"])
        assertEquals("rt2", refreshed.refreshToken); assertEquals("idt1", refreshed.idToken, "id token retained")
        assertEquals(first.scopes, refreshed.scopes, "scopes retained when omitted")
        assertEquals(8_600L, refreshed.expiresAtEpochSeconds)
        assertTrue(refreshed.grantsPlanUsage)

        client.exchangeCode("oaiapp_x", "the-code", pending)
        val exchange = transport.form(1)
        assertEquals("authorization_code", exchange["grant_type"]); assertEquals("the-code", exchange["code"])
        assertEquals(pending.codeVerifier, exchange["code_verifier"]); assertEquals(pending.redirectUri, exchange["redirect_uri"])
        assertEquals("oaiapp_x", exchange["client_id"])
    }

    @Test fun tokenErrorsMap() = runBlocking {
        suspend fun failure(status: Int, code: String, refreshing: Boolean): OAuthFailure {
            val client = ChatGptOAuthClient(RecordingTransport { OpenAiHttpResponse(status, JSONObject().put("error", code).put("error_description", "secret").toString()) }, seeded)
            return try {
                if (refreshing) client.refresh("c", TokenSet("a", "r", null, 0, emptySet())) else client.exchangeCode("c", "code", client.begin("c", "http://127.0.0.1:1/auth/callback"))
                fail("expected failure")
            } catch (e: OAuthException) { assertFalse("secret" in e.message.orEmpty()); e.failure }
        }
        assertEquals(OAuthFailure.INVALID_GRANT, failure(400, "invalid_grant", refreshing = false))
        assertEquals(OAuthFailure.REFRESH_EXPIRED, failure(400, "invalid_grant", refreshing = true))
        assertEquals(OAuthFailure.REFRESH_EXPIRED, failure(400, "refresh_token_reused", refreshing = true))
        assertEquals(OAuthFailure.INVALID_CLIENT, failure(401, "invalid_client", refreshing = true))
        assertEquals(OAuthFailure.UNAVAILABLE, failure(503, "", refreshing = true))
        val down = ChatGptOAuthClient(RecordingTransport { throw java.io.IOException("offline") }, seeded)
        try { down.refresh("c", TokenSet("a", "r", null, 0, emptySet())); fail() } catch (e: OAuthException) { assertEquals(OAuthFailure.CONNECTION, e.failure) }
    }

    @Test fun exchangeWithoutPlanScopeIsVisibleToCaller() = runBlocking {
        val client = ChatGptOAuthClient(RecordingTransport { OpenAiHttpResponse(200, tokenJson(scope = "openid profile email")) }, seeded)
        val tokens = client.exchangeCode("c", "code", client.begin("c", "http://127.0.0.1:1/auth/callback"))
        assertFalse(tokens.grantsPlanUsage)
    }

    @Test fun revokeUsesDiscoveryAndTreats200AsConfirmed() = runBlocking {
        val transport = RecordingTransport { request ->
            if (request.url == ChatGptOAuthConfig.DISCOVERY_ENDPOINT) OpenAiHttpResponse(200, JSONObject().put("revocation_endpoint", "https://auth.openai.com/oauth/revoke").toString())
            else OpenAiHttpResponse(200, "")
        }
        val client = ChatGptOAuthClient(transport, seeded)
        assertTrue(client.revoke("oaiapp_x", "rt"))
        assertEquals("https://auth.openai.com/oauth/revoke", transport.requests[1].url)
        val form = transport.form(1)
        assertEquals("rt", form["token"]); assertEquals("refresh_token", form["token_type_hint"]); assertEquals("oaiapp_x", form["client_id"])
        assertFalse(ChatGptOAuthClient(RecordingTransport { throw java.io.IOException() }, seeded).revoke("c", "r"))
    }
}

class IdTokenVerifierTest {
    private fun jwt(header: JSONObject, payload: JSONObject, sign: (ByteArray) -> ByteArray): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val signed = encoder.encodeToString(header.toString().toByteArray()) + "." + encoder.encodeToString(payload.toString().toByteArray())
        return signed + "." + encoder.encodeToString(sign(signed.toByteArray(Charsets.US_ASCII)))
    }

    private fun claims(now: Long) = JSONObject().put("iss", ChatGptOAuthConfig.ISSUER).put("aud", "oaiapp_1").put("sub", "user-9")
        .put("exp", now + 600).put("iat", now).put("nonce", "n1").put("email", "ada@example.com").put("name", "Ada")

    @Test fun verifiesRs256AgainstJwks() = runBlocking {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val public = pair.public as RSAPublicKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val jwks = JSONObject().put("keys", JSONArray().put(JSONObject().put("kty", "RSA").put("kid", "k1").put("alg", "RS256")
            .put("n", encoder.encodeToString(public.modulus.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }))
            .put("e", encoder.encodeToString(public.publicExponent.toByteArray()))))
        val transport = RecordingTransport { OpenAiHttpResponse(200, jwks.toString()) }
        val verifier = IdTokenVerifier(transport, clock = { 10_000L })
        val token = jwt(JSONObject().put("alg", "RS256").put("kid", "k1"), claims(10_000L)) { bytes ->
            Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(bytes); sign() }
        }
        val identity = verifier.verify(token, "oaiapp_1", "n1")
        assertEquals(VerifiedIdentity("user-9", "ada@example.com", "Ada"), identity)
        verifier.verify(token, "oaiapp_1", "n1")
        assertEquals(1, transport.requests.size, "JWKS cached")
        val tampered = token.dropLast(2) + "AA"
        try { verifier.verify(tampered, "oaiapp_1", "n1"); fail() } catch (e: OAuthException) { assertEquals(OAuthFailure.INVALID_TOKEN, e.failure) }
    }

    @Test fun verifiesEs256() = runBlocking {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val public = pair.public as ECPublicKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun coordinate(value: java.math.BigInteger): String {
            val bytes = value.toByteArray().let { if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else it }
            return encoder.encodeToString(ByteArray(32 - bytes.size) + bytes)
        }
        val jwks = JSONObject().put("keys", JSONArray().put(JSONObject().put("kty", "EC").put("crv", "P-256").put("kid", "e1")
            .put("x", coordinate(public.w.affineX)).put("y", coordinate(public.w.affineY))))
        val verifier = IdTokenVerifier(RecordingTransport { OpenAiHttpResponse(200, jwks.toString()) }, clock = { 10_000L })
        val token = jwt(JSONObject().put("alg", "ES256").put("kid", "e1"), claims(10_000L)) { bytes ->
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }
            derToJose(der)
        }
        assertEquals("user-9", verifier.verify(token, "oaiapp_1", "n1").subject)
    }

    private fun derToJose(der: ByteArray): ByteArray {
        var offset = 2
        fun readInteger(): ByteArray {
            require(der[offset] == 0x02.toByte()); val length = der[offset + 1].toInt(); offset += 2
            val value = der.copyOfRange(offset, offset + length); offset += length
            val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - trimmed.size) + trimmed
        }
        return readInteger() + readInteger()
    }

    @Test fun claimChecks() {
        val verifier = IdTokenVerifier(RecordingTransport { OpenAiHttpResponse(500, "") }, clock = { 10_000L })
        fun rejected(mutate: JSONObject.() -> Unit, clientId: String = "oaiapp_1", nonce: String = "n1"): Boolean = try {
            verifier.checkClaims(claims(10_000L).apply(mutate), clientId, nonce); false
        } catch (e: OAuthException) { e.failure == OAuthFailure.INVALID_TOKEN }
        assertNotNull(verifier.checkClaims(claims(10_000L), "oaiapp_1", "n1"))
        assertNotNull(verifier.checkClaims(claims(10_000L).put("aud", JSONArray(listOf("other", "oaiapp_1"))), "oaiapp_1", "n1"))
        assertTrue(rejected({ put("iss", "https://evil.example") }))
        assertTrue(rejected({}, clientId = "oaiapp_2"))
        assertTrue(rejected({ put("exp", 9_000L) }))
        assertTrue(rejected({}, nonce = "other"))
        assertTrue(rejected({ remove("sub") }))
        assertTrue(rejected({ put("nbf", 20_000L) }))
    }
}

class LoopbackRedirectServerTest {
    private fun get(url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        val status = connection.responseCode
        val body = (if (status < 400) connection.inputStream else connection.errorStream).bufferedReader().readText()
        connection.disconnect()
        return status to body
    }

    @Test fun servesCallbackOnceAndIgnoresOtherPaths() {
        LoopbackRedirectServer("ui://florisboard/settings/chatgpt").use { server ->
            assertTrue(server.redirectUri.startsWith("http://127.0.0.1:") && server.redirectUri.endsWith("/auth/callback"))
            val results = ArrayList<Pair<Int, String>>()
            val requester = thread {
                results += get("http://127.0.0.1:${server.port}/favicon.ico")
                results += get("${server.redirectUri}?code=abc&state=xyz&client_id=oaiapp_7&scope=openid")
            }
            val callback = server.awaitBlocking(10_000)
            requester.join(10_000)
            assertNotNull(callback)
            assertEquals("abc", callback.code); assertEquals("xyz", callback.state); assertEquals("oaiapp_7", callback.clientId)
            assertEquals(404, results[0].first)
            assertEquals(200, results[1].first)
            assertTrue("Back to Repli" in results[1].second && "ui://florisboard/settings/chatgpt" in results[1].second)
        }
    }

    @Test fun timesOutWithoutCallback() {
        LoopbackRedirectServer(null).use { server -> assertNull(server.awaitBlocking(200)) }
    }
}
