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

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Request-scoped, memory-only screenshots for the user's optional source-crop check.
 * They are never written to disk or included in the reply-generation request. */
object ReviewEvidenceStore {
    private var requestId: String? = null
    private var images: List<ByteArray> = emptyList()

    @Synchronized
    fun put(id: String, sourceImages: List<ByteArray>) {
        discard()
        requestId = id
        images = sourceImages.map(ByteArray::clone)
    }

    @Synchronized
    fun discard(id: String? = null) {
        if (id != null && requestId != id) return
        images.forEach { it.fill(0) }
        images = emptyList()
        requestId = null
    }

    @Synchronized
    fun crop(id: String, source: TurnSource): Bitmap? {
        if (id != requestId) return null
        val image = images.getOrNull(source.frameIndex) ?: return null
        val full = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return null
        return try {
            val left = (full.width * source.left / 1000 - 16).coerceIn(0, full.width - 1)
            val top = (full.height * source.top / 1000 - 16).coerceIn(0, full.height - 1)
            val right = (full.width * source.right / 1000 + 16).coerceIn(left + 1, full.width)
            val bottom = (full.height * source.bottom / 1000 + 16).coerceIn(top + 1, full.height)
            val crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
            // Bitmap.createBitmap may return its input for a full-image box.
            if (crop === full) full.copy(full.config ?: Bitmap.Config.ARGB_8888, false) else crop
        } finally {
            full.recycle()
        }
    }
}
