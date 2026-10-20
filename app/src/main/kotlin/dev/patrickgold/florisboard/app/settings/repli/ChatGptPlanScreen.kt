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

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.repli.openai.OpenAiException
import dev.patrickgold.florisboard.repli.openai.OpenAiModel
import dev.patrickgold.florisboard.repli.openai.OpenAiPlanClient
import dev.patrickgold.florisboard.repli.openai.RepliOpenAiAccess
import dev.patrickgold.florisboard.repli.openai.auth.ChatGptAccountManager
import dev.patrickgold.florisboard.repli.openai.auth.ChatGptOAuthConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Use your ChatGPT plan": the only sign-in Repli has. Wording follows OpenAI's Sign in with
 * ChatGPT guidelines (Continue with ChatGPT, Manage usage, the one-time welcome).
 */
@Composable
fun ChatGptPlanScreen() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val account by ChatGptAccountManager.state.collectAsState()
    val apiKey = RepliOpenAiAccess.apiKeys().load()

    if (account.welcomePending) {
        AlertDialog(
            onDismissRequest = { ChatGptAccountManager.markWelcomeShown() },
            title = { Text("You're using your ChatGPT plan") },
            text = {
                Text(
                    "Replies, screenshot reading and rewrites in Repli now run on your own ChatGPT plan. " +
                        "Repli has no servers and no subscription: nothing is billed by Repli. " +
                        "Your plan's limits apply, and you can set a cap for Repli or disconnect it any time in ChatGPT settings → Usage.",
                )
            },
            confirmButton = { TextButton(onClick = { ChatGptAccountManager.markWelcomeShown() }) { Text("Got it") } },
        )
    }

    if (chooseModel && models != null) {
        RepliSheet(onDismiss = { chooseModel = false }) {
            Spacer(Modifier.height(18.dp))
            RepliLabel("Choose a model", 20, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(6.dp))
            RepliLabel("Models your ChatGPT plan offers, in ChatGPT's order. Replies and screenshot reading use the same model.", 13, RepliStyle.muted)
            Spacer(Modifier.height(14.dp))
            RepliAction("Automatic (first listed)", {
                RepliOpenAiAccess.preferredPlanModel = null; preferredModel = null; chooseModel = false
            }, filled = preferredModel == null)
            models!!.forEach { model ->
                Spacer(Modifier.height(8.dp))
                RepliAction(if (model.displayName != model.slug) "${model.displayName} · ${model.slug}" else model.slug, {
                    RepliOpenAiAccess.preferredPlanModel = model.slug; preferredModel = model.slug; chooseModel = false
                }, filled = preferredModel == model.slug)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    RepliPage {
        RepliLabel("Use your ChatGPT plan", 27, RepliStyle.ink, bold = true)
        Spacer(Modifier.height(6.dp))
        RepliLabel(
            "Repli is open source and free. AI replies come straight from OpenAI using your ChatGPT Plus or Pro plan — " +
                "no Repli account, no Repli servers, nothing sold.",
            15, RepliStyle.muted,
        )

        Spacer(Modifier.height(20.dp))
        RepliCard(tinted = account.planAllowed) {
            when {
                account.planAllowed -> {
                    RepliLabel("Using ChatGPT plan", 18, RepliStyle.ink, bold = true)
                    Spacer(Modifier.height(6.dp))
                    RepliLabel("Signed in as ${account.email ?: account.name ?: "your ChatGPT account"}", 14, RepliStyle.muted)
                    Spacer(Modifier.height(6.dp))
                    RepliLabel("Model · " + (preferredModel ?: "first model ChatGPT lists"), 14, RepliStyle.muted)
                    Spacer(Modifier.height(12.dp))
                    RepliAction("Manage usage", { openUrl(context, ChatGptOAuthConfig.MANAGE_USAGE_URL) })
                    Spacer(Modifier.height(8.dp))
                    RepliAction(if (models == null && modelStatus != null) modelStatus!! else "Choose model", {
                        modelStatus = "Loading models…"
                        scope.launch {
                            try {
                                val credential = ChatGptAccountManager.credentialForRequest()
                                    ?: throw IllegalStateException("Sign in with ChatGPT first")
                                models = withContext(Dispatchers.IO) { OpenAiPlanClient().listModels(credential).filter { it.listed } }
                                modelStatus = null
                                chooseModel = true
                            } catch (error: OpenAiException) {
                                modelStatus = error.failure.label
                            } catch (error: Exception) {
                                modelStatus = error.message ?: "Couldn't load models"
                            }
                        }
                    }, filled = false, enabled = !account.busy)
                    Spacer(Modifier.height(8.dp))
                    RepliAction("Sign out of ChatGPT", { scope.launch { ChatGptAccountManager.signOut() } },
                        filled = false, enabled = !account.busy)
                }
                account.finishPending -> {
                    RepliLabel(if (account.busy) "Finishing sign-in…" else "Almost done", 18, RepliStyle.ink, bold = true)
                    Spacer(Modifier.height(6.dp))
                    RepliLabel("ChatGPT approved Repli. One more step completes the connection now that Repli is open.", 14, RepliStyle.muted)
                    Spacer(Modifier.height(12.dp))
                    RepliAction(if (account.busy) "Finishing…" else "Finish signing in",
                        { scope.launch { ChatGptAccountManager.completePendingSignIn() } }, enabled = !account.busy)
                }
                account.signedIn -> {
                    RepliLabel("Plan usage not allowed", 18, RepliStyle.ink, bold = true)
                    Spacer(Modifier.height(6.dp))
                    RepliLabel("You signed in, but ChatGPT didn't allow Repli to use your plan. Sign in again and approve plan usage.", 14, RepliStyle.muted)
                    Spacer(Modifier.height(12.dp))
                    RepliAction("Continue with ChatGPT", { scope.launch { ChatGptAccountManager.signIn(context) } }, enabled = !account.busy)
                }
                else -> {
                    RepliLabel("Not connected", 18, RepliStyle.ink, bold = true)
                    Spacer(Modifier.height(6.dp))
                    RepliLabel(
                        "Tap below to open ChatGPT in your browser. Approve Repli, then come back here. " +
                            "Repli only receives a token for your account; it never sees your ChatGPT password.",
                        14, RepliStyle.muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    RepliAction(if (account.busy) "Waiting for ChatGPT…" else "Continue with ChatGPT",
                        { scope.launch { ChatGptAccountManager.signIn(context) } }, enabled = !account.busy)
                }
            }
            account.errorMessage?.let {
                Spacer(Modifier.height(10.dp))
                RepliLabel(it, 13, RepliStyle.muted)
            }
            if (account.revocationUnconfirmed) {
                Spacer(Modifier.height(10.dp))
                RepliLabel("Repli removed its tokens, but ChatGPT didn't confirm. You can also disconnect Repli in ChatGPT settings → Usage.", 13, RepliStyle.muted)
            }
        }

        Spacer(Modifier.height(16.dp))
        RepliCard {
            RepliLabel("What your plan is used for", 18, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel(
                "Only what you approve leaves the phone: the reviewed chat, your writing style cues and, for screenshot reading, the cropped chat image. " +
                    "Requests go directly to api.openai.com under your account with storage off. Chat memory stays encrypted on this phone.",
                14, RepliStyle.muted,
            )
            Spacer(Modifier.height(8.dp))
            RepliLabel(
                "If your plan's limit is reached, Repli pauses AI replies until it resets; typing suggestions keep working offline.",
                14, RepliStyle.muted,
            )
        }

        Spacer(Modifier.height(16.dp))
        RepliCard {
            RepliLabel("No ChatGPT plan?", 18, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel(
                if (apiKey != null) "An OpenAI API key is set (${apiKey.masked}). It's used whenever you're not signed in with ChatGPT."
                else "You can use your own OpenAI API key instead; you pay OpenAI per request.",
                14, RepliStyle.muted,
            )
            Spacer(Modifier.height(12.dp))
            RepliAction(if (apiKey != null) "Manage API key" else "Use an API key instead",
                { navController.navigate(Routes.Settings.OpenAiApiKey) }, filled = false)
        }
    }
}

internal fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
