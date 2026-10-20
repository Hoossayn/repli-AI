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

package dev.patrickgold.florisboard.ime.nlp.latin.repli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LatestPredictionWorkerTest {
    @Test fun `rapid input coalesces waiting queries and never publishes an obsolete word`() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val computed = mutableListOf<String>()
        val published = mutableListOf<String>()
        val worker = LatestPredictionWorker<String, String>(backgroundScope,
            compute = { word ->
                computed += word
                if (word == "h") releaseFirst.await()
                word
            },
            publish = { _, word -> published += word },
        )
        worker.submit("h")
        runCurrent()
        worker.submit("he")
        worker.submit("hel")
        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals(listOf("h", "hel"), computed)
        assertEquals(listOf("hel"), published)
    }

    @Test fun `editor reset invalidates an in flight result`() = runTest {
        val release = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val worker = LatestPredictionWorker<String, String>(backgroundScope,
            compute = { release.await(); it },
            publish = { _, word -> published += word },
        )
        worker.submit("previous editor")
        runCurrent()
        worker.invalidate()
        release.complete(Unit)
        runCurrent()
        assertEquals(emptyList(), published)
    }

    @Test fun `generation is rechecked when publication waits for the editor lock`() = runTest {
        val editorLock = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        lateinit var worker: LatestPredictionWorker<String, String>
        worker = LatestPredictionWorker(backgroundScope,
            compute = { it },
            publish = { request, word ->
                editorLock.await()
                if (worker.isCurrent(request)) published += word
            },
        )
        worker.submit("old")
        runCurrent()
        worker.submit("new")
        editorLock.complete(Unit)
        runCurrent()
        assertEquals(listOf("new"), published)
    }
}
