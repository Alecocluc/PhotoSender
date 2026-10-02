package com.appharbor.pherry.data.network

import androidx.test.platform.app.InstrumentationRegistry
import android.os.Build
import android.content.pm.PackageManager
import com.appharbor.pherry.data.upload.TransferReason
import com.appharbor.pherry.data.upload.UploadChunkBody
import com.appharbor.pherry.data.upload.shouldRetryTransfer
import com.appharbor.pherry.data.upload.transferReason
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Uses the production Kotlin transport against desktop/scripts/integration-receiver.cjs. */
class TransferApiReceiverTest {
    @Before fun allowLocalReceiverAccessOnNewInstall() {
        // Connected tests install a fresh target APK and bypass its normal onboarding prompt.
        // Grant the same declared runtime permission before exercising real LAN requests.
        if (Build.VERSION.SDK_INT >= 37) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val permission = "android.permission.ACCESS_LOCAL_NETWORK"
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
            assertEquals(PackageManager.PERMISSION_GRANTED, instrumentation.targetContext.checkSelfPermission(permission))
        }
    }

    private val endpoint get() = InstrumentationRegistry.getArguments().getString("pherryReceiver")
        ?: "http://10.0.2.2:43210"
    private fun client() = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private fun id() = UUID.randomUUID().toString()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun pair(http: OkHttpClient): TransferApi {
        val health = http.newCall(Request.Builder().url("$endpoint/health").build()).awaitResponse().use {
            assertEquals(200, it.code)
            JSONObject(it.body!!.string())
        }
        assertEquals(2, health.getInt("apiVersion"))
        val body = JSONObject().put("pairingCode", "integration-code").put("clientId", id())
            .put("deviceName", "Android integration").put("credential", EnrollmentCredentials.reuseOrCreate(""))
        val enrollment = http.newCall(Request.Builder().url("$endpoint/pair")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).awaitResponse().use {
            assertEquals(200, it.code)
            JSONObject(it.body!!.string())
        }
        assertEquals(health.getString("deviceId"), enrollment.getString("deviceId"))
        return TransferApi(http, ReceiverConnection(ReceiverIdentity(enrollment.getString("deviceId"),
            enrollment.getString("libraryId")), endpoint, enrollment.getString("credential")))
    }

    private fun metadata(job: String, upload: String, bytes: ByteArray) = JSONObject()
        .put("jobId", job).put("uploadId", upload).put("hash", hash(bytes))
        .put("size", bytes.size).put("fileName", "original.bin").put("bucketName", "Integration")

    private suspend fun startJob(api: TransferApi, job: String, files: Int, bytes: Int) = api.json("/v2/jobs/$job", "PUT",
        JSONObject().put("state", "running").put("totalFiles", files).put("totalBytes", bytes))

    private suspend fun patch(api: TransferApi, upload: String, bytes: ByteArray, offset: Long): JSONObject =
        bytes.inputStream().use { input ->
            var sent = 0L
            val body = UploadChunkBody(input, bytes.size.toLong(), ByteArray(128 * 1024),
                isCancelled = { false }, onBytes = { sent += it })
            api.request("/v2/uploads/$upload", "PATCH", body, offset).also {
                assertEquals(bytes.size.toLong(), sent)
            }
        }

    private suspend fun exists(api: TransferApi, digest: String): JSONObject = api.json("/v2/files/exists", "POST",
        JSONObject().put("hashes", JSONArray(listOf(digest))).put("verify", true))
        .getJSONArray("files").getJSONObject(0)

    @Test fun acknowledgedChunksResumeAndDuplicatePartialsDoNotBlockLaterJobsOrOtherPhones() = runBlocking<Unit> {
        val http = client()
        val resumedHttp = client()
        try {
            val api = pair(http)
            val bytes = ByteArray(5 * 1024 * 1024 + 19) { ((it * 17 + 29) % 251).toByte() }
            val job = id(); val upload = id(); val duplicate = id()
            startJob(api, job, 2, bytes.size * 2)
            val uploadMetadata = metadata(job, upload, bytes)
            assertEquals(0L, api.json("/v2/uploads", "POST", uploadMetadata).getLong("offset"))
            api.json("/v2/uploads", "POST", metadata(job, duplicate, bytes))
            patch(api, duplicate, bytes.copyOfRange(0, 65536), 0)
            val boundary = 4 * 1024 * 1024
            assertEquals(boundary.toLong(), patch(api, upload, bytes.copyOfRange(0, boundary), 0).getLong("offset"))

            // A fresh transport represents reconnect/process recreation; its local offset is not trusted.
            http.connectionPool.evictAll()
            val resumed = TransferApi(resumedHttp, api.connection)
            assertEquals(boundary.toLong(), resumed.json("/v2/uploads", "POST", uploadMetadata).getLong("offset"))
            patch(resumed, upload, bytes.copyOfRange(boundary, bytes.size), boundary.toLong())
            val receipt = resumed.json("/v2/uploads/$upload/complete", "POST")
            assertTrue(receipt.getBoolean("complete"))
            assertFalse(receipt.getBoolean("deduplicated"))
            assertTrue(exists(resumed, hash(bytes)).getBoolean("exists"))

            // Production skipped-file handling abandons its own partial before reporting completion.
            resumed.json("/v2/uploads/$duplicate", "DELETE")
            val finished = resumed.json("/v2/jobs/$job", "PUT", JSONObject().put("state", "completed")
                .put("totalFiles", 2).put("totalBytes", bytes.size * 2).put("completedFiles", 1)
                .put("skippedFiles", 1).put("failedFiles", 0).put("completedBytes", bytes.size))
            assertEquals("completed", finished.getString("state"))
            assertEquals(1, finished.getInt("savedFiles"))
            assertEquals(1, resumed.json("/v2/jobs/$job").getInt("skippedFiles"))

            val otherPhone = pair(resumedHttp)
            assertFalse(exists(otherPhone, hash(bytes)).getBoolean("exists"))
            val otherJob = id(); val otherUpload = id()
            startJob(otherPhone, otherJob, 1, bytes.size)
            otherPhone.json("/v2/uploads", "POST", metadata(otherJob, otherUpload, bytes))
            patch(otherPhone, otherUpload, bytes.copyOfRange(0, boundary), 0)
            patch(otherPhone, otherUpload, bytes.copyOfRange(boundary, bytes.size), boundary.toLong())
            val otherReceipt = otherPhone.json("/v2/uploads/$otherUpload/complete", "POST")
            assertNotEquals(receipt.getString("relativePath"), otherReceipt.getString("relativePath"))
            assertTrue(exists(otherPhone, hash(bytes)).getBoolean("exists"))
            assertTrue(exists(resumed, hash(bytes)).getBoolean("exists"))
        } finally {
            for (transport in listOf(http, resumedHttp)) { transport.connectionPool.evictAll(); transport.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun actionableProtocolFailuresKeepTheirCodes() = runBlocking<Unit> {
        val http = client()
        try {
            val api = pair(http)
            val wrongFolder = TransferApi(http, api.connection.copy(identity = api.connection.identity.copy(libraryId = "wrong-library")))
            val destination = failure { wrongFolder.json("/v2/preflight", "POST", JSONObject()) }
            assertEquals("DESTINATION_CHANGED", destination.protocolCode)
            assertFalse(destination.shouldRetryTransfer())
            assertEquals(TransferReason.DESTINATION_CHANGED, destination.transferReason())

            val revoked = TransferApi(http, api.connection.copy(credential = "invalid-credential"))
            val denied = failure { revoked.json("/v2/preflight", "POST", JSONObject()) }
            assertEquals(401, denied.code)
            assertFalse(denied.shouldRetryTransfer())
            assertEquals(TransferReason.PAIRING_REQUIRED, denied.transferReason())

            val bytes = byteArrayOf(1, 2, 3, 4)
            val job = id(); val upload = id()
            startJob(api, job, 1, bytes.size)
            api.json("/v2/uploads", "POST", metadata(job, upload, bytes))
            val unfinished = failure { api.json("/v2/jobs/$job", "PUT", JSONObject().put("state", "completed").put("completedFiles", 1)) }
            assertEquals("JOB_HAS_PENDING_UPLOADS", unfinished.protocolCode)
            assertFalse(unfinished.shouldRetryTransfer())
            api.json("/v2/uploads/$upload", "DELETE")
            assertEquals("completed", api.json("/v2/jobs/$job", "PUT", JSONObject().put("state", "completed")
                .put("completedFiles", 0).put("failedFiles", 1)).getString("state"))
            val next = id()
            assertEquals("running", startJob(api, next, 1, bytes.size).getString("state"))
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }

    private suspend fun failure(block: suspend () -> Unit): TransferHttpException {
        try { block() } catch (e: TransferHttpException) { return e }
        throw AssertionError("Expected a structured receiver failure")
    }
}
