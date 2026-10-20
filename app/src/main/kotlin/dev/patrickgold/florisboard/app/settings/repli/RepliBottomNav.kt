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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.Routes

private data class RepliTab(val route: Any, val label: String, val icon: Int)

private val tabs = listOf(
    RepliTab(Routes.Settings.RepliHome, "Home", R.drawable.ic_nav_home),
    RepliTab(Routes.Settings.RepliChats, "Chats", R.drawable.ic_nav_chats),
    RepliTab(Routes.Settings.RepliSettings, "Settings", R.drawable.ic_nav_settings),
)

@Composable
fun RepliBottomNav(navController: NavController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val selectedIndex = tabs.indexOfFirst { destination?.hasRoute(it.route::class) == true }
    if (selectedIndex < 0) return
    Surface(color = RepliStyle.card, border = BorderStroke(1.dp, RepliStyle.line)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val tint = if (selected) RepliStyle.accent else RepliStyle.muted
                Column(
                    Modifier.weight(1f).height(64.dp).clickable {
                        if (!selected) {
                            val returnedHome = navController.popBackStack(Routes.Settings.RepliHome, false)
                            if (index != 0 || !returnedHome) {
                                navController.navigate(tab.route) {
                                    launchSingleTop = true
                                    if (index == 0) {
                                        popUpTo(navController.graph.id)
                                    }
                                }
                            }
                        }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.width(64.dp).height(32.dp)
                        .background(if (selected) RepliStyle.accentSoft else Color.Transparent,
                            RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Icon(painterResource(tab.icon), contentDescription = null,
                            modifier = Modifier.size(24.dp), tint = tint)
                    }
                    Text(tab.label, modifier = Modifier.padding(top = 4.dp), color = tint,
                        fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }
    }
}
