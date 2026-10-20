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

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.ime.nlp.latin.repli.AdaptiveLanguageRepository
import dev.patrickgold.florisboard.ime.nlp.latin.repli.KeyboardLearningPreferences
import dev.patrickgold.florisboard.repli.openai.OpenAiRoute
import dev.patrickgold.florisboard.repli.openai.RepliOpenAiAccess
import dev.patrickgold.florisboard.repli.openai.auth.ChatGptAccountManager
import dev.patrickgold.florisboard.repli.capture.ReplyAutoScrollAccessibilityService
import dev.patrickgold.florisboard.repli.data.AutoScrollPreferences
import dev.patrickgold.florisboard.repli.data.RecentMessageRepository
import dev.patrickgold.florisboard.repli.data.RemoteGenerationPreferences
import dev.patrickgold.florisboard.repli.diagnostics.CaptureTelemetry
import dev.patrickgold.florisboard.repli.diagnostics.CrashReportingPreferences
import dev.patrickgold.florisboard.repli.diagnostics.RepliCrashReporting
import dev.patrickgold.florisboard.imeController

@Composable
fun RepliSettingsScreen() {
    val context = LocalContext.current
    val keyboard by context.imeController()
    val navController = LocalNavController.current
    val chatGpt by ChatGptAccountManager.state.collectAsState()
    var aiRoute by remember { mutableStateOf(RepliOpenAiAccess.route()) }
    LaunchedEffect(chatGpt.planAllowed) { aiRoute = RepliOpenAiAccess.route() }
    val remote = remember(context) { RemoteGenerationPreferences(context) }
    val capture = remember(context) { AutoScrollPreferences(context) }
    val learning = remember(context) { KeyboardLearningPreferences(context) }
    var cloudEnabled by remember { mutableStateOf(remote.enabled) }
    var guideEnabled by remember { mutableStateOf(capture.enabled) }
    var learningEnabled by remember { mutableStateOf(learning.enabled) }
    var crashReportsEnabled by remember { mutableStateOf(CrashReportingPreferences(context).enabled) }
    val telemetry = remember(context) { CaptureTelemetry(context) }
    var diagnostics by remember { mutableStateOf(telemetry.snapshot()) }
    var typingMetrics by remember { mutableStateOf(keyboard.repliAssistant?.predictionMetrics) }
    var notificationEnabled by remember { mutableStateOf(hasNotificationAccess(context)) }
    var accessibilityEnabled by remember { mutableStateOf(hasCaptureAccessibility(context)) }
    var cloudDisclosure by remember { mutableStateOf(false) }
    var guideDisclosure by remember { mutableStateOf(false) }
    var clearLearning by remember { mutableStateOf(false) }
    var privacyFaq by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                diagnostics = telemetry.snapshot()
                typingMetrics = keyboard.repliAssistant?.predictionMetrics
                notificationEnabled = hasNotificationAccess(context)
                accessibilityEnabled = hasCaptureAccessibility(context)
                cloudEnabled = remote.enabled
                aiRoute = RepliOpenAiAccess.route()
                guideEnabled = capture.enabled
                learningEnabled = learning.enabled
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    RepliPage {
        RepliLabel("Make Repli yours", 27, RepliStyle.ink, bold = true)
        Spacer(Modifier.height(6.dp))
        RepliLabel("Choose what Repli can use. You're in control.", 15, RepliStyle.muted)

        Spacer(Modifier.height(16.dp))
        RepliAction("See how Repli works", {
            navController.navigate(Routes.Settings.RepliWelcome)
        }, filled = false)

        Spacer(Modifier.height(20.dp))
        RepliCard {
            RepliLabel("AI replies & your ChatGPT plan", 18, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel(when (aiRoute) {
                OpenAiRoute.CHATGPT_PLAN -> "Using ChatGPT plan · ${chatGpt.email ?: "signed in"}"
                OpenAiRoute.API_KEY -> "Using your OpenAI API key"
                null -> "Not connected. Sign in with ChatGPT to use your plan, or add an OpenAI API key."
            }, 14, RepliStyle.muted)
            Spacer(Modifier.height(12.dp))
            RepliAction(if (aiRoute != null) "Manage ChatGPT & API key" else "Continue with ChatGPT", {
                navController.navigate(Routes.Settings.ChatGptPlan)
            })
            Spacer(Modifier.height(8.dp))
            RepliLabel(when {
                aiRoute == null -> "Unavailable until ChatGPT or an API key is connected."
                cloudEnabled -> "Enabled. Captures use AI reading; reply generation waits for context review."
                else -> "Off. AI reply generation is unavailable."
            }, 13, RepliStyle.muted)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = cloudEnabled, enabled = aiRoute != null,
                    onCheckedChange = { checked ->
                        if (checked) cloudDisclosure = true else {
                            remote.enabled = false
                            cloudEnabled = false
                        }
                    })
                RepliLabel("Offer cloud replies after review", 14, RepliStyle.ink)
            }
            RepliLabel("Captured chat images can be read by AI when enabled. Check the extracted messages, then tap Generate replies to send the reviewed context. Replies require a connection.",
                12, RepliStyle.muted)
        }

        Spacer(Modifier.height(16.dp))
        RepliCard {
            RepliLabel("Guided manual capture", 18, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel("Start on the oldest page you want, then move toward newer messages. Tap Capture view for each page; Repli keeps that order.",
                14, RepliStyle.muted)
            Spacer(Modifier.height(6.dp))
            RepliLabel(if (accessibilityEnabled) "Accessibility guide is enabled."
                else "Accessibility guide is off; capture can still use the first visible view.",
                13, RepliStyle.muted)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = guideEnabled, onCheckedChange = { checked ->
                    if (checked) guideDisclosure = true else {
                        capture.enabled = false
                        guideEnabled = false
                    }
                })
                RepliLabel("Show glowing page-capture controls", 14, RepliStyle.ink)
            }
            Spacer(Modifier.height(8.dp))
            RepliAction("Open Accessibility settings", {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, filled = false)
            Spacer(Modifier.height(8.dp))
            RepliLabel("Without this option, capture uses the first visible view only. With it, sessions keep up to four views you select.",
                12, RepliStyle.muted)
        }

        Spacer(Modifier.height(16.dp))
        RepliCard {
            RepliLabel("Use the latest message", 18, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel("Optional notification access can provide a recent WhatsApp or Telegram message without a screen capture. Confirm the person in the keyboard first. If a message is hidden, missing, or ambiguous, use capture instead.",
                14, RepliStyle.muted)
            Spacer(Modifier.height(4.dp))
            RepliLabel(if (notificationEnabled) "On. Recent notifications can be offered for confirmation."
                else "Off. Capture still works; allow access here to use readable recent notifications.",
                13, RepliStyle.muted)
            Spacer(Modifier.height(12.dp))
            RepliAction("Open notification access", {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            Spacer(Modifier.height(8.dp))
            RepliAction("Clear recent context", {
                RecentMessageRepository(context).clear()
            }, filled = false)
        }

        Spacer(Modifier.height(16.dp))
        RepliCard {
            RepliLabel("Adaptive keyboard", 18, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel("Learns repeated words and word sequences on this device so completions and next-word choices follow how you type. Password, email, URL, and no-suggestion fields are excluded.",
                14, RepliStyle.muted)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = learningEnabled, onCheckedChange = {
                    learning.enabled = it
                    learningEnabled = it
                })
                RepliLabel("Learn from typing", 14, RepliStyle.ink)
            }
            RepliLabel(if (learningEnabled) "On · encrypted on-device learning · never uploaded"
                else "Off · bundled dictionary suggestions still work", 13, RepliStyle.muted)
            Spacer(Modifier.height(10.dp))
            RepliAction("Clear learned words & emoji", { clearLearning = true }, filled = false)
        }

        Spacer(Modifier.height(16.dp))
        RepliCard {
            RepliLabel("Keyboard & appearance", 18, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel("Customize the FlorisBoard keyboard that powers Repli.", 14, RepliStyle.muted)
            Spacer(Modifier.height(14.dp))
            RepliAction("Keyboard settings", { navController.navigate(Routes.Settings.Keyboard) }, filled = false)
            Spacer(Modifier.height(8.dp))
            RepliAction("Theme", { navController.navigate(Routes.Settings.Theme) }, filled = false)
            Spacer(Modifier.height(8.dp))
            RepliAction("Typing & suggestions", { navController.navigate(Routes.Settings.Typing) }, filled = false)
            Spacer(Modifier.height(8.dp))
            RepliAction("All keyboard settings", { navController.navigate(Routes.Settings.Home) }, filled = false)
        }

        Spacer(Modifier.height(16.dp))
        RepliCard {
            RepliLabel("Private by default", 17, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel("Keyboard prediction and adaptive learning stay on this device. AI reading uses captured images when cloud capture is enabled. Tapping Generate replies sends reviewed text, your direction, and selected style examples. Repli never sends a chat message for you.",
                14, RepliStyle.muted)
            Spacer(Modifier.height(14.dp))
            RepliAction("Privacy & FAQ", { privacyFaq = true }, filled = false)
        }

        Spacer(Modifier.height(16.dp))
        RepliCard {
            RepliLabel("Crash reports", 17, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel("If the keyboard crashes, an anonymous report with the error, device model and app version helps us fix it. Typed text, chats, suggestions and your account are never included.",
                14, RepliStyle.muted)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = crashReportsEnabled, onCheckedChange = {
                    crashReportsEnabled = it
                    RepliCrashReporting.setEnabled(context, it)
                })
                RepliLabel("Send anonymous crash reports", 14, RepliStyle.ink)
            }
        }

        Spacer(Modifier.height(16.dp))
        RepliCard {
            RepliLabel("Diagnostics", 17, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel("Local counters for typing and replies. No typed words are included, and these numbers are never uploaded.", 14, RepliStyle.muted)
            Spacer(Modifier.height(10.dp))
            RepliLabel(
                "Captures started ${diagnostics.capturesStarted} · screen-share not opened ${diagnostics.captureConsentFailed} · frames ${diagnostics.framesCaptured}\n" +
                    "AI read ${diagnostics.visionRead} · AI found nothing ${diagnostics.visionEmpty} · AI failed ${diagnostics.visionFailed} · AI unavailable ${diagnostics.visionUnavailable}\n" +
                    "Messages read ${diagnostics.turnsRead} · speaker fixes ${diagnostics.speakerCorrections} · messages removed ${diagnostics.turnsRemoved}\n" +
                    "Replies generated ${diagnostics.generationsSucceeded} · failed ${diagnostics.generationsFailed} · inserted ${diagnostics.repliesInserted}\n" +
                    "Rewrites requested ${diagnostics.rewritesRequested} · failed ${diagnostics.rewritesFailed} · applied ${diagnostics.rewritesApplied}\n" +
                    "Last field: ${diagnostics.lastField ?: "none yet"}\n" +
                    "Quick replies ${diagnostics.quickReplies} · avg ${diagnostics.quickReplyMsAverage / 1000}s (last ${diagnostics.quickReplyMsLast / 1000}s)\n" +
                    "Captured replies ${diagnostics.captureReplyCount} · avg ${diagnostics.captureReplyMsAverage / 1000}s (last ${diagnostics.captureReplyMsLast / 1000}s)",
                13, RepliStyle.muted,
            )
            Spacer(Modifier.height(10.dp))
            typingMetrics?.let { timing ->
                RepliLabel(
                    "Typing calculations ${timing.suggestionComputations + timing.correctionComputations} · reused results ${timing.cacheHits}\n" +
                        "Recent processing p95 ${timing.recentP95Micros} µs · slowest ${timing.worstMicros} µs\n" +
                        "Since the keyboard process started. Processing time only, not touch-to-screen delay.",
                    13, RepliStyle.muted,
                )
            }
            Spacer(Modifier.height(10.dp))
            RepliAction("Reset counters", {
                telemetry.reset()
                keyboard.repliAssistant?.resetPredictionMetrics()
                diagnostics = telemetry.snapshot()
                typingMetrics = keyboard.repliAssistant?.predictionMetrics
            }, filled = false)
        }
    }

    if (cloudDisclosure) RepliDisclosure("Enable cloud replies?",
        "When enabled, captured chat images can be sent for AI reading. Review the extracted messages and optional direction before tapping Generate replies. That tap sends the reviewed context and selected style examples. Reply generation requires internet and an account.",
        "Enable", onConfirm = {
            remote.enabled = true
            cloudEnabled = true
            cloudDisclosure = false
        }, onDismiss = { cloudDisclosure = false })
    if (guideDisclosure) RepliDisclosure("Show the scrolling guide?",
        "The Accessibility service shows Capture view and Done controls while you choose chat pages. It does not read the view tree, perform gestures, type, or send messages. Android will open Accessibility settings so you can enable it.",
        "Continue", onConfirm = {
            capture.enabled = true
            guideEnabled = true
            guideDisclosure = false
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, onDismiss = { guideDisclosure = false })
    if (clearLearning) RepliDisclosure("Clear adaptive keyboard data?",
        "This removes learned words, word sequences, and recent emoji from this device. The bundled dictionary remains available.",
        "Clear", onConfirm = {
            AdaptiveLanguageRepository(context).clear()
            learning.markCleared()
            clearLearning = false
        }, onDismiss = { clearLearning = false })
    if (privacyFaq) RepliDisclosure("Privacy & FAQ",
        "Repli never sends a chat message automatically. Keyboard learning stays on-device. AI reading may use captured images when cloud capture is enabled. Tapping Generate replies sends reviewed text, your direction, selected style examples, and the saved chat ID to Repli's backend. It stores up to 120 approved turns per chat; later requests can include up to 24 earlier turns and five outgoing style examples. Captured images are not saved to app storage.",
        "Done", onConfirm = { privacyFaq = false }, onDismiss = { privacyFaq = false })
}

@Composable
private fun RepliDisclosure(title: String, body: String, action: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun hasNotificationAccess(context: android.content.Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun hasCaptureAccessibility(context: android.content.Context): Boolean {
    val expected = ComponentName(context, ReplyAutoScrollAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) } ||
        !TextUtils.isEmpty(enabled) && enabled.contains(ReplyAutoScrollAccessibilityService::class.java.name)
}
