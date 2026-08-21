package com.navplus.core.safety.di

import android.content.Context
import androidx.room.Room
import com.navplus.core.safety.SafetyDatabase
import com.navplus.core.safety.SpeedCameraDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SafetyModule {

    @Provides
    @Singleton
    fun provideSafetyDatabase(@ApplicationContext context: Context): SafetyDatabase =
        Room.databaseBuilder(context, SafetyDatabase::class.java, "safety.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideSpeedCameraDao(db: SafetyDatabase): SpeedCameraDao = db.speedCameraDao()
}
