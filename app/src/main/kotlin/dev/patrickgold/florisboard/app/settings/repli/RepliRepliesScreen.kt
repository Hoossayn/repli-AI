package dev.patrickgold.florisboard.app.settings.repli

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import dev.patrickgold.florisboard.repli.openai.RepliOpenAiAccess
import dev.patrickgold.florisboard.repli.openai.auth.ChatGptAccountManager
import dev.patrickgold.florisboard.repli.capture.ReplyAutoScrollAccessibilityService
import dev.patrickgold.florisboard.repli.data.RemoteGenerationPreferences
import dev.patrickgold.florisboard.repli.practice.PracticeChatActivity
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import org.florisboard.lib.compose.stringRes

@Composable
fun RepliRepliesScreen() = FlorisScreen {
    title = stringRes(R.string.repli_replies__title)
    previewFieldVisible = false

    content {
        val context = LocalContext.current
        val navController = LocalNavController.current
        val chatGpt by ChatGptAccountManager.state.collectAsState()
        val aiConnected = remember(chatGpt.planAllowed) { RepliOpenAiAccess.configured }
        val keyboardEnabled by InputMethodUtils.observeIsFlorisboardEnabled(foregroundOnly = true)
        val keyboardSelected by InputMethodUtils.observeIsFlorisboardSelected(foregroundOnly = true)
        var cloudEnabled by remember { mutableStateOf(RemoteGenerationPreferences(context).enabled) }
        var notificationOn by remember { mutableStateOf(isNotificationListenerOn(context)) }
        var guidedOn by remember { mutableStateOf(isGuidedCaptureOn(context)) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, context) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    notificationOn = isNotificationListenerOn(context)
                    guidedOn = isGuidedCaptureOn(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        PreferenceGroup(title = stringRes(R.string.repli_replies__cloud_title)) {
            Preference(
                icon = Icons.Default.AutoAwesome,
                title = stringRes(R.string.repli_replies__cloud_toggle),
                summary = if (cloudEnabled) {
                    stringRes(R.string.repli_replies__cloud_on)
                } else {
                    stringRes(R.string.repli_replies__cloud_off)
                },
                onClick = {
                    if (!aiConnected) {
                        navController.navigate(Routes.Settings.ChatGptPlan)
                    } else {
                        cloudEnabled = !cloudEnabled
                        RemoteGenerationPreferences(context).enabled = cloudEnabled
                    }
                },
            )
            if (!aiConnected) {
                Preference(
                    icon = Icons.Default.AccountCircle,
                    title = stringRes(R.string.repli_replies__sign_in_first),
                    onClick = { navController.navigate(Routes.Settings.ChatGptPlan) },
                )
            }
        }

        PreferenceGroup(title = stringRes(R.string.repli_replies__context_title)) {
            Preference(
                icon = Icons.Default.Notifications,
                title = stringRes(R.string.repli_replies__notification_access),
                summary = if (notificationOn) {
                    stringRes(R.string.repli_replies__on)
                } else {
                    stringRes(R.string.repli_replies__off)
                },
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    notificationOn = isNotificationListenerOn(context)
                },
            )
            Preference(
                icon = Icons.Default.AutoAwesome,
                title = stringRes(R.string.repli_replies__guided_capture),
                summary = if (guidedOn) {
                    stringRes(R.string.repli_replies__on)
                } else {
                    stringRes(R.string.repli_replies__off)
                },
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    guidedOn = isGuidedCaptureOn(context)
                },
            )
        }

        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text(stringRes(R.string.repli_replies__how_it_works))
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringRes(R.string.repli_replies__guided_capture))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    when {
                        !keyboardEnabled -> InputMethodUtils.showImeEnablerActivity(context)
                        !keyboardSelected -> InputMethodUtils.showImePicker(context)
                        else -> context.startActivity(Intent(context, PracticeChatActivity::class.java))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringRes(when {
                    !keyboardEnabled -> R.string.repli_home__enable_keyboard
                    !keyboardSelected -> R.string.repli_home__choose_keyboard
                    else -> R.string.repli_replies__try_practice_flow
                }))
            }
            Spacer(Modifier.height(8.dp))
            Text(stringRes(R.string.repli_replies__mic_note))
            Spacer(Modifier.height(4.dp))
            Preference(
                icon = Icons.Default.Mic,
                title = stringRes(R.string.repli_replies__mic_title),
                summary = stringRes(R.string.repli_replies__mic_note),
                onClick = { },
            )
        }
    }
}

private fun isNotificationListenerOn(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun isGuidedCaptureOn(context: Context): Boolean {
    val expected = ComponentName(context, ReplyAutoScrollAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) } ||
        TextUtils.isEmpty(enabled).not() && enabled.contains(
            ReplyAutoScrollAccessibilityService::class.java.name,
        )
}
