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

package dev.patrickgold.florisboard.repli.data

import android.content.Context
import dev.patrickgold.florisboard.ime.nlp.latin.repli.EncryptedFileStore
import dev.patrickgold.florisboard.ime.nlp.latin.repli.readBoundedString
import dev.patrickgold.florisboard.ime.nlp.latin.repli.writeBoundedString
import dev.patrickgold.florisboard.repli.profile.RecentMessage
import java.io.DataInputStream
import java.io.File

/** Stores a bounded set of recent incoming conversations, encrypted at rest.
 * Replaces the original plaintext SharedPreferences storage; any legacy plaintext
 * preferences are wiped on first use so no message text remains unencrypted. */
class RecentMessageRepository(context: Context) {
    private val store = EncryptedFileStore(File(context.noBackupFilesDir, DIRECTORY_NAME))
    private val legacyPreferences = context.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)

    init {
        if (legacyPreferences.all.isNotEmpty()) {
            legacyPreferences.edit().clear().apply()
        }
    }

    fun save(message: RecentMessage) {
        synchronized(repositoryLock) {
            val normalized = message.copy(
                sourcePackage = keyOf(message.sourcePackage).takeUtf8Bytes(MAX_PACKAGE_BYTES),
                sender = message.sender.trim().takeUtf8Bytes(MAX_SENDER_BYTES),
                text = message.text.takeUtf8Bytes(MAX_TEXT_BYTES),
                notificationKey = message.notificationKey?.trim()?.takeUtf8Bytes(MAX_NOTIFICATION_KEY_BYTES)
                    ?.takeIf(String::isNotBlank),
                conversationId = message.conversationId?.trim()?.takeUtf8Bytes(MAX_CONVERSATION_ID_BYTES)
                    ?.takeIf(String::isNotBlank),
            )
            val updated = (readAll().filterNot { existing ->
                normalized.notificationKey != null && existing.notificationKey == normalized.notificationKey
            } + normalized).sortedByDescending(RecentMessage::receivedAt).take(MAX_MESSAGES)
            writeAll(updated)
        }
    }

    /** Records the strongest signal available without inspecting another app's UI:
     * Android reports that the user opened this exact notification. */
    fun markOpened(notificationKey: String?, openedAt: Long) {
        val key = notificationKey?.trim()?.takeIf(String::isNotBlank) ?: return
        synchronized(repositoryLock) {
            val messages = readAll()
            if (messages.none { it.notificationKey == key }) return
            writeAll(messages.map { message ->
                if (message.notificationKey == key) message.copy(openedAt = openedAt) else message
            })
        }
    }

    fun recentFor(sourcePackage: String?): List<RecentMessage> {
        if (sourcePackage.isNullOrBlank()) return emptyList()
        val packageKey = keyOf(sourcePackage)
        return readAll().filter { it.sourcePackage == packageKey }.sortedByDescending(RecentMessage::receivedAt)
    }

    fun latestFor(sourcePackage: String?): RecentMessage? = recentFor(sourcePackage).firstOrNull()

    fun clear() {
        synchronized(repositoryLock) {
            store.delete(store.file(FILE_NAME))
            legacyPreferences.edit().clear().apply()
        }
    }

    private fun writeAll(messages: List<RecentMessage>) {
        store.write(store.file(FILE_NAME), KEY_ALIAS) { output ->
            output.writeInt(FILE_VERSION)
            output.writeInt(messages.size)
            messages.forEach { entry ->
                output.writeBoundedString(entry.sourcePackage, MAX_PACKAGE_BYTES)
                output.writeBoundedString(entry.sender, MAX_SENDER_BYTES)
                output.writeBoundedString(entry.text, MAX_TEXT_BYTES)
                output.writeLong(entry.receivedAt)
                output.writeBoundedString(entry.notificationKey.orEmpty(), MAX_NOTIFICATION_KEY_BYTES)
                output.writeBoundedString(entry.conversationId.orEmpty(), MAX_CONVERSATION_ID_BYTES)
                output.writeBoolean(entry.isGroupConversation)
                output.writeLong(entry.openedAt ?: NOT_OPENED)
            }
        }
    }

    private fun readAll(): List<RecentMessage> =
        store.read(store.file(FILE_NAME), KEY_ALIAS) { input ->
            when (val version = input.readInt()) {
                LEGACY_FILE_VERSION -> readLegacyMessages(input)
                FILE_VERSION -> readCurrentMessages(input)
                else -> error("Unsupported recent-message version $version")
            }
        } ?: emptyList()

    private fun readLegacyMessages(input: DataInputStream): List<RecentMessage> {
        val count = input.readInt().also { require(it in 0..MAX_LEGACY_PACKAGES) }
        return List(count) {
            RecentMessage(
                sourcePackage = input.readBoundedString(MAX_PACKAGE_BYTES),
                sender = input.readBoundedString(MAX_SENDER_BYTES),
                text = input.readBoundedString(MAX_TEXT_BYTES),
                receivedAt = input.readLong(),
            )
        }
    }

    private fun readCurrentMessages(input: DataInputStream): List<RecentMessage> {
        val count = input.readInt().also { require(it in 0..MAX_MESSAGES) }
        return List(count) {
            RecentMessage(
                sourcePackage = input.readBoundedString(MAX_PACKAGE_BYTES),
                sender = input.readBoundedString(MAX_SENDER_BYTES),
                text = input.readBoundedString(MAX_TEXT_BYTES),
                receivedAt = input.readLong(),
                notificationKey = input.readBoundedString(MAX_NOTIFICATION_KEY_BYTES).takeIf(String::isNotBlank),
                conversationId = input.readBoundedString(MAX_CONVERSATION_ID_BYTES).takeIf(String::isNotBlank),
                isGroupConversation = input.readBoolean(),
                openedAt = input.readLong().takeUnless { it == NOT_OPENED },
            )
        }
    }

    private fun keyOf(sourcePackage: String): String = sourcePackage.lowercase().trim()

    private companion object {
        const val DIRECTORY_NAME = "recent_message_context"
        const val LEGACY_PREFERENCES = "recent_message_context"
        const val FILE_NAME = "latest_messages.bin"
        const val KEY_ALIAS = "assisted_recent_message_key_v1"
        const val LEGACY_FILE_VERSION = 1
        const val FILE_VERSION = 2
        const val MAX_LEGACY_PACKAGES = 32
        const val MAX_MESSAGES = 64
        const val MAX_PACKAGE_BYTES = 128
        const val MAX_SENDER_BYTES = 512
        const val MAX_TEXT_BYTES = 4_000
        const val MAX_NOTIFICATION_KEY_BYTES = 1_024
        const val MAX_CONVERSATION_ID_BYTES = 512
        const val NOT_OPENED = -1L
        val repositoryLock = Any()
    }
}
