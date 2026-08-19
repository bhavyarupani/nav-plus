package com.navplus.app.di

import com.navplus.app.BuildConfig
import com.navplus.core.search.PhotonOnlineSearchProvider
import com.navplus.core.search.SearchProvider
import com.navplus.core.search.TomTomSearchProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SearchBindingsModule {

    @Provides
    @Singleton
    @IntoSet
    fun provideTomTomSearch(client: OkHttpClient): SearchProvider =
        TomTomSearchProvider(client, BuildConfig.TOMTOM_API_KEY)

    @Provides
    @Singleton
    @IntoSet
    fun providePhotonOnlineSearch(client: OkHttpClient): SearchProvider =
        PhotonOnlineSearchProvider(client)
}
