package com.appharbor.photosender.di

import android.content.Context
import androidx.room.Room
import com.appharbor.photosender.data.db.PhotoSenderDatabase
import com.appharbor.photosender.data.db.UploadRecordDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PhotoSenderDatabase {
        return Room.databaseBuilder(
            context,
            PhotoSenderDatabase::class.java,
            "photosender_db"
        ).build()
    }

    @Provides
    fun provideUploadRecordDao(db: PhotoSenderDatabase): UploadRecordDao {
        return db.uploadRecordDao()
    }
}
