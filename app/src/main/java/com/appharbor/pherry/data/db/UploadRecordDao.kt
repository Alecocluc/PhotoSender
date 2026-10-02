package com.appharbor.pherry.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadRecordDao {

    @Update
    suspend fun update(record: UploadRecord)

    @Query("SELECT * FROM upload_records WHERE id = :id")
    suspend fun record(id: Long): UploadRecord?

    @Query("SELECT * FROM upload_records WHERE status IN ('PENDING', 'UPLOADING') ORDER BY id ASC")
    suspend fun getPendingAndUploading(): List<UploadRecord>

    @Query("SELECT * FROM upload_records WHERE status = 'FAILED' ORDER BY id ASC")
    suspend fun getFailed(): List<UploadRecord>

    @Query("DELETE FROM upload_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE upload_records SET status = 'PENDING' WHERE status = 'UPLOADING'")
    suspend fun resetUploadingToPending()

    @Query("SELECT * FROM upload_records WHERE receiverId = :receiverId AND libraryId = :libraryId")
    suspend fun recordsForDestination(receiverId: String, libraryId: String): List<UploadRecord>

    @Query("SELECT * FROM upload_records WHERE jobId = :jobId ORDER BY id")
    suspend fun recordsForJob(jobId: String): List<UploadRecord>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewRecords(records: List<UploadRecord>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putJob(job: UploadJob)

    @Query("SELECT * FROM upload_jobs WHERE state NOT IN ('completed', 'cancelled') ORDER BY createdAt")
    suspend fun openJobs(): List<UploadJob>

    @Query("SELECT * FROM upload_jobs WHERE id = :id")
    suspend fun job(id: String): UploadJob?

    @Query("SELECT * FROM upload_jobs ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestJob(): UploadJob?

    @Query("UPDATE upload_jobs SET userPaused = :paused, state = CASE WHEN :paused THEN 'paused' ELSE 'waiting' END WHERE state NOT IN ('completed', 'cancelled')")
    suspend fun pauseOpenJobs(paused: Boolean)

    @Transaction
    suspend fun enqueueJob(job: UploadJob, records: List<UploadRecord>) {
        putJob(job)
        insertNewRecords(records)
    }

    @Transaction
    suspend fun enqueueRepair(job: UploadJob, originals: List<UploadRecord>) {
        putJob(job)
        for (record in originals) {
            // Keep the historical transfer receipt, but stop treating it as proof of a current copy.
            update(record.copy(dedupKey = null))
            insertNewRecords(listOf(record.copy(id = 0, jobId = job.id, uploadId = java.util.UUID.randomUUID().toString(),
                status = UploadStatus.PENDING, progress = 0, uploadedAt = 0, acknowledgedBytes = 0, sentBytes = 0,
                receiptId = "", error = "Missing on the receiver", skipped = false, historyHidden = false)))
        }
    }

    @Query("SELECT COUNT(*) FROM upload_records WHERE receiverId = :receiverId AND libraryId = :libraryId AND status = 'COMPLETED' AND dedupKey IS NOT NULL")
    fun getCompletedCount(receiverId: String, libraryId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM upload_records WHERE receiverId = :receiverId AND libraryId = :libraryId AND status = 'FAILED'")
    fun getFailedCount(receiverId: String, libraryId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM upload_records WHERE receiverId = :receiverId AND libraryId = :libraryId AND status IN ('PENDING', 'UPLOADING')")
    fun getQueuedCount(receiverId: String, libraryId: String): Flow<Int>

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM upload_records WHERE receiverId = :receiverId AND libraryId = :libraryId AND status = 'COMPLETED' AND dedupKey IS NOT NULL")
    fun getTotalTransferredBytes(receiverId: String, libraryId: String): Flow<Long>

    @Query("SELECT COALESCE(MAX(uploadedAt), 0) FROM upload_records WHERE receiverId = :receiverId AND libraryId = :libraryId AND status = 'COMPLETED'")
    fun getLastSyncTimestamp(receiverId: String, libraryId: String): Flow<Long>

    @Query("SELECT * FROM upload_records WHERE receiverId = :receiverId AND libraryId = :libraryId AND status = 'COMPLETED' AND historyHidden = 0 ORDER BY uploadedAt DESC LIMIT :limit")
    fun getRecentCompleted(receiverId: String, libraryId: String, limit: Int): Flow<List<UploadRecord>>

    @Query("UPDATE upload_records SET historyHidden = 1 WHERE receiverId = :receiverId AND libraryId = :libraryId AND status = 'COMPLETED'")
    suspend fun clearCompleted(receiverId: String, libraryId: String)

    @Query("SELECT COUNT(*) FROM upload_records WHERE receiverId = :receiverId AND libraryId = :libraryId AND status = 'COMPLETED' AND historyHidden = 0")
    fun getHistoryCount(receiverId: String, libraryId: String): Flow<Int>
}
