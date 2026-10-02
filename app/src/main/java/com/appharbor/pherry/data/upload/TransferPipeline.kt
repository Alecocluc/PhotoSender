package com.appharbor.pherry.data.upload

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Bounded read-ahead and long-lived upload workers, so a video never holds up the next photo batch. */
internal suspend fun <T, Prepared, Ready> transferPipeline(
    items: List<T>,
    slots: Int,
    shouldStop: () -> Boolean,
    prepare: suspend (T) -> Prepared?,
    inspect: suspend (List<Prepared>) -> List<Ready>,
    transfer: suspend (Ready) -> Unit,
) = coroutineScope {
    require(slots > 0)
    val prepared = Channel<Prepared>(slots * 2)
    val uploads = Channel<Ready>(slots * 2)
    val producer = launch {
        try {
            coroutineScope {
                val next = AtomicInteger()
                repeat(minOf(slots, items.size)) {
                    launch {
                        while (!shouldStop()) {
                            val index = next.getAndIncrement()
                            if (index >= items.size) break
                            prepare(items[index])?.let { prepared.send(it) }
                        }
                    }
                }
            }
        } finally { prepared.close() }
    }
    val workers = List(slots) {
        launch {
            for (item in uploads) {
                // Drain bounded queued work after a recoverable stop, allowing the coordinator to
                // close the queue even when all workers encounter the same receiver outage.
                if (!shouldStop()) transfer(item)
            }
        }
    }
    try {
        while (!shouldStop()) {
            val first = prepared.receiveCatching().getOrNull() ?: break
            val batch = mutableListOf(first)
            while (batch.size < slots * 2) batch.add(prepared.tryReceive().getOrNull() ?: break)
            if (shouldStop()) break
            for (item in inspect(batch)) {
                if (shouldStop()) break
                uploads.send(item)
            }
        }
        uploads.close()
        workers.joinAll()
    } finally {
        producer.cancelAndJoin()
        prepared.cancel()
        uploads.cancel()
        workers.forEach { it.cancelAndJoin() }
    }
}
