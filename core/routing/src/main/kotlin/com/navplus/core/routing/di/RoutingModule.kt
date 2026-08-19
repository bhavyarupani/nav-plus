package com.navplus.core.routing.di

import com.navplus.core.routing.RoutingEngine
import com.navplus.core.routing.graphhopper.GraphHopperEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RoutingModule {
    @Binds
    @Singleton
    abstract fun bindRoutingEngine(impl: GraphHopperEngine): RoutingEngine
}
