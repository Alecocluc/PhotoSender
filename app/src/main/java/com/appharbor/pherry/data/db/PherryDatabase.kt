package com.appharbor.pherry.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [UploadRecord::class, UploadJob::class], version = 2, exportSchema = true)
abstract class PherryDatabase : RoomDatabase() {
    abstract fun uploadRecordDao(): UploadRecordDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "receiverId TEXT NOT NULL DEFAULT ''", "libraryId TEXT NOT NULL DEFAULT ''",
                    "sourceVersion TEXT NOT NULL DEFAULT ''", "dedupKey TEXT DEFAULT NULL",
                    "jobId TEXT NOT NULL DEFAULT ''", "uploadId TEXT NOT NULL DEFAULT ''",
                    "hashAlgorithm TEXT NOT NULL DEFAULT 'md5'", "acknowledgedBytes INTEGER NOT NULL DEFAULT 0",
                    "sentBytes INTEGER NOT NULL DEFAULT 0", "receiptId TEXT NOT NULL DEFAULT ''",
                    "error TEXT NOT NULL DEFAULT ''", "skipped INTEGER NOT NULL DEFAULT 0", "historyHidden INTEGER NOT NULL DEFAULT 0",
                ).forEach { db.execSQL("ALTER TABLE upload_records ADD COLUMN $it") }
                db.execSQL("CREATE UNIQUE INDEX index_upload_records_dedupKey ON upload_records(dedupKey)")
                db.execSQL("CREATE INDEX index_upload_records_receiverId_libraryId_status ON upload_records(receiverId, libraryId, status)")
                db.execSQL("CREATE INDEX index_upload_records_jobId ON upload_records(jobId)")
                db.execSQL("CREATE TABLE upload_jobs (id TEXT NOT NULL PRIMARY KEY, receiverId TEXT NOT NULL, libraryId TEXT NOT NULL, createdAt INTEGER NOT NULL, state TEXT NOT NULL, userPaused INTEGER NOT NULL, error TEXT NOT NULL)")
                // Existing rows remain intact. Legacy successes are history, not evidence for a new destination.
            }
        }
    }
}
