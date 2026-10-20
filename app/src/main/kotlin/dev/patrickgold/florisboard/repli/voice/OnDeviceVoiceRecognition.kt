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

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import dev.patrickgold.florisboard.BuildConfig
import java.util.Locale

class OnDeviceVoiceRecognition(private val context: Context) : VoiceRecognitionFactory {
    override fun available(): Boolean = Build.VERSION.SDK_INT >= 31 &&
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    override fun create(): VoiceRecognition {
        check(available())
        if (Build.VERSION.SDK_INT < 31) error("On-device recognition requires Android 12 or newer")
        // Never substitute the generic recognizer: it may send audio to a remote service.
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        return object : VoiceRecognition {
            private var cancelled = false
            private val gate = VoicePreparationGate()
            private var language: String? = null
            private var preparationFailure: VoiceFailure? = null
            private var preparationCode: Int? = null
            private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            override fun prepare() = gate.prepare(::querySupport)

            private fun querySupport() {
                if (Build.VERSION.SDK_INT >= 33) querySupport33() else gate.complete()
            }

            @RequiresApi(33)
            private fun querySupport33() {
                val began = SystemClock.elapsedRealtime()
                recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (cancelled) return
                        if (BuildConfig.DEBUG) Log.d("RepliVoice", "preflight_ms=${SystemClock.elapsedRealtime() - began} installed_models=${support.installedOnDeviceLanguages.size}")
                        language = VoiceGuidanceText.installedLanguage(Locale.getDefault().toLanguageTag(), support.installedOnDeviceLanguages)
                        preparationFailure = when {
                            language != null -> null
                            support.installedOnDeviceLanguages.isEmpty() && support.supportedOnDeviceLanguages.isEmpty() && support.pendingOnDeviceLanguages.isEmpty() -> null
                            support.installedOnDeviceLanguages.isEmpty() -> VoiceFailure.MODEL_MISSING
                            else -> VoiceFailure.LANGUAGE
                        }
                        gate.complete()
                    }
                    override fun onError(error: Int) {
                        if (cancelled) return
                        // Unsupported metadata queries still use this SAME explicit local provider once.
                        if (error != SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) {
                            preparationFailure = failure(error); preparationCode = error
                        }
                        gate.complete()
                    }
                })
            }

            override fun start(onReady: () -> Unit, onResult: (String?) -> Unit, onError: (VoiceFailure, Int?) -> Unit, onPartial: (String) -> Unit) {
                val began = SystemClock.elapsedRealtime()
                fun reportError(code: Int) {
                    if (cancelled) return
                    if (BuildConfig.DEBUG) Log.d("RepliVoice", "native_error=$code")
                    onError(failure(code), code)
                }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (!cancelled) {
                            if (BuildConfig.DEBUG) Log.d("RepliVoice", "ready_ms=${SystemClock.elapsedRealtime() - began}")
                            onReady()
                        }
                    }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit // Never retain audio.
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!cancelled) partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.let(onPartial)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onResults(results: Bundle?) {
                        if (!cancelled) onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                    }
                    override fun onError(error: Int) = reportError(error)
                })
                gate.listen(::querySupport) {
                    if (cancelled) return@listen
                    preparationFailure?.let { onError(it, preparationCode); return@listen }
                    language?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
                    try { recognizer.startListening(intent) }
                    catch (_: SecurityException) { onError(VoiceFailure.PERMISSION, null) }
                    catch (_: Exception) { onError(VoiceFailure.SERVICE, null) }
                }
            }
            override fun stop() = recognizer.stopListening()
            override fun cancel() { cancelled = true; gate.close(); recognizer.cancel() }
            override fun close() { cancelled = true; gate.close(); recognizer.destroy() }
        }
    }

    private fun failure(code: Int) = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceFailure.PERMISSION
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceFailure.NO_SPEECH
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> VoiceFailure.LANGUAGE
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceFailure.MODEL_MISSING
        SpeechRecognizer.ERROR_AUDIO -> VoiceFailure.AUDIO
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_CLIENT -> VoiceFailure.SERVICE
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceFailure.BUSY
        else -> VoiceFailure.FAILED
    }
}

/** In-process, debug-only seam. Never selectable by Intent or another app. */
internal object VoiceGuidanceDependencies {
    private var testFactory: VoiceRecognitionFactory? = null
    fun factory(context: Context): VoiceRecognitionFactory =
        testFactory?.takeIf { BuildConfig.DEBUG } ?: OnDeviceVoiceRecognition(context)
    fun setTestFactory(value: VoiceRecognitionFactory?) { check(BuildConfig.DEBUG); testFactory = value }
}
