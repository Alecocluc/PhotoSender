package com.appharbor.photosender.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [UploadRecord::class], version = 1, exportSchema = false)
abstract class PhotoSenderDatabase : RoomDatabase() {
    abstract fun uploadRecordDao(): UploadRecordDao
}
