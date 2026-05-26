package com.appharbor.pherry.di

import android.content.Context
import androidx.room.Room
import com.appharbor.pherry.data.db.PherryDatabase
import com.appharbor.pherry.data.db.UploadRecordDao
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
    fun provideDatabase(@ApplicationContext context: Context): PherryDatabase {
        return Room.databaseBuilder(
            context,
            PherryDatabase::class.java,
            "photosender_db"
        ).build()
    }

    @Provides
    fun provideUploadRecordDao(db: PherryDatabase): UploadRecordDao {
        return db.uploadRecordDao()
    }
}
