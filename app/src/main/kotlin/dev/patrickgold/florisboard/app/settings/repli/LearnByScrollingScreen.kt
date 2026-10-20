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

import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.repli.capture.LearnByScrollingService
import dev.patrickgold.florisboard.repli.capture.MessageSide
import dev.patrickgold.florisboard.repli.data.LearnedStyleRepository
import dev.patrickgold.florisboard.repli.data.PendingCapture
import dev.patrickgold.florisboard.repli.data.PendingCaptureRepository
import dev.patrickgold.florisboard.repli.data.ProfileRepository
import dev.patrickgold.florisboard.repli.personalization.TextingStyleAnalyzer
import dev.patrickgold.florisboard.repli.profile.VoiceProfile
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.stringRes

private const val MIN_CAPTURE_MESSAGES = 3
private const val LABEL_MAX_CHARS = 180

private data class ReadyInfo(
    val profileName: String,
    val messagesAnalyzed: Int,
    val summary: String,
)

@Composable
fun LearnByScrollingScreen() = FlorisScreen {
    title = stringRes(R.string.repli_learn__title)
    previewFieldVisible = false

    content {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val lifecycleOwner = LocalLifecycleOwner.current

        var profiles by remember { mutableStateOf<List<VoiceProfile>>(emptyList()) }
        var selectedProfileId by remember { mutableStateOf<String?>(null) }
        var messageSide by remember { mutableStateOf(MessageSide.RIGHT) }
        var pending by remember { mutableStateOf<PendingCapture?>(null) }
        var checked by remember(pending) { mutableStateOf(BooleanArray(pending?.messages?.size ?: 0)) }
        var resumeTick by remember { mutableStateOf(0) }
        var needsProfile by remember { mutableStateOf(false) }
        var startedHintVisible by remember { mutableStateOf(false) }
        var captureCancelled by remember { mutableStateOf(false) }
        var showMinError by remember { mutableStateOf(false) }
        var isLearning by remember { mutableStateOf(false) }
        var learnFailed by remember { mutableStateOf(false) }
        var profileGone by remember { mutableStateOf(false) }
        var readyInfo by remember { mutableStateOf<ReadyInfo?>(null) }

        fun refreshAll() {
            scope.launch {
                val appContext = context.applicationContext
                val loaded = withContext(Dispatchers.IO) { ProfileRepository(appContext).profiles() }
                profiles = loaded
                if (selectedProfileId != null && loaded.none { it.id == selectedProfileId }) {
                    selectedProfileId = null
                }
                val capture = withContext(Dispatchers.IO) { PendingCaptureRepository(appContext).get() }
                if (capture == null) {
                    pending = null
                } else {
                    val exists = withContext(Dispatchers.IO) {
                        ProfileRepository(appContext).findById(capture.profileId) != null
                    }
                    if (!exists) {
                        withContext(Dispatchers.IO) { PendingCaptureRepository(appContext).clear() }
                        pending = null
                        profileGone = true
                    } else {
                        pending = capture
                        profileGone = false
                    }
                }
            }
        }

        fun toggleMessage(index: Int) {
            val current = checked
            if (index !in current.indices) return
            checked = current.copyOf().also { it[index] = !it[index] }
        }

        fun discardPending() {
            scope.launch {
                withContext(Dispatchers.IO) { PendingCaptureRepository(context.applicationContext).clear() }
                pending = null
                showMinError = false
            }
        }

        fun approveSelected() {
            val capture = pending ?: return
            val approved = capture.messages.filterIndexed { index, _ -> index < checked.size && checked[index] }
            if (approved.size < MIN_CAPTURE_MESSAGES) {
                showMinError = true
                return
            }
            showMinError = false
            learnFailed = false
            isLearning = true
            scope.launch {
                try {
                    val snapshot = withContext(Dispatchers.IO) {
                        val appContext = context.applicationContext
                        val analyzer = TextingStyleAnalyzer()
                        val snap = analyzer.analyze(approved)
                        val fallback = analyzer.inferFallbackStyle(snap.style)
                        val profileRepo = ProfileRepository(appContext)
                        requireNotNull(profileRepo.updateStyle(capture.profileId, fallback))
                        LearnedStyleRepository(appContext).save(capture.profileId, snap)
                        PendingCaptureRepository(appContext).clear()
                        snap
                    }
                    readyInfo = ReadyInfo(
                        profileName = capture.profileName,
                        messagesAnalyzed = snapshot.style.messagesAnalyzed,
                        summary = snapshot.style.summary(),
                    )
                    pending = null
                    profiles = withContext(Dispatchers.IO) {
                        ProfileRepository(context.applicationContext).profiles()
                    }
                } catch (_: Exception) {
                    learnFailed = true
                } finally {
                    isLearning = false
                }
            }
        }

        val screenCaptureLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val data = result.data
            val profile = profiles.firstOrNull { it.id == selectedProfileId }
            if (result.resultCode == Activity.RESULT_OK && data != null && profile != null) {
                captureCancelled = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        LearnByScrollingService.start(
                            context.applicationContext,
                            result.resultCode,
                            data,
                            profile.id,
                            profile.name,
                            messageSide,
                        )
                    }
                    startedHintVisible = true
                }
            } else if (result.resultCode != Activity.RESULT_OK) {
                captureCancelled = true
            }
        }

        fun startCapture() {
            val profile = profiles.firstOrNull { it.id == selectedProfileId }
            if (profile == null) {
                needsProfile = true
                return
            }
            needsProfile = false
            captureCancelled = false
            startedHintVisible = false
            val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return
            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumeTick++
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(resumeTick) { refreshAll() }

        val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
        val capture = pending

        PreferenceGroup(title = stringRes(R.string.repli_learn__profile_title)) {
            if (profiles.isEmpty()) {
                Preference(
                    icon = Icons.Default.Chat,
                    title = stringRes(R.string.repli_learn__profile_empty_title),
                    summary = stringRes(R.string.repli_learn__profile_empty_summary),
                    onClick = { },
                )
            } else {
                Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    profiles.forEach { profile ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedProfileId = profile.id
                                    needsProfile = false
                                },
                        ) {
                            RadioButton(
                                selected = profile.id == selectedProfileId,
                                onClick = {
                                    selectedProfileId = profile.id
                                    needsProfile = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.name)
                                Text(profile.style.displayName)
                            }
                        }
                    }
                }
            }
        }

        PreferenceGroup(title = stringRes(R.string.repli_learn__side_title)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { messageSide = MessageSide.RIGHT },
                ) {
                    RadioButton(
                        selected = messageSide == MessageSide.RIGHT,
                        onClick = { messageSide = MessageSide.RIGHT },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringRes(R.string.repli_learn__side_right))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { messageSide = MessageSide.LEFT },
                ) {
                    RadioButton(
                        selected = messageSide == MessageSide.LEFT,
                        onClick = { messageSide = MessageSide.LEFT },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringRes(R.string.repli_learn__side_left))
                }
            }
        }

        PreferenceGroup(title = stringRes(R.string.repli_learn__start_title)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                if (selectedProfile != null) {
                    Text(stringRes(R.string.repli_learn__disclosure, "name" to selectedProfile.name))
                    Spacer(Modifier.height(8.dp))
                }
                Text(stringRes(R.string.repli_learn__privacy_note))
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { startCapture() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringRes(R.string.repli_learn__start_action))
                }
                if (needsProfile) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringRes(R.string.repli_learn__needs_profile),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (captureCancelled) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringRes(R.string.repli_learn__cancelled))
                }
                if (startedHintVisible) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringRes(R.string.repli_learn__started_hint))
                }
            }
        }

        PreferenceGroup(title = stringRes(R.string.repli_learn__review_title)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                OutlinedButton(
                    onClick = { refreshAll() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringRes(R.string.repli_learn__review_refresh))
                }
                Spacer(Modifier.height(8.dp))
                when {
                    profileGone -> {
                        Text(
                            text = stringRes(R.string.repli_learn__profile_gone),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    capture == null -> {
                        Text(stringRes(R.string.repli_learn__review_none))
                    }
                    capture.messages.isEmpty() -> {
                        Text(
                            text = stringRes(R.string.repli_learn__review_empty_title),
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringRes(
                                R.string.repli_learn__review_empty_summary,
                                "side" to capture.messageSide.name.lowercase(),
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { discardPending() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringRes(R.string.repli_learn__discard_action))
                        }
                    }
                    else -> {
                        Text(
                            stringRes(
                                R.string.repli_learn__review_captured,
                                "name" to capture.profileName,
                                "count" to capture.messages.size,
                                "side" to capture.messageSide.name.lowercase(),
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        capture.messages.forEachIndexed { index, message ->
                            val label = message.replace('\n', ' ').take(LABEL_MAX_CHARS)
                            val isChecked = index < checked.size && checked[index]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { toggleMessage(index) }
                                    .padding(vertical = 4.dp),
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { toggleMessage(index) },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (showMinError) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringRes(
                                    R.string.repli_learn__min_error,
                                    "min" to MIN_CAPTURE_MESSAGES,
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (learnFailed) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringRes(R.string.repli_learn__learn_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (isLearning) {
                            Spacer(Modifier.height(4.dp))
                            Text(stringRes(R.string.repli_learn__learning))
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { approveSelected() },
                            enabled = !isLearning,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringRes(R.string.repli_learn__learn_action))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { discardPending() },
                            enabled = !isLearning,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringRes(R.string.repli_learn__discard_action))
                        }
                    }
                }
                val ready = readyInfo
                if (ready != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringRes(R.string.repli_learn__ready_title, "name" to ready.profileName),
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringRes(
                            R.string.repli_learn__ready_summary,
                            "count" to ready.messagesAnalyzed,
                            "summary" to ready.summary,
                        ),
                    )
                }
            }
        }
    }
}
