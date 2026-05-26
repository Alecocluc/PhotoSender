package com.appharbor.pherry.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [UploadRecord::class], version = 1, exportSchema = false)
abstract class PherryDatabase : RoomDatabase() {
    abstract fun uploadRecordDao(): UploadRecordDao
}
