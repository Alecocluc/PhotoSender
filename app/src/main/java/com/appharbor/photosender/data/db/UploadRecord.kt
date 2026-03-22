package com.appharbor.photosender.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "upload_records")
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
)

enum class UploadStatus {
    PENDING,
    UPLOADING,
    COMPLETED,
    FAILED,
}
