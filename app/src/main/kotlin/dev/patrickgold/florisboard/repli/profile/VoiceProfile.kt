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

package dev.patrickgold.florisboard.repli.profile

data class VoiceProfile(
    val id: String,
    val name: String,
    val relationship: String,
    val style: VoiceStyle,
    val personaId: String = style.name.lowercase(),
    val nameAliases: List<String> = emptyList(),
)

enum class VoiceStyle(val displayName: String) {
    CASUAL("Casual"),
    WARM("Warm"),
    DIRECT("Direct"),
}
