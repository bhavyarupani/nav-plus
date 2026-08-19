package com.roadpulse.auto

import android.app.Application
import com.roadpulse.auto.engine.RegionInstallStore

/**
 * Runs once-per-process setup that would otherwise be redundantly repeated across
 * `MainActivity`/`NavigationActivity`/`RoadPulseNavigationScreen`, each of which independently
 * constructs its own engine instances (see ZERO_COST_ARCHITECTURE.md - this independence is
 * deliberate elsewhere in the app, not something this class changes).
 *
 * Seeding the bundled Bremen region runs synchronously here rather than on a background thread:
 * it's a one-time, ~29MB local file copy (no network) that only actually does anything on the
 * very first launch ever - every launch after that is a fast no-op existence check - and doing it
 * synchronously avoids a real race where a screen queries [RegionInstallStore.installedRegions]
 * before an async seed finishes.
 */
class RoadPulseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RegionInstallStore(this).apply {
            seedBundledRegionsIfNeeded()
            sweepOrphans()
        }
    }
}
