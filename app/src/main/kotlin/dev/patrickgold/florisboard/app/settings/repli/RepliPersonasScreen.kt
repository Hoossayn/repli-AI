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

package dev.patrickgold.florisboard.app.settings.repli

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.repli.data.ProfileRepository
import dev.patrickgold.florisboard.repli.persona.BuiltInPersonas
import dev.patrickgold.florisboard.repli.persona.Persona
import dev.patrickgold.florisboard.repli.persona.PersonaRepository

@Composable
fun RepliPersonasScreen() {
    val context = LocalContext.current
    val repository = remember(context) { PersonaRepository(context) }
    val navController = LocalNavController.current
    var personas by remember { mutableStateOf(repository.personas()) }
    var editing by remember { mutableStateOf<Persona?>(null) }
    var creating by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Persona?>(null) }

    RepliPage {
        TextButton(onClick = { navController.popBackStack() }) { Text("‹  Chats", color = RepliStyle.accent) }
        RepliLabel("Personas", 27, RepliStyle.ink, bold = true)
        Spacer(Modifier.height(6.dp))
        RepliLabel("Pick a personality for each chat or for your next reply. Teach it with a guide and a few examples.",
            15, RepliStyle.muted)
        Spacer(Modifier.height(6.dp))
        RepliLabel("Your edits stay on this device until you generate a cloud reply with that persona.",
            12, RepliStyle.muted)
        Spacer(Modifier.height(18.dp))
        RepliAction("Create a persona", { creating = true })
        Spacer(Modifier.height(18.dp))
        personas.forEach { persona ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                color = RepliStyle.card, border = BorderStroke(1.dp, RepliStyle.line)) {
                Column(Modifier.padding(16.dp)) {
                    RepliLabel(persona.name, 17, RepliStyle.ink, bold = true)
                    Spacer(Modifier.height(5.dp))
                    RepliLabel(persona.description, 13, RepliStyle.muted)
                    if (persona.examples.isNotEmpty()) {
                        Spacer(Modifier.height(7.dp))
                        RepliLabel("${persona.examples.size} example ${if (persona.examples.size == 1) "reply" else "replies"}",
                            12, RepliStyle.accent)
                    }
                    Spacer(Modifier.height(12.dp))
                    RepliAction("Edit persona", { editing = persona }, filled = false)
                    if (!persona.builtIn) {
                        TextButton(onClick = { removing = persona }) { Text("Remove persona") }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    if (creating || editing != null) {
        PersonaEditorSheet(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { name, description, examples ->
                repository.save(editing?.id, name, description, examples)
                personas = repository.personas()
                creating = false
                editing = null
            },
        )
    }
    removing?.let { persona ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text("Remove ${persona.name}?") },
            text = { Text("Chats using this persona will use Easy Breezy until you choose another.") },
            confirmButton = { TextButton(onClick = {
                val chats = ProfileRepository(context)
                chats.profiles().filter { it.personaId == persona.id }.forEach {
                    chats.updatePersona(it.id, BuiltInPersonas.all.first())
                }
                repository.remove(persona.id)
                personas = repository.personas()
                removing = null
            }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } })
    }
}

@Composable
private fun PersonaEditorSheet(initial: Persona?, onDismiss: () -> Unit,
                                onSave: (String, String, List<String>) -> Unit) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var example1 by remember(initial?.id) { mutableStateOf(initial?.examples?.getOrNull(0).orEmpty()) }
    var example2 by remember(initial?.id) { mutableStateOf(initial?.examples?.getOrNull(1).orEmpty()) }
    var example3 by remember(initial?.id) { mutableStateOf(initial?.examples?.getOrNull(2).orEmpty()) }
    val valid = name.trim().isNotEmpty() && description.trim().isNotEmpty()
    RepliSheet(onDismiss = onDismiss) {
            RepliLabel(if (initial == null) "Create a persona" else "Teach ${initial.name}",
                23, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel("Describe how this persona should sound. Example replies help Repli match its rhythm, emoji, and punctuation.",
                14, RepliStyle.muted)
            Spacer(Modifier.height(14.dp))
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                RepliInput(name, { name = it }, "FUN NAME", "e.g. The Spark",
                    singleLine = true, maxLength = 50)
                Spacer(Modifier.height(14.dp))
                RepliInput(description, { description = it }, "THE VIBE", "How should replies sound?",
                    minLines = 3, maxLines = 6, maxLength = 500)
                Spacer(Modifier.height(14.dp))
                RepliLabel("SHOW REPLI WHAT YOU MEAN", 13, RepliStyle.accent, bold = true)
                Spacer(Modifier.height(8.dp))
                listOf(example1, example2, example3).forEachIndexed { index, value ->
                    RepliInput(value, { changed ->
                        when (index) {
                            0 -> example1 = changed
                            1 -> example2 = changed
                            else -> example3 = changed
                        }
                    }, label = "EXAMPLE ${index + 1}", placeholder = "Write a reply in this persona's voice…",
                        maxLines = 3, maxLength = 280)
                    Spacer(Modifier.height(14.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            RepliAction("Save persona", enabled = valid, onClick = {
                onSave(name, description, listOf(example1, example2, example3))
            })
            TextButton(onClick = onDismiss) { Text("Cancel", color = RepliStyle.muted) }
            Spacer(Modifier.height(12.dp))
    }
}
