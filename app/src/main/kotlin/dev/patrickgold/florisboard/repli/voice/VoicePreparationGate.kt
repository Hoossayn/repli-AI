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

/** Main-thread, single-driver gate: share an in-flight non-recording preflight with one explicit start. */
internal class VoicePreparationGate {
    private var preparing = false
    private var prepared = false
    private var closed = false
    private var started = false
    private var start: (() -> Unit)? = null

    fun prepare(query: () -> Unit) {
        if (closed || preparing || prepared) return
        preparing = true
        query()
    }

    fun listen(query: () -> Unit, action: () -> Unit) {
        if (closed || start != null || started) return
        start = action
        prepare(query)
        dispatch()
    }

    fun complete() { if (!closed) { prepared = true; dispatch() } }
    fun close() { closed = true; start = null }
    private fun dispatch() {
        if (prepared && !closed) {
            val action = start
            start = null
            if (action != null) { started = true; action() }
        }
    }
}
