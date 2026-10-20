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

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One running calculation and at most one waiting snapshot; obsolete work never publishes. */
class LatestPredictionWorker<K, V>(
    scope: CoroutineScope,
    private val compute: suspend (K) -> V,
    private val publish: suspend (Request<K>, V) -> Unit,
) {
    data class Request<K>(val generation: Long, val key: K)
    private val generation = AtomicLong()
    private val requests = Channel<Request<K>>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                if (!isCurrent(request)) continue
                val result = compute(request.key)
                if (isCurrent(request)) publish(request, result)
            }
        }
    }

    @Synchronized fun submit(key: K) {
        requests.trySend(Request(generation.incrementAndGet(), key))
    }

    @Synchronized fun invalidate() { generation.incrementAndGet() }

    // Call again inside the editor's state lock: publication itself may have waited for that lock.
    fun isCurrent(request: Request<K>): Boolean = generation.get() == request.generation
}
