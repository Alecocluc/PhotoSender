package com.appharbor.pherry.data.network

import com.appharbor.pherry.data.network.PherryHeaderInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(headerInterceptor: PherryHeaderInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            // Six high-speed uploads must leave room for presence checks and job/control calls.
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .addInterceptor(headerInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
