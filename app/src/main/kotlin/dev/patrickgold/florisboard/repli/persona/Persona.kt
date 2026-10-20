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

package dev.patrickgold.florisboard.repli.persona

import dev.patrickgold.florisboard.repli.profile.VoiceStyle

data class Persona(
    val id: String,
    val name: String,
    val description: String,
    val examples: List<String>,
    val baseStyle: VoiceStyle,
    val builtIn: Boolean = false,
)

object BuiltInPersonas {
    val all = listOf(
        Persona("casual", "Easy Breezy", "Relaxed, natural, and conversational. Keep it easy to say out loud.", emptyList(), VoiceStyle.CASUAL, true),
        Persona("warm", "The Soft Spot", "Kind and attentive. Show care without sounding scripted or overly formal.", emptyList(), VoiceStyle.WARM, true),
        Persona("direct", "Straight Talk", "Clear, brief, and honest. Get to the point without sounding cold.", emptyList(), VoiceStyle.DIRECT, true),
        Persona("flirty", "The Spark", "Playful and lightly flirty. Keep it respectful and match the other person's energy.", emptyList(), VoiceStyle.CASUAL, true),
        Persona("buddies", "The Sidekick", "Sound like a close friend: easy banter, supportive, and never stiff.", emptyList(), VoiceStyle.CASUAL, true),
        Persona("romantic", "Heart Notes", "Affectionate and sincere. Choose gentle, personal words without overdoing it.", emptyList(), VoiceStyle.WARM, true),
        Persona("professional", "The Pro", "Polished, helpful, and concise. Be friendly while keeping a professional boundary.", emptyList(), VoiceStyle.DIRECT, true),
    )

    fun get(id: String): Persona? = all.firstOrNull { it.id == id }
}
