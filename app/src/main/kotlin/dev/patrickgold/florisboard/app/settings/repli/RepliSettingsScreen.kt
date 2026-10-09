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
import dev.patrickgold.florisboard.repli.account.RepliFirebaseAccountManager
import dev.patrickgold.florisboard.repli.capture.ReplyAutoScrollAccessibilityService
import dev.patrickgold.florisboard.repli.data.AutoScrollPreferences
import dev.patrickgold.florisboard.repli.data.RecentMessageRepository
import dev.patrickgold.florisboard.repli.data.RemoteGenerationPreferences
import dev.patrickgold.florisboard.repli.diagnostics.CrashReportingPreferences
import dev.patrickgold.florisboard.repli.diagnostics.RepliCrashReporting

@Composable
fun RepliSettingsScreen() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val account by RepliFirebaseAccountManager.state.collectAsState()
    val remote = remember(context) { RemoteGenerationPreferences(context) }
    val capture = remember(context) { AutoScrollPreferences(context) }
    val learning = remember(context) { KeyboardLearningPreferences(context) }
    var cloudEnabled by remember { mutableStateOf(remote.enabled) }
    var guideEnabled by remember { mutableStateOf(capture.enabled) }
    var learningEnabled by remember { mutableStateOf(learning.enabled) }
    var crashReportsEnabled by remember { mutableStateOf(CrashReportingPreferences(context).enabled) }
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
                notificationEnabled = hasNotificationAccess(context)
                accessibilityEnabled = hasCaptureAccessibility(context)
                cloudEnabled = remote.enabled
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
            RepliLabel("Cloud replies & account", 18, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel(when {
                !account.configured -> "Cloud account setup is unavailable in this build."
                account.signedIn -> "Signed in as ${account.email.orEmpty()}"
                else -> "Sign in or create an account · your cloud session is created automatically."
            }, 14, RepliStyle.muted)
            Spacer(Modifier.height(12.dp))
            RepliAction(if (account.signedIn) "Manage account" else "Sign in", {
                navController.navigate(Routes.Settings.Account)
            })
            if (!account.signedIn) {
                Spacer(Modifier.height(8.dp))
                RepliAction("Create account", { navController.navigate(Routes.Settings.Account) },
                    filled = false)
            }
            Spacer(Modifier.height(8.dp))
            RepliLabel(when {
                !account.configured -> "Not configured in this build."
                !account.signedIn -> "Unavailable without an authenticated Repli account."
                cloudEnabled -> "Enabled. Captures use AI reading; reply generation waits for context review."
                else -> "Off. Cloud reply generation is unavailable."
            }, 13, RepliStyle.muted)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = cloudEnabled, enabled = account.signedIn,
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
