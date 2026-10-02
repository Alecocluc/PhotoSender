package com.appharbor.pherry.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
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
    val md5Hash: String = "",
    val status: UploadStatus = UploadStatus.PENDING,
    val progress: Int = 0,
    val uploadedAt: Long = 0,
    val serverIp: String = "",
    @ColumnInfo(defaultValue = "''") val receiverId: String = "",
    @ColumnInfo(defaultValue = "''") val libraryId: String = "",
    @ColumnInfo(defaultValue = "''") val sourceVersion: String = "",
    @ColumnInfo(defaultValue = "NULL") val dedupKey: String? = null,
    @ColumnInfo(defaultValue = "''") val jobId: String = "",
    @ColumnInfo(defaultValue = "''") val uploadId: String = "",
    @ColumnInfo(defaultValue = "'md5'") val hashAlgorithm: String = "md5",
    @ColumnInfo(defaultValue = "0") val acknowledgedBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val sentBytes: Long = 0,
    @ColumnInfo(defaultValue = "''") val receiptId: String = "",
    @ColumnInfo(defaultValue = "''") val error: String = "",
    @ColumnInfo(defaultValue = "0") val skipped: Boolean = false,
    @ColumnInfo(defaultValue = "0") val historyHidden: Boolean = false,
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
