package com.appharbor.photosender.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: UploadRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<UploadRecord>): List<Long>

    @Update
    suspend fun update(record: UploadRecord)

    @Query("SELECT * FROM upload_records WHERE mediaStoreId = :mediaStoreId AND status = 'COMPLETED' LIMIT 1")
    suspend fun getCompletedByMediaStoreId(mediaStoreId: Long): UploadRecord?

    @Query("SELECT mediaStoreId FROM upload_records WHERE status = 'COMPLETED'")
    suspend fun getCompletedMediaStoreIds(): List<Long>

    @Query("SELECT * FROM upload_records WHERE status IN ('PENDING', 'UPLOADING') ORDER BY id ASC")
    suspend fun getPendingAndUploading(): List<UploadRecord>

    @Query("SELECT * FROM upload_records WHERE status = 'COMPLETED' ORDER BY uploadedAt DESC")
    fun getAllCompleted(): Flow<List<UploadRecord>>

    @Query("SELECT * FROM upload_records WHERE status = 'COMPLETED' ORDER BY uploadedAt DESC LIMIT :limit")
    fun getRecentCompleted(limit: Int): Flow<List<UploadRecord>>

    @Query("SELECT COUNT(*) FROM upload_records WHERE status = 'COMPLETED'")
    fun getCompletedCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM upload_records WHERE status = 'COMPLETED'")
    fun getTotalTransferredBytes(): Flow<Long>

    @Query("SELECT COALESCE(MAX(uploadedAt), 0) FROM upload_records WHERE status = 'COMPLETED'")
    fun getLastSyncTimestamp(): Flow<Long>

    @Query("DELETE FROM upload_records")
    suspend fun clearAll()

    @Query("DELETE FROM upload_records WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    @Query("UPDATE upload_records SET status = 'PENDING' WHERE status = 'UPLOADING'")
    suspend fun resetUploadingToPending()
}
