package com.appharbor.pherry.data.upload

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class TransferPipelineTest {
    @Test fun aLongVideoDoesNotHoldUpPhotosBeyondTheFirstBatch() = runBlocking {
        val releaseVideo = CompletableDeferred<Unit>()
        val laterPhoto = CompletableDeferred<Unit>()
        val completed = mutableListOf<Int>()
        val task = launch {
            transferPipeline((0..100).toList(), slots = 3, shouldStop = { false },
                prepare = { it }, inspect = { it }, transfer = { item ->
                    if (item == 0) releaseVideo.await()
                    completed.add(item)
                    if (item == 100) laterPhoto.complete(Unit)
                })
        }
        try {
            withTimeout(5000) { laterPhoto.await() }
            assertFalse("The video is still uploading", 0 in completed)
            assertEquals(100, completed.size)
            releaseVideo.complete(Unit)
            withTimeout(5000) { task.join() }
            assertEquals((0..100).toSet(), completed.toSet())
        } finally { task.cancelAndJoin() }
    }

    @Test fun twentyThousandFilesUseBoundedReadAheadAndCancellationStopsAllWorkers() = runBlocking {
        val blocked = CompletableDeferred<Unit>()
        val allSlotsStarted = CompletableDeferred<Unit>()
        val slots = 6
        var prepared = 0
        var running = 0
        var started = 0
        val task = launch {
            transferPipeline((1..20_000).toList(), slots, shouldStop = { false },
                prepare = { prepared++; it }, inspect = { it }, transfer = {
                    started++; running++
                    if (running == slots) allSlotsStarted.complete(Unit)
                    try { blocked.await() } finally { running-- }
                })
        }
        withTimeout(5000) { allSlotsStarted.await() }
        repeat(20) { yield() }
        assertEquals(slots, started)
        // Two bounded queues, one inspection page, active preparers and active upload workers.
        assertTrue("Prepared $prepared of 20,000 while uploads were blocked", prepared <= slots * 8)
        task.cancelAndJoin()
        assertEquals(0, running)
        val afterCancellation = prepared
        repeat(5) { yield() }
        assertEquals(afterCancellation, prepared)
    }

    @Test fun anOutageStopsQueuedWorkWithoutDeadlockingTheCoordinator() = runBlocking {
        val stop = AtomicBoolean()
        var transferred = 0
        withTimeout(5000) {
            transferPipeline((1..20_000).toList(), 3, shouldStop = stop::get,
                prepare = { it }, inspect = { it }, transfer = {
                    transferred++
                    stop.set(true)
                })
        }
        assertEquals(1, transferred)
    }

    @Test fun unreadableOriginalsDoNotPreventTheRestFromTransferring() = runBlocking {
        val transferred = mutableListOf<Int>()
        transferPipeline((1..20).toList(), 3, shouldStop = { false },
            prepare = { it.takeUnless { value -> value % 2 == 0 } },
            inspect = { it }, transfer = { transferred.add(it); Unit })
        assertEquals((1..20 step 2).toSet(), transferred.toSet())
    }
}
