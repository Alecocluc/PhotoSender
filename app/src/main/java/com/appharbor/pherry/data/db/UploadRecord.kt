package com.appharbor.pherry.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "upload_records", indices = [
    Index(value = ["dedupKey"], unique = true),
    Index(value = ["receiverId", "libraryId", "status"]),
    Index(value = ["jobId"]),
])
data class UploadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaStoreId: Long,
    val contentUri: String,
    val fileName: String,
    val bucketName: String,
    val fileSize: Long,
    /** SHA-256 of the original, empty until it has been read. */
    val hash: String = "",
    val status: UploadStatus = UploadStatus.PENDING,
    val progress: Int = 0,
    val uploadedAt: Long = 0,
    val receiverId: String = "",
    val libraryId: String = "",
    val sourceVersion: String = "",
    val dedupKey: String? = null,
    val jobId: String = "",
    val uploadId: String = "",
    val acknowledgedBytes: Long = 0,
    val sentBytes: Long = 0,
    val receiptId: String = "",
    val error: String = "",
    val skipped: Boolean = false,
    val historyHidden: Boolean = false,
)

/** Durable user intent and identity, independent of a particular worker/process run. */
@Entity(tableName = "upload_jobs")
data class UploadJob(
    @PrimaryKey val id: String,
    val receiverId: String,
    val libraryId: String,
    val createdAt: Long,
    val state: String = "planning",
    val userPaused: Boolean = false,
    val error: String = "",
)

enum class UploadStatus {
    PENDING,
    UPLOADING,
    COMPLETED,
    FAILED,
}
