package com.roadpulse.auto.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.roadpulse.auto.traffic.RoadCoordinate
import java.io.File
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
 * data-generation time from the same Bremen Geofabrik extract used for map tiles and routing.
 * The index-building tool (`BuildSearchIndex.java`, not part of the app, analogous to
 * Planetiler's role for tiles) scanned 1,661,904 raw OSM elements and produced 25,930 searchable
 * places - see ZERO_COST_ARCHITECTURE.md.
 *
 * Query construction is delegated to [OfflineSearchQueryBuilder] (pure, unit-tested) so raw user
 * input can never be interpreted as FTS4 query syntax.
 */
class OfflineSearchEngine(
    private val context: Context,
    private val regionAssetPath: String = "search/search-bremen.db",
) : SearchEngine {
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var database: SQLiteDatabase? = null

    private fun ensureOpen(): SQLiteDatabase {
        database?.let { return it }
        val dbFile = File(context.filesDir, "search/${File(regionAssetPath).name}")
        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            context.assets.open(regionAssetPath).use { input ->
                dbFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val opened = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        database = opened
        return opened
    }

    override fun search(
        query: String,
        nearCoordinate: RoadCoordinate?,
    ): CompletableFuture<List<SearchResult>> =
        CompletableFuture.supplyAsync({
            val ftsQuery = OfflineSearchQueryBuilder.buildFtsQuery(query) ?: return@supplyAsync emptyList()
            val results = mutableListOf<SearchResult>()
            ensureOpen()
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
            if (nearCoordinate != null) {
                results.sortBy { haversineMeters(nearCoordinate, it.coordinate) }
            }
            results.take(RESULT_LIMIT)
        }, executor)

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
