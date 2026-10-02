package com.appharbor.pherry.data.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Cancelling work closes its socket immediately, including an in-flight request body. */
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

class TransferHttpException(val code: Int, message: String, val protocolCode: String = "") : IOException(message) {
    val retryable: Boolean get() = code == 408 || code == 429 || code in 500..506 || code in 508..599 ||
        (code == 409 && protocolCode in setOf("OFFSET_CHANGED", "UPLOAD_BUSY")) ||
        (code == 410 && protocolCode == "UPLOAD_EXPIRED")
}

class TransferApi(private val client: OkHttpClient, val connection: ReceiverConnection) {
    suspend fun json(path: String, method: String = "GET", payload: JSONObject? = null): JSONObject {
        val body = if (method == "GET") null else (payload ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())
        return request(path, method, body)
    }

    suspend fun request(path: String, method: String, body: RequestBody?, offset: Long? = null): JSONObject {
        val request = Request.Builder().url(connection.baseUrl + path)
            .header("Authorization", "Bearer ${connection.credential}")
            .header("X-Pherry-Library", connection.identity.libraryId)
            .header("X-Pherry-Receiver", connection.identity.deviceId)
            .apply { if (offset != null) header("Upload-Offset", offset.toString()) }
            .method(method, body).build()
        return client.newCall(request).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) throw TransferHttpException(response.code, json.optString("error", "Receiver returned ${response.code}"), json.optString("code"))
            if (json.optString("libraryId").let { it.isNotBlank() && it != connection.identity.libraryId }) {
                throw TransferHttpException(409, "The computer changed its destination folder. Review and start a new backup.", "DESTINATION_CHANGED")
            }
            json
        }
    }
}
