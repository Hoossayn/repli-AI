package dev.patrickgold.florisboard.repli.ime

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.repli.capture.ConversationTurn
import dev.patrickgold.florisboard.repli.suggestions.ReplyIntent
import dev.patrickgold.florisboard.repli.voice.VoicePhase
import dev.patrickgold.florisboard.repli.voice.VoiceRecordingState
import kotlinx.coroutines.delay
import org.florisboard.lib.compose.stringRes

private val Paper: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF17151E) else Color(0xFFF3F0FA)
private val Card: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF25212F) else Color.White
private val Ink: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF4F1FA) else Color(0xFF27243A)
private val Muted: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFB8B1C6) else Color(0xFF6E6A80)
private val Accent: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFAB9BFF) else Color(0xFF6654D1)
private val OnAccent: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF21183E) else Color.White
private val AccentSoft: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF383050) else Color(0xFFEEEAFE)
private val ReplyPromptCard: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF35294F) else Color(0xFFE5DDFF)
private val Line: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF453E52) else Color(0xFFE4DEEE)
private val ErrorCard: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF4A292D) else Color(0xFFFFEDEC)
private val ErrorLine: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF8A5357) else Color(0xFFE8B8B5)
private val ErrorText: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFFC9C5) else Color(0xFF8B2925)
private val CardShape = RoundedCornerShape(16.dp)

