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

package dev.patrickgold.florisboard.repli.voice

import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyPrivacyPolicy

enum class VoiceFailure { UNAVAILABLE, PERMISSION, NO_SPEECH, LANGUAGE, MODEL_MISSING, AUDIO, SERVICE, BUSY, INTERRUPTED, TIMEOUT, TOO_LONG, FAILED }
enum class VoicePhase { READY, PREPARING, LISTENING, PAUSING, PAUSED, PROCESSING, REVIEW, ERROR, CLOSED }
data class VoiceRecordingState(val phase: VoicePhase = VoicePhase.READY, val transcript: String? = null, val failure: VoiceFailure? = null, val nativeError: Int? = null, val partial: String? = null)

interface VoiceRecognition {
    /** Bind/query the local provider only; must never start audio capture. */
    fun prepare() = Unit
    fun start(onReady: () -> Unit, onResult: (String?) -> Unit, onError: (VoiceFailure, Int?) -> Unit, onPartial: (String) -> Unit = {})
    fun stop()
    fun cancel()
    fun close()
}

interface VoiceRecognitionFactory {
    fun available(): Boolean
    fun create(): VoiceRecognition
}

fun interface VoiceDeadline { fun cancel() }

/** Main-thread recorder; no files, audio buffers, network fallback, or automatic retries. */
class VoiceGuidanceRecorder(
    private val factory: VoiceRecognitionFactory,
    private val schedule: (Long, () -> Unit) -> VoiceDeadline,
    private val changed: (VoiceRecordingState) -> Unit,
) {
    var state = VoiceRecordingState()
        private set
    private var recognition: VoiceRecognition? = null
    private var deadline: VoiceDeadline? = null
    private var attempt = 0L
    val busy get() = state.phase in setOf(VoicePhase.PREPARING, VoicePhase.LISTENING, VoicePhase.PAUSING, VoicePhase.PROCESSING)

    /** Warm only a visible editor's driver. No microphone, retry, or visible error until consent. */
    fun prepare() {
        if (busy || state.phase == VoicePhase.CLOSED || recognition != null ||
            !runCatching { factory.available() }.getOrDefault(false)) return
        try { recognition = factory.create(); recognition?.prepare() }
        catch (_: Exception) { release() }
    }

    fun start(permissionGranted: Boolean) {
        if (busy || state.phase == VoicePhase.CLOSED) return
        if (!permissionGranted) { release(); publish(VoiceRecordingState(VoicePhase.ERROR, failure = VoiceFailure.PERMISSION)); return }
        if (!runCatching { factory.available() }.getOrDefault(false)) {
            release(); publish(VoiceRecordingState(VoicePhase.ERROR, failure = VoiceFailure.UNAVAILABLE)); return
        }
        val token = ++attempt
        try {
            recognition = recognition ?: factory.create()
            publish(VoiceRecordingState(VoicePhase.PREPARING))
            deadline = schedule(START_TIMEOUT_MS) { if (token == attempt && busy) fail(VoiceFailure.TIMEOUT) }
            recognition?.start(
                onReady = {
                    if (token == attempt && state.phase == VoicePhase.PREPARING) {
                        deadline?.cancel()
                        publish(VoiceRecordingState(VoicePhase.LISTENING))
                        deadline = schedule(MAX_RECORDING_MS) { if (token == attempt && busy) stop() }
                    }
                },
                onResult = { text ->
                    if (token == attempt && busy) {
                        val transcript = text?.trim().orEmpty()
                        when {
                            transcript.isBlank() -> if (state.phase == VoicePhase.PAUSING) paused(null) else fail(VoiceFailure.NO_SPEECH)
                            transcript.length > RemoteReplyPrivacyPolicy.MAX_INSTRUCTION_CHARACTERS -> fail(VoiceFailure.TOO_LONG)
                            state.phase == VoicePhase.PAUSING -> paused(transcript)
                            else -> { release(); publish(VoiceRecordingState(VoicePhase.REVIEW, transcript)) }
                        }
                    }
                },
                onError = { error, code ->
                    if (token == attempt && busy) {
                        if (state.phase == VoicePhase.PAUSING && error == VoiceFailure.NO_SPEECH) paused(null)
                        else fail(error, code)
                    }
                },
                onPartial = { text ->
                    if (token == attempt && state.phase == VoicePhase.LISTENING &&
                        text.isNotBlank() && text.length <= RemoteReplyPrivacyPolicy.MAX_INSTRUCTION_CHARACTERS && text != state.partial) {
                        // Revisions replace this provisional preview, never append/commit it.
                        publish(state.copy(partial = text))
                    }
                },
            )
        } catch (_: SecurityException) { fail(VoiceFailure.PERMISSION) }
        catch (_: Exception) { fail(VoiceFailure.FAILED) }
    }

    fun stop() {
        when (state.phase) {
            VoicePhase.PREPARING -> { cancel(); return }
            // Its segment is already in the editor. Don't emit it a second time.
            VoicePhase.PAUSED -> { publish(VoiceRecordingState(VoicePhase.REVIEW)); return }
            VoicePhase.LISTENING, VoicePhase.PAUSING -> Unit
            else -> return
        }
        val alreadyStopping = state.phase == VoicePhase.PAUSING
        deadline?.cancel()
        publish(VoiceRecordingState(VoicePhase.PROCESSING))
        val token = attempt
        deadline = schedule(RESULT_TIMEOUT_MS) { if (token == attempt && busy) fail(VoiceFailure.TIMEOUT) }
        try { if (!alreadyStopping) recognition?.stop() }
        catch (_: Exception) { fail(VoiceFailure.FAILED) }
    }

    /** Android has no suspended local-recognizer stream. Finalize this segment and release the mic. */
    fun pause() {
        if (state.phase != VoicePhase.LISTENING) return
        deadline?.cancel()
        publish(VoiceRecordingState(VoicePhase.PAUSING))
        val token = attempt
        deadline = schedule(RESULT_TIMEOUT_MS) { if (token == attempt && busy) fail(VoiceFailure.TIMEOUT) }
        try { recognition?.stop() }
        catch (_: Exception) { fail(VoiceFailure.FAILED) }
    }

    private fun paused(transcript: String?) { release(); publish(VoiceRecordingState(VoicePhase.PAUSED, transcript)) }

    fun interrupt() { if (busy) fail(VoiceFailure.INTERRUPTED) }

    fun cancel() { if (state.phase != VoicePhase.CLOSED) { release(); publish(VoiceRecordingState()) } }

    fun close() { release(); publish(VoiceRecordingState(VoicePhase.CLOSED)) }

    private fun fail(error: VoiceFailure, code: Int? = null) { release(); publish(VoiceRecordingState(VoicePhase.ERROR, failure = error, nativeError = code)) }

    private fun release() {
        // Invalidate first: cancel/destroy can themselves cause late callbacks.
        attempt++
        deadline?.cancel(); deadline = null
        val old = recognition
        recognition = null
        runCatching { old?.cancel() }
        runCatching { old?.close() }
    }

    private fun publish(value: VoiceRecordingState) { state = value; changed(value) }

    companion object {
        const val START_TIMEOUT_MS = 5_000L
        const val MAX_RECORDING_MS = 30_000L
        const val RESULT_TIMEOUT_MS = 5_000L
    }
}
