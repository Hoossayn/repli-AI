/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.keyboard3.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFold
import androidx.compose.ui.util.fastForEach
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchKey
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchKeyboard
import dev.patrickgold.florisboard.ime.nlp.latin.repli.GlidePoint
import dev.patrickgold.florisboard.ime.nlp.latin.repli.GlideKey
import dev.patrickgold.florisboard.ime.nlp.latin.repli.TapEvidence
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.k3lp.model.K3Model
import org.k3lp.lib.text.K3String
import org.k3lp.model.layer.K3LayerId
import kotlin.math.pow
import kotlin.time.Duration

sealed interface TrackedPointer {
    val id: PointerId
    val down: PointerInputChange
    val downKey: TouchKey

    data class Peek(
        override val id: PointerId,
        override val down: PointerInputChange,
        override val downKey: TouchKey,
        val downLayerId: K3LayerId,
        val downInputShiftState: InputShiftState,
        val peekLayerId: K3LayerId,
        val peekKey: TouchKey?,
        val peekLine: PeekLine?,
        val peekMustSwitchBack: Boolean,
    ) : TrackedPointer

    data class Output(
        override val id: PointerId,
        override val down: PointerInputChange,
        override val downKey: TouchKey,
        val longPress: LongPress,
        val repeatJob: Job?,
        val tap: TapEvidence? = null,
    ) : TrackedPointer
}

data class TrackedMultiTapSeq(
    val touchKey: TouchKey,
    val multiTapIndex: Int,
    val commitJob: Job?,
    val commit: suspend () -> Unit,
    val cycleNext: suspend () -> Unit,
)

data class PeekLine(
    val start: Offset,
    val end: Offset,
)

data class GlideTrailSnapshot(
    val points: List<GlidePoint> = emptyList(),
    val active: Boolean = false,
    val version: Long = 0,
)

private data class LastUpKey(
    val touchKey: TouchKey,
    val uptimeMillis: Long,
)

