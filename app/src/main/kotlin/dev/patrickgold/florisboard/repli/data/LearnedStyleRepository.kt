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
import dev.patrickgold.florisboard.ime.nlp.latin.repli.MAX_STORED_STRING_BYTES
import dev.patrickgold.florisboard.ime.nlp.latin.repli.readBoundedString
import dev.patrickgold.florisboard.ime.nlp.latin.repli.writeBoundedString
import dev.patrickgold.florisboard.repli.profile.LearnedStyleSnapshot
import dev.patrickgold.florisboard.repli.profile.LearnedTextingStyle
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class LearnedStyleRepository(context: Context) {
    private val store = EncryptedFileStore(File(context.noBackupFilesDir, DIRECTORY_NAME))

    fun save(profileId: String, snapshot: LearnedStyleSnapshot) {
        val target = fileFor(profileId)
        store.write(target, KEY_ALIAS) { output ->
            writeSnapshot(output, snapshot)
        }
        cacheStyle(target, snapshot.style)
    }

    fun get(profileId: String): LearnedStyleSnapshot? =
        store.read(fileFor(profileId), KEY_ALIAS, ::readSnapshot)

    /** Returns only the compact style model and caches it by encrypted-file stamp.
     * The first load still authenticates the complete AES-GCM payload, but later
     * keyboard openings avoid decrypting and rebuilding all retained examples. */
    fun getStyle(profileId: String): LearnedTextingStyle? {
        val source = fileFor(profileId)
        if (!source.exists()) {
            synchronized(styleCache) { styleCache.remove(source.absolutePath) }
            return null
        }
        val stampBefore = source.stamp()
        synchronized(styleCache) {
            styleCache[source.absolutePath]?.takeIf { it.stamp == stampBefore }?.let { return it.style }
        }

        val style = get(profileId)?.style ?: return null
        val stampAfter = source.stamp()
        if (stampAfter == stampBefore) cacheStyle(source, style)
        return style
    }

    fun delete(profileId: String) {
        val target = fileFor(profileId)
        store.delete(target)
        synchronized(styleCache) { styleCache.remove(target.absolutePath) }
    }

    private fun writeSnapshot(output: DataOutputStream, snapshot: LearnedStyleSnapshot) {
        val style = snapshot.style
        output.writeInt(FILE_VERSION)
        output.writeInt(style.messagesAnalyzed)
        output.writeDouble(style.averageWords)
        output.writeDouble(style.averageCharacters)
        output.writeDouble(style.lowercaseStartRatio)
        output.writeDouble(style.terminalPunctuationRatio)
        output.writeDouble(style.emojiMessageRatio)
        output.writeDouble(style.questionRatio)
        output.writeDouble(style.exclamationRatio)
        output.writeInt(style.preferredEmojis.size)
        style.preferredEmojis.forEach { output.writeBoundedString(it, MAX_EMOJI_BYTES) }
        output.writeInt(snapshot.outgoingExamples.size)
        snapshot.outgoingExamples.forEach { output.writeBoundedString(it) }
    }

    private fun readSnapshot(input: DataInputStream): LearnedStyleSnapshot {
        require(input.readInt() == FILE_VERSION) { "Unsupported encrypted style version" }
        val messagesAnalyzed = input.readInt().also { require(it in 1..MAX_MESSAGES) }
        val style = LearnedTextingStyle(
            messagesAnalyzed = messagesAnalyzed,
            averageWords = input.readDouble(),
            averageCharacters = input.readDouble(),
            lowercaseStartRatio = input.readDouble(),
            terminalPunctuationRatio = input.readDouble(),
            emojiMessageRatio = input.readDouble(),
            questionRatio = input.readDouble(),
            exclamationRatio = input.readDouble(),
            preferredEmojis = List(input.readInt().also { require(it in 0..MAX_EMOJIS) }) {
                input.readBoundedString(MAX_EMOJI_BYTES)
            },
        )
        val exampleCount = input.readInt().also { require(it in 0..MAX_MESSAGES) }
        val examples = List(exampleCount) { input.readBoundedString(MAX_STORED_STRING_BYTES) }
        return LearnedStyleSnapshot(style, examples)
    }

    private fun fileFor(profileId: String): File = store.file("$profileId.style")

    private fun cacheStyle(source: File, style: LearnedTextingStyle) {
        synchronized(styleCache) {
            styleCache[source.absolutePath] = CachedStyle(source.stamp(), style)
        }
    }

    private fun File.stamp() = FileStamp(lastModified(), length())

    private data class FileStamp(val lastModified: Long, val length: Long)
    private data class CachedStyle(val stamp: FileStamp, val style: LearnedTextingStyle)

    private companion object {
        const val DIRECTORY_NAME = "learned_texting_styles"
        const val KEY_ALIAS = "assisted_texting_style_key_v1"
        const val FILE_VERSION = 1
        const val MAX_MESSAGES = 100_000
        const val MAX_EMOJIS = 32
        const val MAX_EMOJI_BYTES = 64
        val styleCache = mutableMapOf<String, CachedStyle>()
    }
}
