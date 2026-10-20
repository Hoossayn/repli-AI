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

/** Returns a prefix whose UTF-8 representation fits [maxBytes] without splitting
 * a Unicode code point. Storage limits are byte limits, not UTF-16 character limits. */
fun String.takeUtf8Bytes(maxBytes: Int): String {
    require(maxBytes >= 0)
    if (toByteArray(Charsets.UTF_8).size <= maxBytes) return this

    val result = StringBuilder()
    var offset = 0
    var usedBytes = 0
    while (offset < length) {
        val codePoint = codePointAt(offset)
        val codePointText = String(Character.toChars(codePoint))
        val codePointBytes = codePointText.toByteArray(Charsets.UTF_8).size
        if (usedBytes + codePointBytes > maxBytes) break
        result.append(codePointText)
        usedBytes += codePointBytes
        offset += Character.charCount(codePoint)
    }
    return result.toString()
}
