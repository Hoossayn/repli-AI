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

package dev.patrickgold.florisboard.repli.openai

import android.content.Context
import dev.patrickgold.florisboard.ime.nlp.latin.repli.EncryptedFileStore
import dev.patrickgold.florisboard.ime.nlp.latin.repli.readBoundedString
import dev.patrickgold.florisboard.ime.nlp.latin.repli.writeBoundedString
import java.io.File
import org.json.JSONObject

data class OpenAiApiKeyConfig(val key: String, val model: String) {
    /** What settings may show: never the key itself. */
    val masked: String get() = if (key.length <= 8) "••••" else key.take(3) + "…" + key.takeLast(4)
}

/** Bring-your-own OpenAI API key, Keystore-encrypted and excluded from backups. */
class OpenAiApiKeyStore(context: Context) {
    private val store = EncryptedFileStore(File(context.noBackupFilesDir, DIRECTORY))
    private val file = store.file(FILE)

    fun load(): OpenAiApiKeyConfig? = store.read(file, KEY_ALIAS) { input ->
        val json = runCatching { JSONObject(input.readBoundedString(MAX_BYTES)) }.getOrNull() ?: return@read null
        val key = json.optString("key").takeIf(::looksLikeKey) ?: return@read null
        OpenAiApiKeyConfig(key, json.optString("model").takeIf(String::isNotBlank) ?: DEFAULT_MODEL)
    }

    fun save(config: OpenAiApiKeyConfig) {
        require(looksLikeKey(config.key)) { "That doesn't look like an OpenAI API key" }
        require(config.model.isNotBlank() && config.model.length <= 80) { "Choose a model" }
        store.write(file, KEY_ALIAS) { output ->
            output.writeBoundedString(JSONObject().put("key", config.key.trim()).put("model", config.model.trim()).toString(), MAX_BYTES)
        }
    }

    fun clear() = store.delete(file)

    companion object {
        const val DEFAULT_MODEL = "gpt-5-mini"
        private const val DIRECTORY = "repli_openai_key"
        private const val FILE = "key.bin"
        private const val KEY_ALIAS = "repli_openai_key_v1"
        private const val MAX_BYTES = 4 * 1024

        fun looksLikeKey(value: String): Boolean {
            val key = value.trim()
            return key.startsWith("sk-") && key.length in 20..400 && key.none(Char::isWhitespace)
        }
    }
}
