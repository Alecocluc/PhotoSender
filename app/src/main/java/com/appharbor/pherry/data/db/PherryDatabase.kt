package com.appharbor.pherry.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [UploadRecord::class, UploadJob::class], version = 1, exportSchema = true)
abstract class PherryDatabase : RoomDatabase() {
    abstract fun uploadRecordDao(): UploadRecordDao
}
