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

import android.content.Context
import dev.patrickgold.florisboard.ime.nlp.latin.repli.EncryptedFileStore
import dev.patrickgold.florisboard.ime.nlp.latin.repli.readBoundedString
import dev.patrickgold.florisboard.ime.nlp.latin.repli.writeBoundedString
import dev.patrickgold.florisboard.repli.profile.VoiceStyle
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** User-authored persona guides stay on this device until selected for a cloud reply. */
class PersonaRepository(context: Context) {
    private val store = EncryptedFileStore(File(context.noBackupFilesDir, "repli_personas"))
    private val file = store.file("personas.enc")

    fun personas(): List<Persona> {
        val saved = stored().associateBy(Persona::id)
        return BuiltInPersonas.all.map { saved[it.id]?.copy(builtIn = true) ?: it } +
            saved.values.filterNot { BuiltInPersonas.get(it.id) != null }.sortedBy { it.name.lowercase() }
    }

    fun get(id: String): Persona? = personas().firstOrNull { it.id == id }

    fun save(id: String?, name: String, description: String, examples: List<String>,
             baseStyle: VoiceStyle = VoiceStyle.CASUAL): Persona {
        val cleanName = name.trim()
        val cleanDescription = description.trim()
        val cleanExamples = examples.map(String::trim).filter(String::isNotEmpty)
        require(cleanName.length in 1..50) { "Persona name must be 1–50 characters" }
        require(cleanDescription.length in 1..500) { "Describe the persona in 1–500 characters" }
        require(cleanExamples.size <= 3 && cleanExamples.all { it.length <= 280 }) {
            "Add up to 3 example replies, each under 280 characters"
        }
        val resolvedId = id ?: UUID.randomUUID().toString()
        val existing = personas().firstOrNull { it.id == resolvedId }
        require(id == null || existing != null) { "Persona was removed" }
        val persona = Persona(resolvedId, cleanName, cleanDescription, cleanExamples,
            existing?.baseStyle ?: baseStyle, existing?.builtIn == true)
        val saved = stored().filterNot { it.id == resolvedId } + persona
        persist(saved)
        return persona
    }

    fun remove(id: String) {
        require(BuiltInPersonas.get(id) == null) { "Built-in personas cannot be removed" }
        persist(stored().filterNot { it.id == id })
    }

    private fun stored(): List<Persona> = store.read(file, KEY_ALIAS) { input ->
        require(input.readInt() == FILE_VERSION)
        val count = input.readInt().also { require(it in 0..100) }
        List(count) { decode(JSONObject(input.readBoundedString(8_192))) }
    }.orEmpty()

    private fun persist(personas: List<Persona>) {
        require(personas.size <= 100) { "Too many personas" }
        store.write(file, KEY_ALIAS) { output ->
            output.writeInt(FILE_VERSION)
            output.writeInt(personas.size)
            personas.forEach { output.writeBoundedString(encode(it).toString(), 8_192) }
        }
    }

    private fun encode(persona: Persona) = JSONObject().apply {
        put("id", persona.id)
        put("name", persona.name)
        put("description", persona.description)
        put("base_style", persona.baseStyle.name)
        put("examples", JSONArray(persona.examples))
    }

    private fun decode(json: JSONObject): Persona {
        val examples = json.getJSONArray("examples")
        return Persona(
            id = json.getString("id"),
            name = json.getString("name"),
            description = json.getString("description"),
            examples = List(examples.length()) { examples.getString(it) },
            baseStyle = VoiceStyle.valueOf(json.getString("base_style")),
            builtIn = BuiltInPersonas.get(json.getString("id")) != null,
        )
    }

    private companion object {
        const val FILE_VERSION = 1
        const val KEY_ALIAS = "repli_personas_v1"
    }
}
