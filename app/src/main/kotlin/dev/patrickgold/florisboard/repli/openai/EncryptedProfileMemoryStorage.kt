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

/**
 * Keystore-encrypted, no-backup storage for per-profile chat memory. Replaces the backend's
 * encrypted memory files: the same bounded turn list, now only ever on this phone.
 */
class EncryptedProfileMemoryStorage(context: Context) : ProfileMemoryStorage {
    private val store = EncryptedFileStore(File(context.noBackupFilesDir, DIRECTORY_NAME))

    override fun read(profileId: String): ByteArray? =
        store.read(store.file(fileName(profileId)), KEY_ALIAS) { input ->
            input.readBoundedString(ProfileMemory.MAX_BYTES).toByteArray(Charsets.UTF_8)
        }

    override fun write(profileId: String, bytes: ByteArray) {
        store.write(store.file(fileName(profileId)), KEY_ALIAS) { output ->
            output.writeBoundedString(String(bytes, Charsets.UTF_8), ProfileMemory.MAX_BYTES)
        }
    }

    override fun delete(profileId: String) {
        store.delete(store.file(fileName(profileId)))
    }

    /** Removes every profile's memory, for sign-out and the "forget everything" control. */
    fun deleteAll(context: Context) {
        File(context.noBackupFilesDir, DIRECTORY_NAME).listFiles()?.forEach { it.delete() }
    }

    private fun fileName(profileId: String): String {
        require(PROFILE_ID_PATTERN.matches(profileId)) { "Invalid profile ID" }
        return "$profileId.bin"
    }

    companion object {
        const val DIRECTORY_NAME = "repli_profile_memory"
        private const val KEY_ALIAS = "repli_profile_memory_v1"
        private val PROFILE_ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    }
}
