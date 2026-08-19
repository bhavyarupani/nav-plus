package com.roadpulse.auto.engine

import android.database.sqlite.SQLiteDatabase
import com.roadpulse.auto.traffic.RoadCoordinate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device destination search, replacing Google Places autocomplete + `FetchPlaceRequest`.
 * Queries a SQLite FTS4 index of named OSM nodes (POIs, places, and address points - shops,
 * amenities, tourism/leisure sites, and `addr:housenumber`+`addr:street` points) built at
 * data-generation time from a Geofabrik extract of each region. The index-building tool
 * (`tools/region-build/BuildSearchIndex.java`, not part of the app, analogous to Planetiler's
 * role for tiles) produced the first such index - 25,930 places from 1,661,904 raw OSM elements
 * scanned for Bremen - see ZERO_COST_ARCHITECTURE.md.
 *
 * Region-aware: queries *every* currently-installed region's `search.db` (via
 * [RegionInstallStore]) and merges the results, rather than one hardcoded region - a search near
 * a state border should surface results from both sides. Query construction is delegated to
 * [OfflineSearchQueryBuilder] (pure, unit-tested) so raw user input can never be interpreted as
 * FTS4 query syntax.
 */
class OfflineSearchEngine(
    private val regionInstallStore: RegionInstallStore,
) : SearchEngine {
    private val executor = Executors.newSingleThreadExecutor()

    override fun search(
        query: String,
        nearCoordinate: RoadCoordinate?,
    ): CompletableFuture<List<SearchResult>> =
        CompletableFuture.supplyAsync({
            val ftsQuery = OfflineSearchQueryBuilder.buildFtsQuery(query) ?: return@supplyAsync emptyList()
            val results = regionInstallStore.installedRegions().flatMap { region -> queryRegion(region, ftsQuery) }
            val sorted = if (nearCoordinate != null) results.sortedBy { haversineMeters(nearCoordinate, it.coordinate) } else results
            sorted.take(RESULT_LIMIT)
        }, executor)

    /** Opens a fresh connection per region per call rather than caching one - search happens far
     * less often than tile requests, and this way an installed/deleted region is always reflected
     * immediately with no cache to invalidate. */
    private fun queryRegion(
        region: InstalledRegion,
        ftsQuery: String,
    ): List<SearchResult> {
        if (!region.searchDbFile.isFile) return emptyList()
        val results = mutableListOf<SearchResult>()
        SQLiteDatabase.openDatabase(region.searchDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db
                .rawQuery(
                    "SELECT name, subtitle, lat, lon FROM places WHERE places MATCH ? LIMIT $CANDIDATE_LIMIT",
                    arrayOf(ftsQuery),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        results +=
                            SearchResult(
                                title = cursor.getString(0),
                                subtitle = cursor.getString(1),
                                coordinate = RoadCoordinate(cursor.getDouble(2), cursor.getDouble(3)),
                            )
                    }
                }
        }
        return results
    }

    private fun haversineMeters(
        start: RoadCoordinate,
        end: RoadCoordinate,
    ): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(end.longitude - start.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private companion object {
        const val CANDIDATE_LIMIT = 200
        const val RESULT_LIMIT = 20
    }
}