@Composable
fun RepliReadingBanner(viewCount: Int, onCancel: () -> Unit) {
    var takingLonger by remember(viewCount) { mutableStateOf(false) }
    LaunchedEffect(viewCount) {
        delay(8_000)
        takingLonger = true
    }
    val title = if (viewCount > 0) {
        "Reading $viewCount captured view${if (viewCount == 1) "" else "s"}"
    } else {
        "Reading captured chat"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = CardShape,
        color = Card,
        border = BorderStroke(1.dp, Line),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (takingLonger) "Still reading · you'll review messages next"
                    else "You'll review messages before getting ideas",
                    color = Muted, fontSize = 10.sp, maxLines = 2,
                )
            }
            TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("Cancel", color = Accent, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun RepliInlineGenerationBanner(modifier: Modifier = Modifier) {
    val controller = LocalImeController.current.repliReply ?: return
    val ui by controller.uiState.collectAsState()
    if (!ui.generating || ui.suggestions.isNotEmpty()) return
    var takingLonger by remember(ui.status) { mutableStateOf(false) }
    LaunchedEffect(ui.status) {
        delay(8_000)
        takingLonger = true
    }
    Surface(
        modifier = modifier.fillMaxWidth().height(54.dp)
            .padding(horizontal = 7.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = CardShape, color = Card, border = BorderStroke(1.dp, Line),
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(21.dp), color = Accent,
                strokeWidth = 2.dp)
            Column(Modifier.weight(1f)) {
                Text("Repli is putting ideas together", color = Ink, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(if (takingLonger) "Still working · you can keep typing" else ui.status,
                    color = Muted, fontSize = 10.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun RepliRecentMessagePrompt(modifier: Modifier = Modifier) {
    val controller = LocalImeController.current.repliReply ?: return
    val prompt = controller.uiState.collectAsState().value.quickReplyPrompt ?: return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = CardShape, color = Card, border = BorderStroke(1.dp, Line),
    ) {
        Row(Modifier.padding(start = 11.dp, end = 5.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${prompt.chatName} · ${prompt.personaName}", color = Accent,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(prompt.message, color = Ink, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = controller::generateFromRecentMessage,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 4.dp)) {
                Text("Generate", color = OnAccent, fontSize = 11.sp)
            }
            IconButton(onClick = controller::dismissRecentMessagePrompt,
                modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss recent message", tint = Muted)
            }
        }
    }
}

@Composable
fun RepliInputLayout(modifier: Modifier = Modifier) {
    val imeController = LocalImeController.current
    val orchestrator = imeController.repliReply
    if (orchestrator == null) {
        LaunchedEffect(Unit) {
            imeController.updateStateBlocking {
                state = state.copy(flags = state.flags.withImeUiMode(ImeUiMode.TEXT))
            }
        }
        return
    }
    val ui by orchestrator.uiState.collectAsState()
    val rewrite = imeController.repliRewrite
    val rewriteActive = rewrite?.uiState?.collectAsState()?.value?.active == true
    LaunchedEffect(ui.active, ui.generating, ui.suggestions.isEmpty(), rewriteActive) {
        if (rewriteActive) return@LaunchedEffect
        if (!ui.active || (ui.generating && ui.suggestions.isEmpty())) {
            imeController.updateStateBlocking {
                state = state.copy(flags = state.flags.withImeUiMode(ImeUiMode.TEXT))
            }
        }
    }
    if (rewriteActive && rewrite != null) {
        RepliRewritePanel(rewrite, modifier)
        return
    }
    val keyboardHeight = FlorisImeSizing.imeUiHeight()
    val moreHeight = minOf(LocalConfiguration.current.screenHeightDp.dp * 0.48f, 440.dp)
    val panelHeight = when {
        ui.reviewing -> keyboardHeight + 112.dp
        ui.suggestions.isNotEmpty() && !ui.guidanceOpen -> maxOf(keyboardHeight, moreHeight)
        else -> keyboardHeight
    }
    Column(
        modifier = modifier.fillMaxWidth().height(panelHeight).background(Paper),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { orchestrator.clear() },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringRes(R.string.repli_panel__back), tint = Ink)
            }
            Text(if (ui.reviewing) "Review chats" else "Repli replies", color = Ink,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (ui.reviewing) {
                TextButton(onClick = orchestrator::openFullScreenReview) {
                    Text("Full screen", color = Accent, fontSize = 11.sp)
                }
            } else if (ui.suggestions.isNotEmpty()) {
                TextButton(onClick = { orchestrator.clear() }) { Text("Clear", color = Muted, fontSize = 12.sp) }
            }
        }
        if (ui.reviewing) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Chat", color = Muted, fontSize = 10.sp)
                    Text(ui.capturedChatName ?: ui.selectedProfileName ?: "Unidentified chat",
                        color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(
                    modifier = Modifier.widthIn(max = 150.dp).clickable(enabled = !ui.busy) {
                        if (ui.showChatPicker) orchestrator.closeChatPicker() else orchestrator.openChatPicker()
                    },
                    shape = RoundedCornerShape(14.dp), color = Card, border = BorderStroke(1.dp, Line),
                ) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(ui.selectedPersonaName, modifier = Modifier.weight(1f, fill = false),
                            color = Accent, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.ExpandMore, contentDescription = "Choose persona",
                            tint = Accent, modifier = Modifier.size(17.dp))
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(42.dp)
                    .clickable(enabled = !ui.busy) {
                        if (ui.showChatPicker) orchestrator.closeChatPicker() else orchestrator.openChatPicker()
                    },
                shape = RoundedCornerShape(15.dp), color = Card, border = BorderStroke(1.dp, Line),
            ) {
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Persona", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${ui.selectedProfileName ?: "This reply"} · ${ui.selectedPersonaName}",
                        color = Ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ExpandMore, contentDescription = "Choose persona", tint = Accent,
                        modifier = Modifier.size(19.dp))
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        if (ui.busy) LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Accent, trackColor = AccentSoft,
        )
        if (ui.guidanceOpen) {
            GuidanceSection(
                modifier = Modifier.weight(1f),
                initial = ui.guidanceText,
                voice = ui.voice,
                voicePreview = ui.voicePreview,
                voiceBase = ui.voiceBase,
                voiceAccepted = ui.voiceAccepted,
                voiceAcceptedRev = ui.voiceAcceptedRev,
                voiceStatus = ui.voiceStatus,
                voiceShowSettings = ui.voiceShowSettings,
                onApply = orchestrator::applyGuidance,
                onCancel = orchestrator::cancelGuidance,
                onMic = orchestrator::startVoice,
                onPause = orchestrator::pauseVoice,
                onResume = orchestrator::resumeVoice,
                onStop = orchestrator::stopVoice,
                onDiscard = orchestrator::discardVoiceSegment,
                onConsumeAccepted = orchestrator::consumeVoiceAccepted,
                onVoiceSettings = orchestrator::openVoiceSettings,
            )
            return@Column
        }
        if (ui.reviewing && !ui.showChatPicker) {
            ReplyIntentSelector(ui.replyIntent, orchestrator::setReplyIntent)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${ui.reviewFrames} view${if (ui.reviewFrames == 1) "" else "s"} · ${ui.reviewTurns.size} messages",
                    color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (ui.reviewFrames < 4) SmallAction("Add page") {
                    orchestrator.beginCapture(append = true, singleView = true)
                }
            }
            ui.captureWarning?.let { warning ->
                Text(warning, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
                    color = ErrorText, fontSize = 11.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.showChatPicker) {
                PersonaPicker(
                    options = ui.chatOptions,
                    personas = ui.personaOptions,
                    selectedName = ui.selectedProfileName,
                    selectedPersonaId = ui.selectedPersonaId,
                    onSelect = orchestrator::selectChat,
                    onPersonaSelect = orchestrator::selectPersona,
                )
            } else {
                ui.confirm?.let { confirm ->
                    Surface(shape = CardShape, color = AccentSoft) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(confirm.description, color = Ink, fontSize = 12.sp,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = orchestrator::confirmSuggestedSender) {
                                Text(confirm.label, color = Accent, fontSize = 12.sp)
                            }
                        }
                    }
                }
                when {
                    ui.reviewing -> ReviewBody(
                        turns = ui.reviewTurns,
                        sourceStatus = if (ui.generationError == null && !ui.status.startsWith("AI read "))
                            ui.status else "",
                        onRemove = orchestrator::removeReviewTurn,
                    )
                    ui.approval != null -> ApprovalBody(
                        approval = ui.approval!!,
                        turns = ui.contextTurns,
                        onReview = orchestrator::openReview,
                        onFullScreen = orchestrator::openFullScreenReview,
                        status = ui.status,
                    )
                    ui.suggestions.isNotEmpty() -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("More replies", color = Ink, fontSize = 16.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            SmallAction("Back to keyboard", orchestrator::backToKeyboard)
                        }
                        if (ui.generating) {
                            GenerationProgressHeader(ui.status)
                        } else if (ui.status.isNotBlank()) {
                            Text(ui.status, color = Muted, fontSize = 11.sp)
                        }
                        ui.suggestions.forEach { suggestion ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { orchestrator.insertSuggestion(suggestion) },
                                shape = CardShape,
                                color = Card,
                                border = BorderStroke(1.dp, Line),
                            ) {
                                Text(suggestion, modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                                    color = Ink, fontSize = 13.sp)
                            }
                        }
                    }
                    ui.generating -> GenerationBody(ui.status)
                    else -> {
                        Text(ui.status.ifBlank { "Capture a chat to find your next reply." },
                            color = Muted, fontSize = 13.sp)
                        if (!ui.busy) {
                            SmallAction("Tell Repli what you want to say (optional)", orchestrator::openGuidance)
                            Text("For longer chats, start on the oldest page and capture each newer view.",
                                color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        if (ui.reviewing && ui.generationError != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = CardShape,
                color = ErrorCard,
                border = BorderStroke(1.dp, ErrorLine),
            ) {
                Text(
                    ui.generationError.orEmpty(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = ErrorText, fontSize = 12.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (ui.reviewing && !ui.showChatPicker) {
            if (ui.canUndoReviewRemoval) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Message removed", color = Muted, fontSize = 11.sp,
                        modifier = Modifier.weight(1f))
                    SmallAction("Undo", orchestrator::undoReviewRemoval)
                }
            }
            Spacer(Modifier.height(12.dp))
            ReviewDirectionCard(ui.instructions, orchestrator::openGuidance)
            Spacer(Modifier.height(10.dp))
        }
        when {
            ui.showChatPicker -> PanelFooter("Done", onClick = orchestrator::closeChatPicker)
            ui.reviewing -> PanelFooter(
                "Repli, give me ideas",
                enabled = ui.reviewTurns.isNotEmpty() || ui.replyIntent == ReplyIntent.FRESH_START,
                onClick = orchestrator::useReviewedContext,
            )
            ui.approval != null -> PanelFooter("Generate cloud replies", onClick = orchestrator::approveGenerate)
            ui.suggestions.isNotEmpty() -> MoreRepliesFooter(
                onDirection = orchestrator::openGuidance,
                onMore = orchestrator::requestMoreSuggestions,
                canGenerateMore = ui.canGenerateMore,
                busy = ui.busy,
            )
            !ui.busy && ui.suggestions.isEmpty() -> PanelFooter("Capture chat", onClick = orchestrator::beginSuggestion)
        }
    }
}

@Composable
private fun GenerationProgressHeader(status: String) {
    Surface(modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = CardShape, color = Card, border = BorderStroke(1.dp, Line)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(25.dp), color = Accent,
                strokeWidth = 2.5.dp)
            Column {
                Text("Repli is putting ideas together", color = Ink, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(status.ifBlank { "Finding your Repli ideas…" }, color = Muted,
                    fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GenerationBody(status: String) {
    GenerationProgressHeader(status)
    Text("Your next replies will appear here", color = Muted, fontSize = 12.sp,
        fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 5.dp))
    val transition = rememberInfiniteTransition(label = "reply placeholders")
    val pulse by transition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_000), RepeatMode.Reverse),
        label = "placeholder opacity",
    )
    repeat(3) { index ->
        Surface(modifier = Modifier.fillMaxWidth(), shape = CardShape, color = Card,
            border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.fillMaxWidth(if (index == 1) 0.74f else 0.88f).height(8.dp)
                    .background(AccentSoft.copy(alpha = pulse), RoundedCornerShape(8.dp)))
                Box(Modifier.fillMaxWidth(if (index == 2) 0.52f else 0.64f).height(8.dp)
                    .background(AccentSoft.copy(alpha = pulse), RoundedCornerShape(8.dp)))
            }
        }
    }
    Text("You choose and edit a reply before it goes into your chat.", color = Muted,
        fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
}

@Composable
private fun PersonaPicker(
    options: List<ChatOption>,
    personas: List<PersonaOption>,
    selectedName: String?,
    selectedPersonaId: String,
    onSelect: (String?) -> Unit,
    onPersonaSelect: (String) -> Unit,
) {
    Text("Persona for ${selectedName ?: "this reply"}", color = Ink, fontSize = 16.sp,
        fontWeight = FontWeight.Bold)
    personas.forEach { persona ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onPersonaSelect(persona.id) },
                shape = CardShape,
                color = if (persona.id == selectedPersonaId) AccentSoft else Card,
                border = BorderStroke(1.dp, if (persona.id == selectedPersonaId) Accent else Line),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(persona.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(persona.description, color = Muted, fontSize = 11.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
    }
    Text(if (selectedName == null) "Applies to this reply only."
        else "Saved as this chat's persona for future replies.", color = Muted, fontSize = 11.sp)
    Text("Chat", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    PersonaChatOption("This reply", "Choose a persona for one reply", selectedName == null) { onSelect(null) }
    options.forEach { option ->
        PersonaChatOption(option.name, option.styleName, option.name == selectedName) { onSelect(option.id) }
    }
}

@Composable
private fun PersonaChatOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = CardShape,
        color = if (selected) AccentSoft else Card,
        border = BorderStroke(1.dp, if (selected) Accent else Line),
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 8.dp)) {
            Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ReviewBody(
    turns: List<ConversationTurn>,
    sourceStatus: String,
    onRemove: (Int) -> Unit,
) {
    if (sourceStatus.isNotBlank()) Text(sourceStatus, color = Muted, fontSize = 11.sp,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    turns.forEachIndexed { index, turn ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            color = if (turn.fromMe) AccentSoft else Card,
            border = BorderStroke(1.dp, Line),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 11.dp, end = 3.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(if (turn.fromMe) "You" else "Them", color = Accent,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(turn.text, color = Ink, fontSize = 13.sp, lineHeight = 17.sp)
                }
                IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove message ${index + 1}",
                        tint = Muted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ReplyIntentSelector(selected: ReplyIntent, onSelect: (ReplyIntent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(ReplyIntent.REPLY to "Reply to this chat", ReplyIntent.FRESH_START to "Start something new").forEach { (intent, label) ->
            Surface(
                modifier = Modifier.weight(1f).selectable(
                    selected = selected == intent, role = Role.RadioButton, onClick = { onSelect(intent) },
                ),
                shape = RoundedCornerShape(14.dp),
                color = if (selected == intent) AccentSoft else Card,
                border = BorderStroke(1.dp, if (selected == intent) Accent else Line),
            ) {
                Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    color = if (selected == intent) Accent else Muted,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ReviewDirectionCard(instructions: String?, onDirection: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        .clickable(onClick = onDirection),
        shape = CardShape, color = ReplyPromptCard, border = BorderStroke(2.dp, Accent),
        shadowElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).background(Accent, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                Text("✦", color = OnAccent, fontSize = 21.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("How do you want to Repli?", color = Ink, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(instructions ?: "Optional · e.g. I can meet Saturday; keep it friendly.",
                    color = if (instructions == null) Muted else Ink, fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.background(Accent, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                Text(if (instructions == null) "Add" else "Edit", color = OnAccent,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ApprovalBody(
    approval: RepliApprovalCard,
    turns: List<ConversationTurn>,
    onReview: () -> Unit,
    onFullScreen: () -> Unit,
    status: String,
) {
    Text("Review before cloud generation", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Text("${approval.turnCount} messages · ${approval.stylePreset} persona · ${approval.exampleCount} examples",
        color = Muted, fontSize = 12.sp)
    Text("Saved chats may add earlier approved messages and writing style to cloud replies.",
        color = Muted, fontSize = 11.sp)
    if (status.isNotBlank()) Text(status, color = Muted, fontSize = 11.sp)
    approval.instructions?.let {
        Text("What you want to say: $it", color = Ink, fontSize = 12.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SmallAction("Review chats", onReview)
        SmallAction("Full screen", onFullScreen)
    }
    turns.takeLast(3).forEach { turn ->
        Surface(shape = CardShape, color = if (turn.fromMe) AccentSoft else Card,
            border = BorderStroke(1.dp, Line)) {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text(if (turn.fromMe) "You" else "Them", color = Muted, fontSize = 11.sp)
                Text(turn.text, color = Ink, fontSize = 12.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MoreRepliesFooter(
    onDirection: () -> Unit, onMore: () -> Unit, canGenerateMore: Boolean, busy: Boolean,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.weight(1f).height(44.dp).clickable(onClick = onDirection),
            shape = CardShape, color = Card, border = BorderStroke(1.dp, Line)) {
            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                Text("Tell Repli what to change…", color = Muted, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Button(onClick = onMore, enabled = canGenerateMore, modifier = Modifier.height(38.dp),
            shape = CardShape, colors = ButtonDefaults.buttonColors(containerColor = Accent),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            Text(if (busy) "Generating…" else "More suggestions", color = OnAccent, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PanelFooter(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp).height(45.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.height(32.dp).clickable(onClick = onClick).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GuidanceSection(
    modifier: Modifier = Modifier,
    initial: String,
    voice: VoiceRecordingState?,
    voicePreview: String,
    voiceBase: String,
    voiceAccepted: String?,
    voiceAcceptedRev: Int,
    voiceStatus: String?,
    voiceShowSettings: Boolean,
    onApply: (String) -> Unit,
    onCancel: () -> Unit,
    onMic: (String) -> Unit,
    onPause: () -> Unit,
    onResume: (String) -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    onConsumeAccepted: () -> Unit,
    onVoiceSettings: () -> Unit,
) {
    var field by remember { mutableStateOf(initial) }
    var appliedRev by remember { mutableStateOf(0) }
    LaunchedEffect(initial) { field = initial; appliedRev = 0 }
    LaunchedEffect(voiceAcceptedRev) {
        if (voiceAcceptedRev > appliedRev && voiceAccepted != null) {
            appliedRev = voiceAcceptedRev
            field = voiceAccepted
            onConsumeAccepted()
        }
    }
    val voiceBusy = voice?.phase in setOf(VoicePhase.PREPARING, VoicePhase.LISTENING,
        VoicePhase.PAUSING, VoicePhase.PROCESSING)
    LaunchedEffect(voicePreview, voiceBusy) {
        if (voiceBusy && voicePreview.isNotBlank()) field = voicePreview
    }
    LaunchedEffect(voiceBusy) {
        if (!voiceBusy && appliedRev == voiceAcceptedRev && voice != null &&
            voice.phase !in setOf(VoicePhase.REVIEW, VoicePhase.PAUSED)) field = voiceBase
    }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Text("How do you want to Repli?", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Optional · Tell Repli what to say or how it should sound. It uses this with the captured chat.",
                color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = field,
                onValueChange = { if (!voiceBusy && it.length <= 500) field = it },
                placeholder = { Text("e.g. I can meet Saturday. Keep it friendly.", color = Muted) },
                supportingText = { Text("${field.length} / 500", color = Muted, fontSize = 10.sp) },
                minLines = 2,
                maxLines = 3,
                enabled = !voiceBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Ink, unfocusedTextColor = Ink,
                    focusedBorderColor = Accent, unfocusedBorderColor = Line,
                    focusedContainerColor = Card, unfocusedContainerColor = Card,
                ),
            )
            if (voiceStatus != null) Text(voiceStatus, color = Muted, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (voice?.phase) {
                    VoicePhase.LISTENING, VoicePhase.PAUSING -> {
                        SmallAction("Pause", onPause)
                        SmallAction("Stop", onStop)
                    }
                    VoicePhase.PAUSED -> {
                        SmallAction("Resume") { onResume(field) }
                        SmallAction("Stop", onStop)
                        SmallAction("Discard", onDiscard)
                    }
                    VoicePhase.PROCESSING, VoicePhase.PREPARING -> SmallAction("Stop", onStop)
                    else -> SmallAction("Say it aloud") { onMic(field) }
                }
                if (voiceShowSettings) SmallAction("Voice settings", onVoiceSettings)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, enabled = !voiceBusy,
                modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Line)) { Text("Cancel", color = Accent) }
            Button(onClick = { onApply(field) }, enabled = !voiceBusy,
                modifier = Modifier.weight(2f).height(44.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("Save what to say", color = OnAccent)
            }
        }
    }
}

@Composable
fun RepliInlineToneBar(modifier: Modifier = Modifier) {
    val controller = LocalImeController.current.repliReply ?: return
    val ui by controller.uiState.collectAsState()
    Surface(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)
        .height(42.dp),
        color = Card, shape = CardShape, border = BorderStroke(1.dp, Line)) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(enabled = !ui.busy) { controller.openChatPicker() },
                verticalAlignment = Alignment.CenterVertically) {
                Text("Persona", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(" · ${ui.selectedPersonaName}", color = Muted, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = controller::openGuidance, enabled = !ui.busy,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                Text("How to Repli", color = Accent, fontSize = 11.sp)
            }
            TextButton(onClick = controller::clear,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                Text("Clear", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun RepliInlineSuggestionRow(modifier: Modifier = Modifier) {
    val controller = LocalImeController.current.repliReply ?: return
    val ui by controller.uiState.collectAsState()
    val visibleReplies = ui.suggestions.takeLast(3)
    if (visibleReplies.isEmpty()) return
    val replyWidth = minOf(LocalConfiguration.current.screenWidthDp.dp * 0.72f, 300.dp)
    LazyRow(
        modifier = modifier.fillMaxWidth().height(54.dp).background(Paper),
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(visibleReplies) { suggestion ->
            Surface(modifier = Modifier.width(replyWidth).height(44.dp)
                .clickable { controller.insertSuggestion(suggestion) },
                color = Card, shape = CardShape, border = BorderStroke(1.dp, Line)) {
                Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(suggestion, color = Ink, fontSize = 12.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            Button(onClick = controller::openMoreReplies, modifier = Modifier.height(38.dp),
                shape = CardShape, colors = ButtonDefaults.buttonColors(containerColor = Accent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text("More suggestions", color = OnAccent, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun RepliInlineGuidance(modifier: Modifier = Modifier) {
    val controller = LocalImeController.current.repliReply ?: return
    val ui by controller.uiState.collectAsState()
    Surface(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
        color = Card, shape = CardShape, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("How do you want to Repli?", color = Ink, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = controller::cancelGuidance, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close reply request", tint = Muted)
                }
            }
            Text("Optional · Add the point to include or how it should sound.", color = Muted, fontSize = 11.sp)
            Box(Modifier.fillMaxWidth().height(62.dp).background(Paper, RoundedCornerShape(10.dp))
                .padding(10.dp), contentAlignment = Alignment.TopStart) {
                if (ui.guidanceText.isEmpty()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text("▍", color = Accent, fontSize = 13.sp)
                        Text("e.g. Say I'm free next week, and keep it warm", color = Muted,
                            fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(ui.guidanceText + "▍", color = Ink,
                        fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallAction("Clear", controller::clearGuidanceText)
                Text("${ui.guidanceText.length}/500", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                if (ui.voiceStatus != null) Text(ui.voiceStatus.orEmpty(), color = Muted,
                    fontSize = 10.sp, maxLines = 1)
                IconButton(onClick = { controller.startVoice(ui.guidanceText) },
                    modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Mic, contentDescription = "Say what you want to reply",
                        tint = Accent, modifier = Modifier.size(21.dp))
                }
                Button(onClick = controller::applyInlineGuidance,
                    shape = CardShape, colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(if (ui.suggestions.isNotEmpty()) "Generate more" else "Save what to say",
                        color = OnAccent, fontSize = 12.sp)
                }
            }
        }
    }
}
