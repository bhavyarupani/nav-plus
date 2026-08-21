package com.navplus.app.di

import com.navplus.app.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object ApiKeysModule {

    @Provides
    @Named("tomtom_api_key")
    fun provideTomTomApiKey(): String = BuildConfig.TOMTOM_API_KEY
}
