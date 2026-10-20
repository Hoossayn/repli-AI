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
import dev.patrickgold.florisboard.repli.openai.OpenAiTransport
import java.io.IOException
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** The claims Repli keeps from a verified ID token. */
data class VerifiedIdentity(val subject: String, val email: String?, val name: String?)

/**
 * Verifies the OIDC ID token against OpenAI's JWKS (RS256 / ES256) and checks issuer,
 * audience, expiry and nonce. Keys are fetched once and refetched when an unknown `kid` appears.
 */
class IdTokenVerifier(
    private val transport: OpenAiTransport,
    private val jwksUrl: String = ChatGptOAuthConfig.JWKS_ENDPOINT,
    private val issuer: String = ChatGptOAuthConfig.ISSUER,
    private val clock: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    private var keys: Map<String, PublicKey> = emptyMap()

    suspend fun verify(idToken: String, clientId: String, nonce: String): VerifiedIdentity {
        val parts = idToken.split('.')
        if (parts.size != 3) throw invalid("malformed token")
        val header = runCatching { JSONObject(decode(parts[0])) }.getOrElse { throw invalid("bad header") }
        val payload = runCatching { JSONObject(decode(parts[1])) }.getOrElse { throw invalid("bad payload") }
        val algorithm = header.optString("alg")
        val kid = header.optString("kid")
        val key = keyFor(kid, algorithm)
        val signed = (parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII)
        val signature = Base64.getUrlDecoder().decode(parts[2])
        if (!verifySignature(algorithm, key, signed, signature)) throw invalid("signature")
        return checkClaims(payload, clientId, nonce)
    }

    internal fun checkClaims(payload: JSONObject, clientId: String, nonce: String): VerifiedIdentity {
        if (payload.optString("iss") != issuer) throw invalid("issuer")
        val audience = payload.opt("aud")
        val audienceMatches = when (audience) {
            is String -> audience == clientId
            is JSONArray -> (0 until audience.length()).any { audience.optString(it) == clientId }
            else -> false
        }
        if (!audienceMatches) throw invalid("audience")
        val now = clock()
        if (payload.optLong("exp", 0L) <= now - CLOCK_SKEW_SECONDS) throw invalid("expired")
        if (payload.has("nbf") && payload.optLong("nbf") > now + CLOCK_SKEW_SECONDS) throw invalid("not yet valid")
        if (payload.optString("nonce") != nonce) throw invalid("nonce")
        val subject = payload.optString("sub").takeIf(String::isNotEmpty) ?: throw invalid("subject")
        return VerifiedIdentity(
            subject = subject,
            email = payload.optString("email").takeIf(String::isNotEmpty),
            name = payload.optString("name").takeIf(String::isNotEmpty),
        )
    }

    private suspend fun keyFor(kid: String, algorithm: String): PublicKey {
        keys[kid]?.let { return it }
        keys = fetchKeys()
        return keys[kid] ?: keys.values.singleOrNull()?.takeIf { kid.isEmpty() } ?: throw invalid("unknown key $algorithm")
    }

    private suspend fun fetchKeys(): Map<String, PublicKey> {
        val response = try {
            transport.send(OpenAiHttpRequest("GET", jwksUrl, mapOf("Accept" to "application/json"), null, 15_000))
        } catch (error: IOException) {
            throw OAuthException(OAuthFailure.CONNECTION, "JWKS unreachable", error)
        }
        if (response.status !in 200..299) throw OAuthException(OAuthFailure.UNAVAILABLE, "JWKS returned HTTP ${response.status}")
        val jwks = runCatching { JSONObject(response.body).getJSONArray("keys") }.getOrElse { throw invalid("JWKS was not JSON") }
        return buildMap {
            for (index in 0 until jwks.length()) {
                val jwk = jwks.optJSONObject(index) ?: continue
                val key = runCatching { publicKey(jwk) }.getOrNull() ?: continue
                put(jwk.optString("kid"), key)
            }
        }
    }

    private fun verifySignature(algorithm: String, key: PublicKey, signed: ByteArray, signature: ByteArray): Boolean = when (algorithm) {
        "RS256" -> Signature.getInstance("SHA256withRSA").run { initVerify(key); update(signed); verify(signature) }
        "ES256" -> Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(signed); verify(joseToDer(signature)) }
        else -> throw invalid("unsupported algorithm")
    }

    private fun invalid(reason: String) = OAuthException(OAuthFailure.INVALID_TOKEN, "ID token rejected: $reason")

    companion object {
        private const val CLOCK_SKEW_SECONDS = 120L

        internal fun publicKey(jwk: JSONObject): PublicKey = when (jwk.optString("kty")) {
            "RSA" -> KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(unsigned(jwk.getString("n")), unsigned(jwk.getString("e"))),
            )
            "EC" -> {
                require(jwk.optString("crv") == "P-256") { "unsupported curve" }
                val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                val spec = parameters.getParameterSpec(ECParameterSpec::class.java)
                KeyFactory.getInstance("EC").generatePublic(
                    ECPublicKeySpec(ECPoint(unsigned(jwk.getString("x")), unsigned(jwk.getString("y"))), spec),
                )
            }
            else -> throw IllegalArgumentException("unsupported key type")
        }

        private fun unsigned(base64Url: String) = BigInteger(1, Base64.getUrlDecoder().decode(base64Url))

        private fun decode(segment: String): String = String(Base64.getUrlDecoder().decode(segment), Charsets.UTF_8)

        /** JWS ES256 signatures are raw r||s; java.security expects DER SEQUENCE { INTEGER r, INTEGER s }. */
        internal fun joseToDer(raw: ByteArray): ByteArray {
            require(raw.size == 64) { "ES256 signature must be 64 bytes" }
            fun integer(bytes: ByteArray): ByteArray {
                var start = 0
                while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
                val body = bytes.copyOfRange(start, bytes.size)
                val padded = if (body[0] < 0) byteArrayOf(0) + body else body
                return byteArrayOf(0x02, padded.size.toByte()) + padded
            }
            val content = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
            return byteArrayOf(0x30, content.size.toByte()) + content
        }
    }
}
