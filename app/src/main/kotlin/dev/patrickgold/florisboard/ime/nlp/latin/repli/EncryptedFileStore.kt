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

package dev.patrickgold.florisboard.ime.nlp.latin.repli

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Shared Keystore-backed AES-GCM storage for every piece of retained message content.
 * File layout: [iv length byte][iv][AES-GCM ciphertext of the caller's payload].
 * Writes go to a temporary file first and replace the target with an atomic move,
 * so concurrent readers only ever observe a complete previous or next version. */
class EncryptedFileStore(private val directory: File) {
    init {
        directory.mkdirs()
    }

    fun file(name: String): File = File(directory, sanitize(name))

    fun write(target: File, keyAlias: String, payload: (DataOutputStream) -> Unit) {
        synchronized(writeLock) {
            val temporary = File(directory, "${target.name}.tmp")
            try {
                BufferedOutputStream(FileOutputStream(temporary)).use { rawOutput ->
                    val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                        init(Cipher.ENCRYPT_MODE, encryptionKey(keyAlias))
                    }
                    rawOutput.write(cipher.iv.size)
                    rawOutput.write(cipher.iv)
                    DataOutputStream(CipherOutputStream(rawOutput, cipher)).use(payload)
                }
                moveReplacing(temporary, target)
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    fun <T> read(source: File, keyAlias: String, payload: (DataInputStream) -> T): T? {
        if (!source.exists()) return null
        return runCatching {
            BufferedInputStream(FileInputStream(source)).use { rawInput ->
                val ivLength = rawInput.read()
                require(ivLength in MIN_IV_BYTES..MAX_IV_BYTES) { "Invalid encrypted header" }
                val iv = ByteArray(ivLength)
                DataInputStream(rawInput).readFully(iv)
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, encryptionKey(keyAlias), GCMParameterSpec(TAG_BITS, iv))
                }
                DataInputStream(CipherInputStream(rawInput, cipher)).use(payload)
            }
        }.getOrNull()
    }

    fun delete(target: File) {
        target.delete()
    }

    private fun encryptionKey(keyAlias: String): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 32
        val writeLock = Any()
    }
}

fun DataOutput.writeBoundedString(value: String, maxBytes: Int = MAX_STORED_STRING_BYTES) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= maxBytes) { "Stored string is too large" }
    writeInt(bytes.size)
    write(bytes)
}

fun DataInput.readBoundedString(maxBytes: Int): String {
    val length = readInt().also { require(it in 0..maxBytes) }
    return ByteArray(length).also(::readFully).toString(Charsets.UTF_8)
}

const val MAX_STORED_STRING_BYTES = 1_000_000
