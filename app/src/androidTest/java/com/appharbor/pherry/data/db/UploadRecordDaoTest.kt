package com.appharbor.pherry.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UploadRecordDaoTest {
    @Test fun largeReceiptListsStayScopedAndCanBeDeletedBeyondTheBindLimit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PherryDatabase::class.java).build()
        try {
            val dao = db.uploadRecordDao()
            val records = (1L..20_000L).map { id ->
                UploadRecord(id = id, mediaStoreId = id, contentUri = "content://media/$id",
                    fileName = "$id.jpg", bucketName = "Camera", fileSize = 3L * 1024 * 1024,
                    receiverId = "computer", libraryId = "library", jobId = "large-job",
                    dedupKey = "key-$id", status = UploadStatus.COMPLETED)
            }
            dao.enqueueJob(UploadJob("large-job", "computer", "library", 1), records)
            dao.insertNewRecords(listOf(
                records.first().copy(id = 20_001, dedupKey = "failed", status = UploadStatus.FAILED),
                records.first().copy(id = 20_002, dedupKey = "pending", status = UploadStatus.PENDING),
                records.first().copy(id = 20_003, dedupKey = "other-library", libraryId = "other"),
                records.first().copy(id = 20_004, dedupKey = "other-computer", receiverId = "other"),
                records.first().copy(id = 20_005, dedupKey = null),
            ))

            val completed = dao.completedReceiptKeys("computer", "library").toSet()
            assertEquals(records.mapTo(HashSet()) { it.dedupKey }, completed)
            assertEquals(completed + "pending", dao.recordedReceiptKeys("computer", "library").toSet())
            assertEquals(20_000, dao.completedRecordsForDestination("computer", "library").size)
            assertEquals(listOf(20_001L), dao.failedRecordsForDestination("computer", "library").map { it.id })
            assertTrue(dao.hasQueuedRecords("computer", "library"))
            assertFalse(dao.hasQueuedRecords("computer", "other"))

            dao.deleteByIds(records.map { it.id } + 20_002L)
            dao.deleteByIds(emptyList())
            assertTrue(dao.completedReceiptKeys("computer", "library").isEmpty())
            assertFalse(dao.hasQueuedRecords("computer", "library"))
            assertNotNull(dao.record(20_001))
            assertNotNull(dao.record(20_003))
            assertNotNull(dao.record(20_004))
            assertNotNull(dao.record(20_005))
        } finally {
            db.close()
        }
    }
}
