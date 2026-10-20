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

import android.content.Context
import dev.patrickgold.florisboard.ime.nlp.latin.repli.EncryptedFileStore
import dev.patrickgold.florisboard.ime.nlp.latin.repli.readBoundedString
import dev.patrickgold.florisboard.ime.nlp.latin.repli.writeBoundedString
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one Sign in with ChatGPT registration this phone holds: the issued client id and host
 * id survive sign-out (re-signing in reuses them); tokens and identity do not.
 */
data class ChatGptAccountRecord(
    val clientId: String?,
    val hostId: String,
    val subject: String? = null,
    val email: String? = null,
    val name: String? = null,
    val tokens: TokenSet? = null,
    val savedAtEpochSeconds: Long = 0,
    val welcomeShown: Boolean = false,
) {
    val signedIn: Boolean get() = tokens != null && clientId != null
}

/** Keystore-encrypted, no-backup storage for [ChatGptAccountRecord]. Never logged, never backed up. */
class ChatGptAccountStore(context: Context) {
    private val store = EncryptedFileStore(File(context.noBackupFilesDir, DIRECTORY))
    private val file = store.file(FILE)

    fun load(): ChatGptAccountRecord = store.read(file, KEY_ALIAS) { input ->
        decode(input.readBoundedString(MAX_BYTES))
    } ?: ChatGptAccountRecord(clientId = null, hostId = newHostId())

    fun save(record: ChatGptAccountRecord) {
        store.write(file, KEY_ALIAS) { output -> output.writeBoundedString(encode(record), MAX_BYTES) }
    }

    fun delete() = store.delete(file)

    private fun encode(record: ChatGptAccountRecord): String = JSONObject().apply {
        put("version", 1)
        put("client_id", record.clientId ?: JSONObject.NULL)
        put("host_id", record.hostId)
        put("subject", record.subject ?: JSONObject.NULL)
        put("email", record.email ?: JSONObject.NULL)
        put("name", record.name ?: JSONObject.NULL)
        put("saved_at", record.savedAtEpochSeconds)
        put("welcome_shown", record.welcomeShown)
        record.tokens?.let { tokens ->
            put("tokens", JSONObject().apply {
                put("access_token", tokens.accessToken)
                put("refresh_token", tokens.refreshToken)
                put("id_token", tokens.idToken ?: JSONObject.NULL)
                put("expires_at", tokens.expiresAtEpochSeconds)
                put("scopes", JSONArray(tokens.scopes.toList()))
            })
        }
    }.toString()

    private fun decode(text: String): ChatGptAccountRecord? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val tokens = json.optJSONObject("tokens")?.let { t ->
            val access = t.optString("access_token").takeIf(String::isNotEmpty) ?: return@let null
            val refresh = t.optString("refresh_token").takeIf(String::isNotEmpty) ?: return@let null
            val scopes = t.optJSONArray("scopes")?.let { array -> (0 until array.length()).map(array::optString).toSet() }.orEmpty()
            TokenSet(access, refresh, t.optString("id_token").takeIf(String::isNotEmpty), t.optLong("expires_at"), scopes)
        }
        return ChatGptAccountRecord(
            clientId = json.optString("client_id").takeIf { it.isNotEmpty() && it != "null" },
            hostId = json.optString("host_id").takeIf(String::isNotEmpty) ?: newHostId(),
            subject = json.optString("subject").takeIf { it.isNotEmpty() && it != "null" },
            email = json.optString("email").takeIf { it.isNotEmpty() && it != "null" },
            name = json.optString("name").takeIf { it.isNotEmpty() && it != "null" },
            tokens = tokens,
            savedAtEpochSeconds = json.optLong("saved_at"),
            welcomeShown = json.optBoolean("welcome_shown"),
        )
    }

    companion object {
        private const val DIRECTORY = "repli_chatgpt_account"
        private const val FILE = "account.bin"
        private const val KEY_ALIAS = "repli_chatgpt_account_v1"
        private const val MAX_BYTES = 64 * 1024

        /** `ext_agent_host_id`: a stable, opaque UUIDv4 URN minted once per install, never an email or user id. */
        fun newHostId(): String = "urn:uuid:" + UUID.randomUUID()
    }
}
