package com.navplus.core.search.di

import com.navplus.core.search.OfflineSearchProvider
import com.navplus.core.search.SearchProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {

    @Provides
    @Singleton
    @ElementsIntoSet
    fun provideOnlineSearchProviders(): Set<SearchProvider> = emptySet()
}
