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
