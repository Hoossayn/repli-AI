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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import dev.patrickgold.florisboard.repli.practice.PracticeChatActivity

@Composable
fun RepliHomeScreen() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val enabled by InputMethodUtils.observeIsFlorisboardEnabled(foregroundOnly = true)
    val selected by InputMethodUtils.observeIsFlorisboardSelected(foregroundOnly = true)

    RepliPage {
        RepliCard(tinted = true) {
            RepliLabel("A LITTLE HELP WITH THE NEXT REPLY", 11, RepliStyle.accent, bold = true)
            Spacer(Modifier.height(12.dp))
            RepliLabel("Good replies.\nLess overthinking.", 30, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(12.dp))
            RepliLabel("Three ideas to make your own. Choose one, edit it, and send when you're ready.",
                15, RepliStyle.muted)
            Spacer(Modifier.height(20.dp))
            RepliAction("Try a practice chat", {
                context.startActivity(Intent(context, PracticeChatActivity::class.java))
            })
            Spacer(Modifier.height(10.dp))
            RepliLabel("Sample conversation · nothing is sent automatically", 12, RepliStyle.muted)
        }

        Spacer(Modifier.height(26.dp))
        RepliSection("Your keyboard, with a little extra")
        RepliCard {
            RepliLabel("Set up once. Use in your chats.", 17, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(4.dp))
            RepliLabel(
                when {
                    !enabled -> "Repli Keyboard is not enabled yet."
                    !selected -> "Repli Keyboard is enabled. Choose it to start typing."
                    else -> "Repli Keyboard is enabled and selected. You're ready to type."
                }, 13, RepliStyle.muted,
            )
            Spacer(Modifier.height(12.dp))
            RepliAction("Open keyboard settings", { InputMethodUtils.showImeEnablerActivity(context) })
            Spacer(Modifier.height(8.dp))
            RepliAction("Choose Repli keyboard", { InputMethodUtils.showImePicker(context) }, filled = false)
        }

        Spacer(Modifier.height(26.dp))
        RepliSection("From chat to reply")
        RepliCard {
            listOf(
                Triple("1", "Open your chat", "Tap its message box and show the Repli keyboard."),
                Triple("2", "Get three ideas", "Tap the reply icon in the suggestion strip. Confirm a recent message, or capture the visible chat."),
                Triple("3", "Make it yours", "Tap a reply to insert it, edit it, then send it yourself."),
            ).forEachIndexed { index, (number, title, description) ->
                if (index > 0) Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(modifier = Modifier.size(32.dp), color = RepliStyle.accentSoft,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            RepliLabel(number, 14, RepliStyle.accent, bold = true)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        RepliLabel(title, 16, RepliStyle.ink, bold = true)
                        Spacer(Modifier.height(4.dp))
                        RepliLabel(description, 14, RepliStyle.muted)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            RepliAction("Latest-message settings", {
                navController.navigate(Routes.Settings.RepliSettings)
            }, filled = false)
        }

        Spacer(Modifier.height(16.dp))
        RepliCard {
            RepliLabel("A persona for every conversation", 17, RepliStyle.ink, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel("Choose from playful presets or create your own persona with a response guide and example replies.",
                14, RepliStyle.muted)
            Spacer(Modifier.height(14.dp))
            RepliAction("Manage your chats", {
                navController.navigate(Routes.Settings.RepliChats)
            }, filled = false)
        }
    }
}
