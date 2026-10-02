package com.appharbor.pherry.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class UploadMigrationTest {
    @Test fun repairPreservesTransferHistoryWithoutCountingAnObsoleteReceiptAsAnotherBackup() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PherryDatabase::class.java).build()
        try {
            val dao = database.uploadRecordDao()
            val original = UploadRecord(mediaStoreId = 1, contentUri = "content://media/1",
                fileName = "photo.jpg", bucketName = "Camera", fileSize = 100,
                receiverId = "pc", libraryId = "folder", dedupKey = "source-receipt", jobId = "original-job",
                status = UploadStatus.COMPLETED, uploadedAt = 1000)
            val originalId = dao.insert(original)
            assertEquals(1, dao.getCompletedCount("pc", "folder").first())
            assertEquals(100L, dao.getTotalTransferredBytes("pc", "folder").first())

            val repair = UploadJob("repair-job", "pc", "folder", 2000)
            dao.enqueueRepair(repair, listOf(original.copy(id = originalId)))
            assertEquals(0, dao.getCompletedCount("pc", "folder").first())
            assertEquals(0L, dao.getTotalTransferredBytes("pc", "folder").first())
            assertEquals(1, dao.getHistoryCount("pc", "folder").first())
            assertNull(dao.record(originalId)!!.dedupKey)

            val pending = dao.recordsForJob(repair.id).single()
            assertNotEquals(originalId, pending.id)
            assertEquals("source-receipt", pending.dedupKey)
            dao.update(pending.copy(status = UploadStatus.COMPLETED, uploadedAt = 3000))
            assertEquals(1, dao.getCompletedCount("pc", "folder").first())
            assertEquals(100L, dao.getTotalTransferredBytes("pc", "folder").first())
            assertEquals(2, dao.getHistoryCount("pc", "folder").first())
            dao.clearCompleted("pc", "folder")
            assertEquals(0, dao.getHistoryCount("pc", "folder").first())
            assertEquals(1, dao.getCompletedCount("pc", "folder").first())
        } finally { database.close() }
    }

    @Test fun versionOneQueueAndHistorySurviveWithoutBecomingUnprovenDestinationReceipts() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pherry-migration-test-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.execSQL("CREATE TABLE upload_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, mediaStoreId INTEGER NOT NULL, contentUri TEXT NOT NULL, fileName TEXT NOT NULL, bucketName TEXT NOT NULL, fileSize INTEGER NOT NULL, md5Hash TEXT NOT NULL, status TEXT NOT NULL, progress INTEGER NOT NULL, uploadedAt INTEGER NOT NULL, serverIp TEXT NOT NULL)")
                old.execSQL("INSERT INTO upload_records VALUES (42, 7, 'content://media/7', 'pending.jpg', 'Camera', 100, '', 'UPLOADING', 20, 0, '192.168.1.2:3210')")
                old.execSQL("INSERT INTO upload_records VALUES (43, 8, 'content://media/8', 'saved.jpg', 'Camera', 200, 'oldhash', 'COMPLETED', 100, 1234, '192.168.1.2:3210')")
                old.version = 1
            }
            val database = Room.databaseBuilder(context, PherryDatabase::class.java, name)
                .addMigrations(PherryDatabase.MIGRATION_1_2).build()
            try {
                    val dao = database.uploadRecordDao()
                    val pending = dao.getPendingAndUploading().single()
                    assertEquals(42L, pending.id)
                    assertEquals("content://media/7", pending.contentUri)
                    assertEquals(UploadStatus.UPLOADING, pending.status)
                    assertEquals("", pending.receiverId)
                    assertNull(pending.dedupKey)
                    val completed = dao.getCompletedSnapshot().single()
                    assertEquals(43L, completed.id)
                    assertEquals("oldhash", completed.md5Hash)
                    assertTrue(dao.recordsForDestination("new-pc", "new-library").isEmpty())
                    dao.putJob(UploadJob("job-test", "pc-a", "folder-a", 123, "paused", true))
                    assertTrue(dao.job("job-test")!!.userPaused)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }
}
