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

package dev.patrickgold.florisboard.repli.capture

/** One bounded sequence of cropped chat images held until automatic AI reading begins.
 * Images remain in the exact oldest-to-newest order captured by the user.
 * Every terminal path overwrites all PNG bytes. */
@ConsistentCopyVisibility
data class PendingVisionCapture internal constructor(
    val requestId: String,
    val images: List<ByteArray>,
    val baseTurns: List<ConversationTurn>,
    val localTurns: List<ConversationTurn>,
    val contactName: String? = null,
) {
    fun eraseImages() = images.forEach { it.fill(0) }
}

object PendingVisionCaptureStore {
    private var pending: PendingVisionCapture? = null

    @Synchronized
    fun put(value: PendingVisionCapture) {
        pending?.eraseImages()
        pending = value
    }

    @Synchronized
    fun peek(requestId: String): PendingVisionCapture? = pending?.takeIf { it.requestId == requestId }

    @Synchronized
    fun take(requestId: String): PendingVisionCapture? {
        val value = pending?.takeIf { it.requestId == requestId } ?: return null
        pending = null
        return value
    }

    @Synchronized
    fun discard(requestId: String? = null) {
        val value = pending ?: return
        if (requestId != null && value.requestId != requestId) return
        pending = null
        value.eraseImages()
    }
}
