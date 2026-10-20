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

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.patrickgold.florisboard.lib.compose.LocalPreviewFieldController

internal object RepliStyle {
    val ink: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF4F1FA) else Color(0xFF27243A)
    val muted: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFB8B1C6) else Color(0xFF6E6A80)
    val paper: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF17151E) else Color(0xFFFAF8F5)
    val card: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF25212F) else Color.White
    val accent: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFAB9BFF) else Color(0xFF6654D1)
    val onAccent: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF21183E) else Color.White
    val accentSoft: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF383050) else Color(0xFFEEEAFE)
    val line: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF453E52) else Color(0xFFE7E2EE)
    val strongLine: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF655A73) else Color(0xFFD7CEE5)
}

@Composable
internal fun RepliPage(content: @Composable () -> Unit) {
    val window = LocalActivity.current?.window
    val paper = RepliStyle.paper
    val dark = isSystemInDarkTheme()
    val previewFieldController = LocalPreviewFieldController.current
    SideEffect {
        previewFieldController?.isVisible = false
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            window.statusBarColor = paper.toArgb()
            window.navigationBarColor = paper.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    Column(Modifier.fillMaxSize().background(paper).statusBarsPadding()) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RepliLabel("repli", 30, RepliStyle.ink, bold = true)
            RepliLabel("Your words. Your call.", 12, RepliStyle.muted,
                modifier = Modifier.padding(top = 14.dp))
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 28.dp),
        ) { content() }
    }
}

@Composable
internal fun RepliLabel(text: String, size: Int, color: Color, bold: Boolean = false,
    modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = color, fontSize = size.sp,
        lineHeight = (size + 3).sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
}

@Composable
internal fun RepliCard(tinted: Boolean = false, modifier: Modifier = Modifier,
    content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = if (tinted) RepliStyle.accentSoft else RepliStyle.card,
        border = if (tinted) null else BorderStroke(1.dp, RepliStyle.line),
    ) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}

@Composable
internal fun RepliAction(label: String, onClick: () -> Unit, filled: Boolean = true,
    enabled: Boolean = true, modifier: Modifier = Modifier, iconRes: Int? = null,
    iconTint: Color = Color.Unspecified) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(16.dp),
        border = if (filled) null else BorderStroke(1.dp, RepliStyle.strongLine),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) RepliStyle.accent else RepliStyle.card,
            contentColor = if (filled) RepliStyle.onAccent else RepliStyle.accent,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
    ) {
        if (iconRes != null) {
            Icon(painterResource(iconRes), contentDescription = null,
                modifier = Modifier.size(20.dp), tint = iconTint)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun RepliSection(title: String, modifier: Modifier = Modifier) {
    RepliLabel(title, 19, RepliStyle.ink, bold = true, modifier = modifier)
    Spacer(Modifier.height(10.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RepliSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = RepliStyle.card,
        contentColor = RepliStyle.ink, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp), content = content)
    }
}

@Composable
internal fun RepliInput(value: String, onValueChange: (String) -> Unit, label: String,
    placeholder: String, modifier: Modifier = Modifier, singleLine: Boolean = false,
    minLines: Int = 1, maxLines: Int = Int.MAX_VALUE, maxLength: Int? = null,
    error: String? = null) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val accent = RepliStyle.accent
    val ink = RepliStyle.ink
    val muted = RepliStyle.muted
    Column(modifier) {
        RepliLabel(label, 13, if (focused) accent else muted, bold = true)
        Spacer(Modifier.height(7.dp))
        Surface(shape = RoundedCornerShape(18.dp), color = RepliStyle.accentSoft,
            border = BorderStroke(if (focused || error != null) 2.dp else 1.dp,
                if (error != null) Color(0xFFB54161) else if (focused) accent else RepliStyle.line)) {
            BasicTextField(value = value, onValueChange = { changed ->
                if (maxLength == null || changed.length <= maxLength) onValueChange(changed)
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                singleLine = singleLine, minLines = minLines, maxLines = maxLines,
                interactionSource = interactionSource, cursorBrush = SolidColor(accent),
                textStyle = TextStyle(color = ink, fontSize = 16.sp, lineHeight = 22.sp),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) Text(placeholder, color = muted, fontSize = 16.sp,
                            lineHeight = 22.sp)
                        innerTextField()
                    }
                })
        }
        if (error != null || maxLength != null) {
            RepliLabel(error ?: "${value.length}/$maxLength", 12,
                if (error != null) Color(0xFFB54161) else muted,
                modifier = Modifier.padding(top = 5.dp, start = 4.dp))
        }
    }
}
