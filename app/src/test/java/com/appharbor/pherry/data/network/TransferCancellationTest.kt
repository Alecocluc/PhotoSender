package com.appharbor.pherry.data.network

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TransferCancellationTest {
    @Test fun cancellingCoroutineCancelsAnHttpRequestWaitingForItsResponse() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = CountDownLatch(1)
            val received = CountDownLatch(1)
            val client = OkHttpClient()
            val peer = launch(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    accepted.countDown()
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) { }
                    received.countDown()
                    // Cancellation must close the socket without waiting for the client's read timeout.
                    assertEquals(-1, input.read())
                }
            }
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/slow").build())
            val request = launch(Dispatchers.IO) { call.awaitResponse().close() }
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            assertTrue(received.await(5, TimeUnit.SECONDS))
            request.cancelAndJoin()
            assertTrue(call.isCanceled())
            withTimeout(5000) { peer.join() }
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