// TODO the sync of this class is a bit messy
class PointerTracker(
    val touchKeyboard: TouchKeyboard,
    val imeController: ImeController,
    val interactionController: InteractionController,
    val showSimplePopups: Boolean,
    val peekDistanceSqMin: Float,
) {
    private val mutationGuard = Mutex()

    val trackedPeekPointer: StateFlow<TrackedPointer.Peek?>
        field = MutableStateFlow(null)

    val trackedOutputPointer: StateFlow<TrackedPointer.Output?>
        field = MutableStateFlow(null)

    val trackedMultiTapSeq: StateFlow<TrackedMultiTapSeq?>
        field = MutableStateFlow(null)

    private var lastUpKeyState: LastUpKey? = null
    private data class PendingGlide(
        val id: PointerId,
        val token: Long,
        val size: IntSize,
        val layer: K3LayerId,
        val keys: List<GlideKey>,
        val stroke: GlideStroke,
        val activationDeadline: Long,
    )
    private var glide: PendingGlide? = null
    private val downPointers = mutableSetOf<PointerId>()
    private val blockedGlidePointers = mutableSetOf<PointerId>()
    val glideTrail: StateFlow<GlideTrailSnapshot>
        field = MutableStateFlow(GlideTrailSnapshot())

    fun clearGlideTrail(version: Long) {
        glideTrail.update { if (it.version == version) it.copy(points = emptyList()) else it }
    }

    private fun publishTrail(points: List<GlidePoint>, active: Boolean) {
        glideTrail.update { GlideTrailSnapshot(points, active, it.version + 1) }
    }

    private fun cancelGlide() {
        glide?.let {
            imeController.cancelGlide(it.token)
            if (it.stroke.active) publishTrail(it.stroke.points, false)
        }
        glide = null
    }

    private fun sampleGlide(change: PointerInputChange, size: IntSize): Boolean {
        val current = glide?.takeIf { it.id == change.id } ?: return false
        val output = trackedOutputPointer.value
        if (current.size != size || output == null || output.longPress.shouldShowExtendedPopup() ||
            (!current.stroke.active && change.uptimeMillis > current.activationDeadline)) {
            if (current.stroke.active) blockedGlidePointers.add(current.id)
            cancelGlide()
            return false
        }
        fun add(position: Offset, time: Long) {
            val letter = (touchKeyboard.findKey(current.layer, position.normalized(size))?.attrs?.output as? K3String)
                ?.toText()?.singleOrNull()?.lowercaseChar()?.takeIf { it in 'a'..'z' }
            current.stroke.add(GlidePoint(position.x, position.y), time, letter)
        }
        change.historical.forEach { add(it.position, it.uptimeMillis) }
        add(change.position, change.uptimeMillis)
        if (current.stroke.rejected) {
            // An active stroke must never fall back to inserting its starting key.
            blockedGlidePointers.add(current.id)
            cancelGlide()
            return false
        }
        if (current.stroke.active) {
            output.longPress.extendedJob?.cancel()
            output.repeatJob?.cancel()
            trackedOutputPointer.value = output.copy(longPress = LongPress.None, repeatJob = null)
            publishTrail(current.stroke.points, true)
            imeController.updateGlide(current.token, current.stroke.points, current.keys)
            return true
        }
        return false
    }

    suspend fun mutateLocked(action: suspend PointerTracker.() -> Unit) {
        mutationGuard.withLock { action() }
    }

    context(scope: CoroutineScope)
    suspend fun handleDown(down: PointerInputChange, size: IntSize) {
        val hadOtherPointer = downPointers.any { it != down.id }
        downPointers.add(down.id)
        glide?.let { previous ->
            val wasActive = previous.stroke.active
            if (wasActive) blockedGlidePointers.add(previous.id)
            cancelGlide()
            if (wasActive) {
                blockedGlidePointers.add(down.id)
                cancelOutput()
            }
        }
        if (blockedGlidePointers.isNotEmpty()) {
            blockedGlidePointers.add(down.id)
            return
        }
        val lastUpKey = lastUpKeyState

        trackedPeekPointer.value?.let { trackedPointer ->
            if (trackedPointer.peekKey != null) {
                handleUp(down.copy(id = trackedPointer.id), size)
            }
        }
        trackedOutputPointer.value?.let { trackedPointer ->
            handleUp(down.copy(id = trackedPointer.id), size)
        }

        val downLayerId = imeController.snapshotState().touchLayerId
        val downKey = touchKeyboard.findKey(downLayerId, down.position.normalized(size)) ?: return

        val timingOptions = interactionController.activeTimingOptions.value
        val keyRepeatTimeout = timingOptions.getKeyRepeatTimeout(downKey.attrs.output)
        val keyRepeatDelay = timingOptions.getKeyRepeatDelay(downKey.attrs.output)
        val longPressTimeout = timingOptions.getLongPressTimeout(downKey.attrs.output)
        val doubleTapTimeout = timingOptions.doubleTapTimeout

        val peekLayerId = downKey.attrs.layerId
        if (peekLayerId != null) {
            // this is a peek key action
            trackedMultiTapSeq.value?.let { multiTapSeq ->
                multiTapSeq.commit()
            }
            val downInputShiftState: InputShiftState
            imeController.updateState {
                downInputShiftState = state.flags.inputShiftState
                if (downKey.isShiftKey) {
                    val isDoubleTap = lastUpKey != null && lastUpKey.touchKey.isShiftKey &&
                        (down.uptimeMillis - lastUpKey.uptimeMillis) < doubleTapTimeout.inWholeMilliseconds
                    cycleInputShiftState(isDoubleTap)
                } else {
                    switchTouchLayer(peekLayerId)
                }
            }
            trackedPeekPointer.value = TrackedPointer.Peek(
                id = down.id,
                down = down,
                downKey = downKey,
                downLayerId = downLayerId,
                downInputShiftState = downInputShiftState,
                peekLayerId = peekLayerId,
                peekKey = null,
                peekLine = null,
                peekMustSwitchBack = false,
            )
            interactionController.performFeedback(InteractionKind.KeyPress)
        } else {
            trackedOutputPointer.value = TrackedPointer.Output(
                id = down.id,
                down = down,
                downKey = downKey,
                tap = (downKey.attrs.output as? K3String)?.toText()?.singleOrNull()?.let { letter ->
                    val position = down.position.normalized(size)
                    val geometry = touchKeyboard.predictionKeys(downLayerId)
                    TapEvidence.from(letter, position.x, position.y, down.uptimeMillis,
                        geometry.hashCode().toString(), geometry)
                },
                longPress = if (downKey.isSuitableForPopup) {
                    val anchorBounds = downKey.bounds.let { bounds ->
                        // TODO this is only tested for PHONE_PORTRAIT, test+fix other form factors!
                        val w = 0.1f
                        val h = bounds.height * 0.9f
                        val x = bounds.bottomCenter.x - w / 2f
                        val y = bounds.bottom - h * 2.3f
                        Rect(
                            offset = Offset(x, y),
                            size = Size(w, h),
                        )
                    }
                    LongPress(
                        anchorBounds = anchorBounds,
                        simpleBounds = if (showSimplePopups && downKey.isSuitableForSimplePopup) {
                            anchorBounds.copy(
                                bottom = anchorBounds.bottom + anchorBounds.height * 1.2f,
                            )
                        } else Rect.Zero,
                        simpleLabel = downKey.display,
                        simpleIndicateExtended = downKey.isSuitableForExtendedPopup,
                        extendedBounds = Rect.Zero,
                        extendedKeys = downKey.extendedPopupKeys,
                        extendedFocusedIndex = 0,
                        extendedJob = if (downKey.isSuitableForExtendedPopup) {
                            launchExtendedLongPressJob(longPressTimeout)
                        } else null,
                    )
                } else LongPress.None,
                repeatJob = if (downKey.isRepeatable) {
                    scope.launch {
                        delay(keyRepeatTimeout)
                        while (isActive) {
                            imeController.updateState {
                                downKey.attrs.output?.let { emit(it) }
                            }
                            interactionController.performFeedback(InteractionKind.KeyRepeat)
                            delay(keyRepeatDelay)
                        }
                    }
                } else null,
            )
            trackedMultiTapSeq.value.let { multiTapSeq ->
                if (multiTapSeq != null) {
                    if (multiTapSeq.touchKey !== downKey) {
                        multiTapSeq.commit()
                    } else if (downKey.multiTapKeys.isNotEmpty()) {
                        multiTapSeq.cycleNext()
                    }
                } else if (downKey.multiTapKeys.isNotEmpty()) {
                    val commit = suspend commit@{
                        val multiTapSeq = trackedMultiTapSeq.value ?: return@commit
                        val multiTapKey = multiTapSeq.touchKey.multiTapKeys.getOrNull(multiTapSeq.multiTapIndex)
                            ?: return@commit
                        val output = multiTapKey.data.output ?: return@commit
                        imeController.updateState {
                            emit(output)
                        }
                        trackedMultiTapSeq.value = null
                    }
                    val cycleNext = suspend cycleNext@{
                        val multiTapSeq = trackedMultiTapSeq.value ?: return@cycleNext
                        multiTapSeq.commitJob?.cancel()
                        val newIndex = (multiTapSeq.multiTapIndex + 1) % multiTapSeq.touchKey.multiTapKeys.size.fastCoerceAtLeast(1)
                        trackedMultiTapSeq.value = multiTapSeq.copy(
                            multiTapIndex = newIndex,
                            commitJob = null,
                        )
                    }
                    trackedMultiTapSeq.value = TrackedMultiTapSeq(
                        touchKey = downKey,
                        multiTapIndex = 0,
                        commitJob = null,
                        commit = commit,
                        cycleNext = cycleNext,
                    )
                }
            }
            if (!hadOtherPointer && trackedMultiTapSeq.value == null && size.width > 0 && size.height > 0) {
                val letter = (downKey.attrs.output as? K3String)?.toText()?.singleOrNull()
                    ?.lowercaseChar()?.takeIf { it in 'a'..'z' }
                val token = if (letter != null) imeController.beginGlide() else null
                if (token != null && letter != null) {
                    val keys = touchKeyboard.predictionKeys(downLayerId).map {
                        GlideKey(it.letter, it.centerX * size.width, it.centerY * size.height,
                            it.width * size.width, it.height * size.height)
                    }
                    glide = PendingGlide(down.id, token, size, downLayerId, keys,
                        GlideStroke(GlidePoint(down.position.x, down.position.y), down.uptimeMillis,
                            letter, downKey.bounds.width * size.width, size.width, size.height),
                        down.uptimeMillis + longPressTimeout.inWholeMilliseconds.coerceIn(1, 5000))
                }
            }
            interactionController.performFeedback(InteractionKind.KeyPress)
        }
    }

    suspend fun handleMove(move: PointerInputChange, size: IntSize) {
        if (move.id in blockedGlidePointers) return
        if (sampleGlide(move, size)) return
        if (move.id in blockedGlidePointers) { cancelOutput(); return }
        trackedPeekPointer.value?.takeIf { it.id == move.id }?.let { trackedPointer ->
            val distanceSq = (move.position - trackedPointer.down.position).getDistanceSquared()
            if (trackedPointer.peekLine != null || distanceSq >= peekDistanceSqMin) {
                val newPeekKey = touchKeyboard.findKey(trackedPointer.peekLayerId, move.position.normalized(size))
                trackedPeekPointer.value = trackedPointer.copy(
                    peekKey = newPeekKey,
                    peekLine = PeekLine(trackedPointer.down.position, move.position),
                    peekMustSwitchBack = true,
                )
            }
        }
        trackedOutputPointer.value?.takeIf { it.id == move.id }?.let { trackedPointer ->
            val longPress = trackedPointer.longPress
            if (longPress.shouldShowExtendedPopup()) {
                val position = move.position.normalized(size).let { position ->
                    Offset(
                        x = (position.x)
                            .fastCoerceIn(longPress.extendedBounds.left, longPress.extendedBounds.right),
                        y = (position.y - longPress.anchorBounds.height)
                            .fastCoerceIn(longPress.extendedBounds.top, longPress.extendedBounds.bottom - 0.01f),
                    )
                }
                val newFocusedIndex = longPress.getNearestKeyIndex(position)
                if (newFocusedIndex != longPress.extendedFocusedIndex) {
                    trackedOutputPointer.value = trackedPointer.copy(
                        longPress = longPress.copy(extendedFocusedIndex = newFocusedIndex),
                    )
                }
            }
        }
    }

    context(scope: CoroutineScope)
    suspend fun handleUp(up: PointerInputChange, size: IntSize) {
        downPointers.remove(up.id)
        if (blockedGlidePointers.remove(up.id)) { cancelOutput(); return }
        sampleGlide(up, size)
        if (blockedGlidePointers.remove(up.id)) { cancelOutput(); return }
        glide?.takeIf { it.id == up.id }?.let { current ->
            glide = null
            if (current.stroke.active && !current.stroke.rejected) {
                cancelOutput()
                publishTrail(current.stroke.points, false)
                imeController.completeGlide(current.token, current.stroke.points, current.keys)
                lastUpKeyState = null
                return
            }
            imeController.cancelGlide(current.token)
        }
        trackedPeekPointer.value?.takeIf { it.id == up.id }?.let { trackedPointer ->
            imeController.updateState {
                trackedPointer.peekKey?.attrs?.output?.let { emit(it) }
                if (trackedPointer.peekMustSwitchBack) {
                    if (trackedPointer.downKey.isShiftKey) {
                        revertCycleInputShiftState(
                            trackedPointer.downLayerId, trackedPointer.downInputShiftState
                        )
                    } else {
                        switchTouchLayer(trackedPointer.downLayerId)
                    }
                }
            }
            trackedPeekPointer.value = null
            lastUpKeyState = LastUpKey(trackedPointer.downKey, up.uptimeMillis)
        }
        trackedOutputPointer.value?.takeIf { it.id == up.id }?.let { trackedPointer ->
            trackedPointer.longPress.extendedJob?.cancel()
            trackedPointer.repeatJob?.cancel()
            imeController.updateState {
                val output = when {
                    trackedPointer.downKey == trackedMultiTapSeq.value?.touchKey -> null
                    trackedPointer.longPress.shouldShowExtendedPopup() -> {
                        trackedPointer.longPress.extendedKeys.getOrNull(trackedPointer.longPress.extendedFocusedIndex)
                            ?.data?.output
                    }
                    else -> trackedPointer.downKey.attrs.output
                }
                output?.let {
                    emitTouch(it, if (it === trackedPointer.downKey.attrs.output) trackedPointer.tap else null)
                }
            }
            trackedPeekPointer.update { it?.copy(peekMustSwitchBack = true) }
            trackedOutputPointer.value = null
            lastUpKeyState = LastUpKey(trackedPointer.downKey, up.uptimeMillis)
        }
        trackedMultiTapSeq.update { multiTapSeq ->
            if (multiTapSeq != null) {
                multiTapSeq.commitJob?.cancel() // should never happen, but still ensure we never have 2 active jobs
                multiTapSeq.copy(
                    commitJob = scope.launch {
                        val timingOptions = interactionController.activeTimingOptions.value
                        delay(timingOptions.multiTapTimeout)
                        mutateLocked {
                            if (trackedOutputPointer.value?.longPress?.extendedBounds?.isEmpty == false) {
                                return@mutateLocked
                            }
                            multiTapSeq.commit()
                        }
                    }
                )
            } else null
        }
    }

    suspend fun cancelNonPresent(changes: List<PointerInputChange>) {
        val present = changes.mapTo(mutableSetOf()) { it.id }
        downPointers.retainAll(present)
        blockedGlidePointers.retainAll(present)
        glide?.takeIf { it.id !in present }?.let { cancelGlide() }
        trackedPeekPointer.value?.id?.let { id ->
            if (changes.fastAll { it.id != id }) {
                cancelPeek()
            }
        }
        trackedOutputPointer.value?.id?.let { id ->
            if (changes.fastAll { it.id != id }) {
                cancelOutput()
            }
        }
    }

    suspend fun cancelAll() {
        cancelGlide()
        downPointers.clear()
        blockedGlidePointers.clear()
        publishTrail(emptyList(), false)
        cancelPeek()
        cancelOutput()
    }

    private suspend fun cancelPeek() {
        val trackedPointer = trackedPeekPointer.getAndUpdate { null } ?: return
        imeController.updateState {
            if (trackedPointer.downKey.isShiftKey) {
                revertCycleInputShiftState(
                    trackedPointer.downLayerId, trackedPointer.downInputShiftState
                )
            } else {
                switchTouchLayer(trackedPointer.downLayerId)
            }
        }
    }

    private suspend fun cancelOutput() {
        cancelMultiTap()
        val trackedPointer = trackedOutputPointer.getAndUpdate { null } ?: return
        trackedPointer.longPress.extendedJob?.cancel()
        trackedPointer.repeatJob?.cancel()
    }

    private suspend fun cancelMultiTap() {
        val multiTapSeq = trackedMultiTapSeq.getAndUpdate { null } ?: return
        multiTapSeq.commitJob?.cancel()
    }

    private fun Offset.normalized(size: IntSize): Offset {
        return Offset(x / size.width, y / size.height)
    }

    context(scope: CoroutineScope)
    private fun launchExtendedLongPressJob(
        longPressTimeout: Duration,
    ): Job = scope.launch(Dispatchers.Default) {
        delay(longPressTimeout)
        mutateLocked {
            trackedMultiTapSeq.value?.let { multiTapSeq ->
                multiTapSeq.commitJob?.cancel()
                trackedMultiTapSeq.value = null
            }
            val trackedPointer = trackedOutputPointer.value ?: return@mutateLocked
            val longPress = trackedPointer.longPress
            val anchorBounds = longPress.anchorBounds
            val extendedKeys = layoutExtendedLongPressKeys(longPress.extendedKeys, anchorBounds)
            val extendedBounds = anchorBounds.copy(
                left = extendedKeys.fastFold(anchorBounds.left) { acc, key -> minOf(acc, key.bounds.left) },
                top = extendedKeys.fastFold(anchorBounds.top) { acc, key -> minOf(acc, key.bounds.top) },
                right = extendedKeys.fastFold(anchorBounds.right) { acc, key -> maxOf(acc, key.bounds.right) },
                bottom = extendedKeys.fastFold(anchorBounds.bottom) { acc, key -> maxOf(acc, key.bounds.bottom) },
            )
            trackedOutputPointer.value = trackedPointer.copy(
                longPress = trackedPointer.longPress.copy(
                    simpleIndicateExtended = false,
                    extendedBounds = extendedBounds,
                    extendedKeys = extendedKeys,
                ),
            )
        }
    }
}

