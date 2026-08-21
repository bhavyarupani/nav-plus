package com.navplus.core.regions.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.navplus.core.regions.db.RegionDao
import com.navplus.core.regions.db.RegionDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RegionsModule {

    @Provides
    @Singleton
    fun provideRegionDatabase(@ApplicationContext context: Context): RegionDatabase =
        Room.databaseBuilder(context, RegionDatabase::class.java, "regions.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideRegionDao(db: RegionDatabase): RegionDao = db.regionDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()

    @Provides
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
