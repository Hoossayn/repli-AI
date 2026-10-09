package dev.patrickgold.florisboard.repli.ime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.repli.suggestions.RewriteTone
import org.florisboard.lib.compose.stringRes

private val Paper: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF17151E) else Color(0xFFF3F0FA)
private val Card: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF25212F) else Color.White
private val Ink: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF4F1FA) else Color(0xFF27243A)
private val Muted: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFB8B1C6) else Color(0xFF6E6A80)
private val Accent: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFAB9BFF) else Color(0xFF6654D1)
private val OnAccent: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF21183E) else Color.White
private val AccentSoft: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF383050) else Color(0xFFEEEAFE)
private val Line: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF453E52) else Color(0xFFE4DEEE)
private val CardShape = RoundedCornerShape(16.dp)
private val ChipShape = RoundedCornerShape(999.dp)

/**
 * The Rewrite panel: the current draft, a row of tone chips, and three rewrites. Tapping a
 * rewrite replaces the composer text; nothing is sent on the user's behalf.
 */
@Composable
fun RepliRewritePanel(controller: RewriteController, modifier: Modifier = Modifier) {
    val ui by controller.uiState.collectAsState()
    val keyboardHeight = FlorisImeSizing.imeUiHeight()
    Column(modifier = modifier.fillMaxWidth().height(keyboardHeight).background(Paper)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = controller::clear, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringRes(R.string.repli_panel__back), tint = Ink)
            }
            Text(stringRes(R.string.repli_rewrite__title), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (!ui.busy && ui.text.isNotEmpty()) {
                TextButton(onClick = controller::retry) {
                    Text(stringRes(R.string.repli_rewrite__retry), color = Accent, fontSize = 11.sp)
                }
            }
        }
        if (ui.text.isNotEmpty()) {
            Text(
                ui.text, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(RewriteTone.entries) { tone ->
                    val selected = tone == ui.tone
                    Surface(
                        modifier = Modifier.clickable(enabled = !ui.busy) { controller.selectTone(tone) },
                        color = if (selected) Accent else Card,
                        shape = ChipShape,
                        border = BorderStroke(1.dp, if (selected) Accent else Line),
                    ) {
                        Text(
                            tone.label, color = if (selected) OnAccent else Ink, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (ui.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), color = Accent, trackColor = AccentSoft)
        }
        if (ui.status.isNotEmpty() && (ui.candidates.isEmpty() || ui.busy)) {
            Text(ui.status, color = Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp))
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ui.candidates.forEach { candidate ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { controller.apply(candidate) },
                    color = Card, shape = CardShape, border = BorderStroke(1.dp, Line),
                ) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(candidate, color = Ink, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (ui.candidates.isNotEmpty()) {
                Text(stringRes(R.string.repli_rewrite__tap_hint), color = Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
