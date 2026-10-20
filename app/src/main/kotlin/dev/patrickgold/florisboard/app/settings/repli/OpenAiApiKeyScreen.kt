package dev.patrickgold.florisboard.app.settings.repli

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.repli.openai.OpenAiApiKeyConfig
import dev.patrickgold.florisboard.repli.openai.OpenAiApiKeyStore
import dev.patrickgold.florisboard.repli.openai.OpenAiCredential
import dev.patrickgold.florisboard.repli.openai.OpenAiException
import dev.patrickgold.florisboard.repli.openai.OpenAiPlanClient
import dev.patrickgold.florisboard.repli.openai.RepliOpenAiAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bring your own OpenAI API key: stored encrypted on the phone, shown masked, tested before saving. */
@Composable
fun OpenAiApiKeyScreen() {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val store = remember { RepliOpenAiAccess.apiKeys() }
    var saved by remember { mutableStateOf(store.load()) }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(saved?.model ?: OpenAiApiKeyStore.DEFAULT_MODEL) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun check(then: (List<String>) -> Unit) {
        val candidate = key.trim().ifEmpty { saved?.key.orEmpty() }
        if (!OpenAiApiKeyStore.looksLikeKey(candidate)) { status = "Paste a key that starts with sk-"; return }
        busy = true; status = "Checking key…"
        scope.launch {
            try {
                val listed = withContext(Dispatchers.IO) {
                    OpenAiPlanClient().listModels(OpenAiCredential.ApiKey(candidate, model)).map { it.id }.sorted()
                }
                then(listed)
            } catch (error: OpenAiException) {
                status = error.failure.label
            } finally {
                busy = false
            }
        }
    }

    RepliPage {
        RepliLabel("Your OpenAI API key", 27, RepliStyle.ink, bold = true)
        Spacer(Modifier.height(6.dp))
        RepliLabel(
            "Used only when you're not signed in with ChatGPT. The key is encrypted on this phone, excluded from backups, " +
                "and sent only to api.openai.com. OpenAI bills your account per request.",
            15, RepliStyle.muted,
        )

        Spacer(Modifier.height(20.dp))
        RepliCard {
            if (saved != null) {
                RepliLabel("Key saved (${saved!!.masked}) · model ${saved!!.model}", 14, RepliStyle.muted)
                Spacer(Modifier.height(12.dp))
            }
            RepliInput(key, { key = it.trim() }, if (saved != null) "Replace key" else "API key", "sk-…", singleLine = true, maxLength = 400)
            Spacer(Modifier.height(12.dp))
            RepliInput(model, { model = it.trim() }, "Model", OpenAiApiKeyStore.DEFAULT_MODEL, singleLine = true, maxLength = 80)
            if (models.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                RepliLabel("Available: " + models.take(12).joinToString(", ") + if (models.size > 12) "…" else "", 12, RepliStyle.muted)
            }
            Spacer(Modifier.height(12.dp))
            RepliAction("Check key & list models", { check { listed -> models = listed; status = "Key works · ${listed.size} models" } },
                filled = false, enabled = !busy)
            Spacer(Modifier.height(8.dp))
            RepliAction("Save", {
                check { listed ->
                    if (model.isNotBlank() && listed.isNotEmpty() && model !in listed) {
                        status = "That model isn't available to this key"
                    } else {
                        val config = OpenAiApiKeyConfig(key.trim().ifEmpty { saved?.key.orEmpty() }, model.trim().ifEmpty { OpenAiApiKeyStore.DEFAULT_MODEL })
                        runCatching { store.save(config) }
                            .onSuccess { saved = config; key = ""; status = "Saved" }
                            .onFailure { status = it.message ?: "Couldn't save" }
                    }
                }
            }, enabled = !busy)
            if (saved != null) {
                Spacer(Modifier.height(8.dp))
                RepliAction("Remove key", { store.clear(); saved = null; models = emptyList(); status = "Key removed" }, filled = false, enabled = !busy)
            }
            status?.let {
                Spacer(Modifier.height(10.dp))
                RepliLabel(it, 13, RepliStyle.muted)
            }
        }

        Spacer(Modifier.height(16.dp))
        RepliAction("Back", { navController.popBackStack() }, filled = false)
    }
}
