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

package dev.patrickgold.florisboard.repli.review

import dev.patrickgold.florisboard.repli.capture.ConversationTurn
import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyPrivacyPolicy

/** Local corrections are never uploaded until a fresh approval payload is prepared. */
internal class ContextReviewEdits(initial: List<ConversationTurn>, initiallyChanged: Boolean = false) {
    private val mutable = initial.toMutableList()
    val turns: List<ConversationTurn> get() = mutable.toList()
    var changed: Boolean = initiallyChanged
        private set

    fun edit(index: Int, text: String): Boolean {
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        if (index !in mutable.indices || cleaned.isEmpty() || cleaned.length > 1_000) return false
        if (mutable[index].text != cleaned) {
            mutable[index] = mutable[index].copy(text = cleaned)
            changed = true
        }
        return true
    }

    fun flipSpeaker(index: Int): Boolean {
        if (index !in mutable.indices) return false
        mutable[index] = mutable[index].copy(fromMe = !mutable[index].fromMe)
        changed = true
        return true
    }

    fun remove(index: Int): Boolean {
        if (index !in mutable.indices || mutable.size <= 1) return false
        mutable.removeAt(index)
        changed = true
        return true
    }

    fun insertAfter(index: Int, text: String, fromMe: Boolean): Boolean {
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        if (index !in mutable.indices || mutable.size >= RemoteReplyPrivacyPolicy.MAX_CONTEXT_TURNS ||
            cleaned.isEmpty() || cleaned.length > 1_000) return false
        mutable.add(index + 1, ConversationTurn(cleaned, fromMe))
        changed = true
        return true
    }

    fun mergeWithPrevious(index: Int): Boolean {
        if (index !in 1 until mutable.size) return false
        val previous = mutable[index - 1]
        val current = mutable[index]
        if (previous.fromMe != current.fromMe || previous.text.length + current.text.length + 1 > 1_000) return false
        // A combined turn spans two bubbles, so neither original crop represents it.
        mutable[index - 1] = previous.copy(text = "${previous.text} ${current.text}", source = null)
        mutable.removeAt(index)
        changed = true
        return true
    }
}
