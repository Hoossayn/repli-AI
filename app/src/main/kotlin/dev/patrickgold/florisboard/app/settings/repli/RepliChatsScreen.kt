package dev.patrickgold.florisboard.app.settings.repli

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.repli.data.LearnedStyleRepository
import dev.patrickgold.florisboard.repli.openai.RepliOpenAiAccess
import dev.patrickgold.florisboard.repli.data.ProfileRepository
import dev.patrickgold.florisboard.repli.identity.CapturedContactName
import dev.patrickgold.florisboard.repli.profile.VoiceProfile
import dev.patrickgold.florisboard.repli.persona.Persona
import dev.patrickgold.florisboard.repli.persona.PersonaRepository
import kotlinx.coroutines.launch

@Composable
fun RepliChatsScreen() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val repository = remember(context) { ProfileRepository(context) }
    val personaRepository = remember(context) { PersonaRepository(context) }
    val learned = remember(context) { LearnedStyleRepository(context) }
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(repository.profiles()) }
    var personas by remember { mutableStateOf(personaRepository.personas()) }
    var styleRevision by remember { mutableIntStateOf(0) }
    var addSheet by remember { mutableStateOf(false) }
    var removeProfile by remember { mutableStateOf<VoiceProfile?>(null) }
    var removeError by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    var personaProfile by remember { mutableStateOf<VoiceProfile?>(null) }
    var editNameProfile by remember { mutableStateOf<VoiceProfile?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                profiles = repository.profiles()
                personas = personaRepository.personas()
                styleRevision++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    RepliPage {
        RepliLabel("Your chats", 27, RepliStyle.ink, bold = true)
        Spacer(Modifier.height(6.dp))
        RepliLabel("A different persona for every conversation.", 15, RepliStyle.muted)
        Spacer(Modifier.height(20.dp))
        RepliAction("Add a chat", { addSheet = true })
        Spacer(Modifier.height(9.dp))
        RepliAction("Manage personas", { navController.navigate(Routes.Settings.RepliPersonas) }, filled = false)
        Spacer(Modifier.height(16.dp))
        if (profiles.isEmpty()) {
            RepliCard {
                RepliLabel("No saved chats yet", 19, RepliStyle.ink, bold = true)
                Spacer(Modifier.height(8.dp))
                RepliLabel("Capture a one-to-one chat and Repli will save its name here automatically. You can also add a chat yourself, or get replies without saving one.",
                    14, RepliStyle.muted)
            }
        } else {
            profiles.forEach { profile ->
                val style = remember(profile.id, styleRevision) { learned.getStyle(profile.id) }
                val persona = personas.firstOrNull { it.id == profile.personaId } ?: personas.first()
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    color = RepliStyle.card, border = BorderStroke(1.dp, RepliStyle.line)) {
                    Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 10.dp, bottom = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                RepliLabel(profile.name, 16, RepliStyle.ink, bold = true)
                                RepliLabel(if (style == null) persona.name
                                    else "${persona.name} · ${style.messagesAnalyzed} writing examples",
                                    13, RepliStyle.muted)
                            }
                            IconButton(onClick = { editNameProfile = profile }) {
                                Icon(painterResource(R.drawable.ic_repli_edit_name),
                                    contentDescription = "Edit ${profile.name} name",
                                    modifier = Modifier.size(22.dp), tint = RepliStyle.accent)
                            }
                            IconButton(onClick = { removeError = null; removeProfile = profile }) {
                                Icon(painterResource(R.drawable.ic_repli_remove_chat),
                                    contentDescription = "Remove ${profile.name}",
                                    modifier = Modifier.size(22.dp), tint = RepliStyle.accent)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        RepliAction("Choose persona", { personaProfile = profile }, filled = false)
                        if (style != null) {
                            Spacer(Modifier.height(10.dp))
                            RepliLabel(style.summary(), 13, RepliStyle.ink)
                            Spacer(Modifier.height(10.dp))
                            RepliAction("Forget local writing examples", {
                                learned.delete(profile.id)
                                styleRevision++
                            }, filled = false)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        RepliCard {
            RepliLabel("Your style and chat memory", 17, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel("Cloud replies remember approved chat messages by saved chat, including your outgoing style. You can also teach Repli with your own messages while you scroll a chat.",
                14, RepliStyle.muted)
            Spacer(Modifier.height(14.dp))
            RepliAction("Learn from visible messages", {
                navController.navigate(Routes.Settings.LearnByScrolling)
            }, filled = false)
            Spacer(Modifier.height(10.dp))
            RepliLabel("On-device recognition · no screenshots saved · no automatic upload", 12,
                RepliStyle.muted)
        }
    }

    if (addSheet) AddChatSheet(repository, personas,
        onSaved = { profiles = repository.profiles(); addSheet = false },
        onDismiss = { addSheet = false })
    editNameProfile?.let { profile ->
        EditChatNameSheet(profile, repository,
            onSaved = { profiles = repository.profiles(); editNameProfile = null },
            onDismiss = { editNameProfile = null })
    }
    removeProfile?.let { profile ->
        AlertDialog(onDismissRequest = { removeProfile = null },
            title = { Text("Remove ${profile.name}?") },
            text = { Text(removeError ?: "This removes the chat, its writing examples and its saved chat memory from this device.") },
            confirmButton = { TextButton(enabled = !removing, onClick = {
                removing = true
                scope.launch {
                    try {
                        RepliOpenAiAccess.memory().forget(profile.id)
                        repository.remove(profile.id)
                        profiles = repository.profiles()
                        removeProfile = null
                        removeError = null
                    } catch (error: Exception) {
                        removeError = error.message ?: "Could not remove this chat. Try again."
                    } finally {
                        removing = false
                    }
                }
            }) { Text(if (removing) "Removing…" else "Remove") } },
            dismissButton = { TextButton(onClick = { removeProfile = null }) { Text("Keep") } })
    }
    personaProfile?.let { profile ->
        RepliSheet(onDismiss = { personaProfile = null }) {
            RepliLabel("Persona for ${profile.name}", 23, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(6.dp))
            RepliLabel("Choose how Repli should sound in this chat.", 14, RepliStyle.muted)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                personas.forEach { persona ->
                    PersonaChoice(persona, profile.personaId == persona.id) {
                        repository.updatePersona(profile.id, persona)
                        profiles = repository.profiles()
                        personaProfile = null
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { personaProfile = null }) { Text("Cancel", color = RepliStyle.muted) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EditChatNameSheet(profile: VoiceProfile, repository: ProfileRepository,
    onSaved: () -> Unit, onDismiss: () -> Unit) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var error by remember(profile.id) { mutableStateOf<String?>(null) }
    RepliSheet(onDismiss = onDismiss) {
        RepliLabel("Edit chat name", 23, RepliStyle.ink, bold = true)
        Spacer(Modifier.height(6.dp))
        RepliLabel("Correct the name Repli read from this chat. Its replies and saved history stay with this chat.",
            14, RepliStyle.muted)
        Spacer(Modifier.height(18.dp))
        RepliInput(name, { name = it; error = null }, "CHAT NAME", "e.g. Alex",
            singleLine = true, maxLength = CapturedContactName.MAX_LENGTH, error = error)
        Spacer(Modifier.height(10.dp))
        RepliLabel("Repli will also recognize the previous captured name next time.",
            12, RepliStyle.muted)
        Spacer(Modifier.height(18.dp))
        RepliAction("Save name", onClick = {
            val normalized = CapturedContactName.prepare(name)
            error = when {
                normalized == null -> "Enter a valid chat name"
                repository.profiles().any { other -> other.id != profile.id &&
                    (listOf(other.name) + other.nameAliases).any {
                        CapturedContactName.identityKey(it) == CapturedContactName.identityKey(normalized)
                    }
                } -> "This name belongs to another saved chat"
                repository.rename(profile.id, normalized) == null -> "Could not save this name. Try again."
                else -> null
            }
            if (error == null) onSaved()
        })
        TextButton(onClick = onDismiss) { Text("Cancel", color = RepliStyle.muted) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AddChatSheet(repository: ProfileRepository, personas: List<Persona>,
    onSaved: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var personaId by remember { mutableStateOf("casual") }
    var error by remember { mutableStateOf<String?>(null) }
    RepliSheet(onDismiss = onDismiss) {
            RepliLabel("Save a chat", 23, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(6.dp))
            RepliLabel("Give the chat a name and choose its starting persona.", 14, RepliStyle.muted)
            Spacer(Modifier.height(18.dp))
            RepliInput(name, { name = it; error = null }, "CHAT NAME", "e.g. Alex",
                singleLine = true, maxLength = 80, error = error)
            Spacer(Modifier.height(18.dp))
            RepliLabel("PERSONA", 13, RepliStyle.accent, bold = true)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
                personas.forEach { choice ->
                    PersonaChoice(choice, personaId == choice.id) { personaId = choice.id }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            RepliLabel("Only saved on this device. You can change the persona later.", 12, RepliStyle.muted)
            Spacer(Modifier.height(18.dp))
            RepliAction("Save chat", onClick = {
                val normalized = name.trim()
                error = when {
                    normalized.isEmpty() -> "Enter a chat name"
                    repository.findByName(normalized) != null -> "This chat is already saved. Change its persona from Chats."
                    else -> null
                }
                if (error == null) {
                    val selected = personas.first { it.id == personaId }
                    repository.updatePersona(repository.add(normalized, "Saved chat", selected.baseStyle).id, selected)
                    onSaved()
                }
            })
            TextButton(onClick = onDismiss) { Text("Cancel", color = RepliStyle.muted) }
            Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PersonaChoice(persona: Persona, selected: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) RepliStyle.accentSoft else RepliStyle.paper,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) RepliStyle.accent else RepliStyle.line)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RepliLabel(persona.name, 16, RepliStyle.ink, bold = true)
                Spacer(Modifier.height(3.dp))
                RepliLabel(persona.description, 12, RepliStyle.muted)
            }
            RadioButton(selected = selected, onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = RepliStyle.accent))
        }
    }
}
