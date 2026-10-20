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
import dev.patrickgold.florisboard.repli.persona.Persona
import dev.patrickgold.florisboard.repli.identity.CapturedContactName
import dev.patrickgold.florisboard.repli.profile.VoiceProfile
import dev.patrickgold.florisboard.repli.profile.VoiceStyle
import java.util.UUID

class ProfileRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val learnedStyleRepository = LearnedStyleRepository(context)

    fun profiles(): List<VoiceProfile> =
        preferences.getStringSet(KEY_PROFILES, null)
            ?.mapNotNull(VoiceProfileCodec::decode)
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun add(name: String, relationship: String, style: VoiceStyle): VoiceProfile {
        val profile = VoiceProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            relationship = relationship.trim().ifBlank { "Other" },
            style = style,
        )
        save(profiles() + profile)
        return profile
    }

    fun remove(id: String) {
        learnedStyleRepository.delete(id)
        save(profiles().filterNot { it.id == id })
    }

    fun clear() {
        profiles().forEach { learnedStyleRepository.delete(it.id) }
        preferences.edit().clear().apply()
    }

    fun updateStyle(id: String, style: VoiceStyle): VoiceProfile? {
        val current = profiles()
        val updated = current.firstOrNull { it.id == id }?.copy(style = style) ?: return null
        save(current.map { if (it.id == id) updated else it })
        return updated
    }

    fun updatePersona(id: String, persona: Persona): VoiceProfile? {
        val current = profiles()
        val updated = current.firstOrNull { it.id == id }
            ?.copy(style = persona.baseStyle, personaId = persona.id) ?: return null
        save(current.map { if (it.id == id) updated else it })
        return updated
    }

    /** Keeps the profile ID (and its cloud memory) while retaining previous OCR spellings. */
    fun rename(id: String, name: String): VoiceProfile? {
        val normalized = CapturedContactName.prepare(name) ?: return null
        val current = profiles()
        val profile = current.firstOrNull { it.id == id } ?: return null
        val key = CapturedContactName.identityKey(normalized)
        if (current.any { other -> other.id != id &&
                (listOf(other.name) + other.nameAliases).any {
                    CapturedContactName.identityKey(it) == key
                }
            }) return null
        val aliases = (profile.nameAliases + profile.name)
            .filter { CapturedContactName.identityKey(it) != key }
            .distinctBy(CapturedContactName::identityKey)
            .takeLast(8)
        val updated = profile.copy(name = normalized, nameAliases = aliases)
        save(current.map { if (it.id == id) updated else it })
        return updated
    }

    fun findByName(name: String): VoiceProfile? {
        val key = CapturedContactName.identityKey(name)
        return profiles().singleOrNull { profile ->
            (listOf(profile.name) + profile.nameAliases).any {
                CapturedContactName.identityKey(it) == key
            }
        }
    }

    fun findById(id: String): VoiceProfile? = profiles().firstOrNull { it.id == id }

    private fun save(profiles: List<VoiceProfile>) {
        preferences.edit().putStringSet(KEY_PROFILES, profiles.map(VoiceProfileCodec::encode).toSet()).apply()
    }

    private companion object {
        const val PREFERENCES = "voice_profiles"
        const val KEY_PROFILES = "profiles"
    }
}
