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

import android.content.Context
import dev.patrickgold.florisboard.ime.nlp.latin.repli.AdaptiveCount
import dev.patrickgold.florisboard.ime.nlp.latin.repli.AdaptiveLanguageModel
import dev.patrickgold.florisboard.ime.nlp.latin.repli.AdaptiveLanguageSnapshot
import dev.patrickgold.florisboard.ime.nlp.latin.repli.AdaptiveTransition
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Keystore-encrypted, no-backup storage for the bounded keyboard language model. */
class AdaptiveLanguageRepository(context: Context) {
    private val store = EncryptedFileStore(File(context.noBackupFilesDir, DIRECTORY_NAME))
    private val target = store.file(FILE_NAME)

    fun load(): AdaptiveLanguageModel = store.read(target, KEY_ALIAS, ::readSnapshot)
        ?.let(AdaptiveLanguageModel::from)
        ?: AdaptiveLanguageModel()

    fun save(model: AdaptiveLanguageModel) {
        val snapshot = model.snapshot()
        store.write(target, KEY_ALIAS) { output -> writeSnapshot(output, snapshot) }
    }

    fun clear() = store.delete(target)

    private fun writeSnapshot(output: DataOutputStream, snapshot: AdaptiveLanguageSnapshot) {
        output.writeInt(FILE_VERSION)
        output.writeLong(snapshot.sequence)
        output.writeInt(snapshot.words.size)
        snapshot.words.forEach { item ->
            output.writeBoundedString(item.value, MAX_WORD_BYTES)
            output.writeInt(item.count)
            output.writeLong(item.lastUsed)
        }
        writeTransitions(output, snapshot.bigrams)
        writeTransitions(output, snapshot.trigrams)
        output.writeInt(snapshot.recentEmojis.size)
        snapshot.recentEmojis.forEach { output.writeBoundedString(it, MAX_EMOJI_BYTES) }
    }

    private fun writeTransitions(output: DataOutputStream, transitions: List<AdaptiveTransition>) {
        output.writeInt(transitions.size)
        transitions.forEach { item ->
            output.writeBoundedString(item.context, MAX_CONTEXT_BYTES)
            output.writeBoundedString(item.nextWord, MAX_WORD_BYTES)
            output.writeInt(item.count)
            output.writeLong(item.lastUsed)
        }
    }

    private fun readSnapshot(input: DataInputStream): AdaptiveLanguageSnapshot {
        require(input.readInt() == FILE_VERSION) { "Unsupported adaptive language model" }
        val sequence = input.readLong().also { require(it >= 0) }
        val words = List(input.readInt().also { require(it in 0..AdaptiveLanguageModel.MAX_WORDS) }) {
            AdaptiveCount(input.readBoundedString(MAX_WORD_BYTES), input.readInt(), input.readLong())
        }
        val bigrams = readTransitions(input, AdaptiveLanguageModel.MAX_BIGRAMS)
        val trigrams = readTransitions(input, AdaptiveLanguageModel.MAX_TRIGRAMS)
        val emojis = List(input.readInt().also { require(it in 0..AdaptiveLanguageModel.MAX_RECENT_EMOJIS) }) {
            input.readBoundedString(MAX_EMOJI_BYTES)
        }
        return AdaptiveLanguageSnapshot(sequence, words, bigrams, trigrams, emojis)
    }

    private fun readTransitions(input: DataInputStream, maximum: Int): List<AdaptiveTransition> =
        List(input.readInt().also { require(it in 0..maximum) }) {
            AdaptiveTransition(
                context = input.readBoundedString(MAX_CONTEXT_BYTES),
                nextWord = input.readBoundedString(MAX_WORD_BYTES),
                count = input.readInt(),
                lastUsed = input.readLong(),
            )
        }

    private companion object {
        const val DIRECTORY_NAME = "adaptive_keyboard"
        const val FILE_NAME = "language_model.bin"
        const val KEY_ALIAS = "repli_keyboard_model_key_v1"
        const val FILE_VERSION = 1
        const val MAX_WORD_BYTES = 128
        const val MAX_CONTEXT_BYTES = 260
        const val MAX_EMOJI_BYTES = 128
    }
}