@Composable
fun rememberPointerTracker(
    touchKeyboard: TouchKeyboard,
): PointerTracker {
    val prefs by FlorisPreferenceStore
    val density = LocalDensity.current
    val imeController = LocalImeController.current
    val interactionController = LocalInteractionController.current

    val showSimplePopups by prefs.keyboard.popupEnabled.collectAsState()
    // TODO make configurable
    val peekDistanceSqMin = with(density) { 30.dp.toPx().pow(2) }

    val pointerTracker = remember(touchKeyboard, showSimplePopups) {
        PointerTracker(touchKeyboard, imeController, interactionController, showSimplePopups, peekDistanceSqMin)
    }

    return pointerTracker
}

fun Modifier.trackPointerInput(
    pointerTracker: PointerTracker,
    model: K3Model,
) = this.pointerInput(pointerTracker, model) {
    try {
        coroutineScope {
            val scope = this
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val sizeAtEvent = size
                    scope.launch {
                        pointerTracker.mutateLocked {
                            cancelNonPresent(event.changes)
                        }
                    }
                    event.changes.fastForEach { change ->
                        if (change.changedToDown()) {
                            scope.launch {
                                pointerTracker.mutateLocked {
                                    handleDown(change, sizeAtEvent)
                                }
                            }
                        } else if (change.changedToUp()) {
                            scope.launch {
                                pointerTracker.mutateLocked {
                                    handleUp(change, sizeAtEvent)
                                }
                            }
                        } else if (!change.isConsumed) {
                            scope.launch {
                                pointerTracker.mutateLocked {
                                    handleMove(change, sizeAtEvent)
                                }
                            }
                        }
                        change.consume()
                    }
                }
            }
        }
    } finally {
        withContext(NonCancellable) {
            pointerTracker.mutateLocked {
                cancelAll()
            }
        }
    }
}
