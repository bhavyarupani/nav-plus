package com.roadpulse.auto.engine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** One downloadable region as published in the catalog - not yet necessarily installed. See
 * [InstalledRegion] for a region that's actually on disk. */
data class CatalogRegion(
    val id: String,
    val displayName: String,
    val continent: String,
    val country: String,
    val countryCode: String,
    val bounds: RegionBounds,
    val packageUrl: String,
    val downloadSizeBytes: Long,
    val sha256: String,
    val formatVersion: Int,
)

/**
 * Fetches and caches the list of downloadable regions from `regions.json`, hosted in the
 * `bhavyarupani/roadpulse` GitHub repo (the same place `.rpregion` packages themselves are
 * published as release assets - see `tools/region-build/upload-release.sh`). Follows this
 * project's existing repository conventions exactly (`HttpURLConnection`, atomic cache writes,
 * fallback-on-failure) - see `DwdRoadWeatherRepository`/`OpenGatsoDataUpdater` for the same shape.
 *
 * A stale catalog is harmless (worst case: a brand-new region doesn't show up for a while), so
 * this refreshes once per process lifetime rather than on any fixed interval, plus whenever the
 * caller explicitly asks (the "Refresh region list" button in Settings).
 */
class RegionCatalogRepository private constructor(
    private val appContext: Context?,
    private val cacheDirectory: File,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        File(context.applicationContext.filesDir, "regions").apply { mkdirs() },
    )

    /** For tests - no real `Context`, so no bundled-fallback asset is available either. */
    internal constructor(cacheDirectory: File) : this(null, cacheDirectory)

    fun currentCatalog(forceRefresh: Boolean = false): List<CatalogRegion> {
        val file = File(cacheDirectory, CATALOG_FILE)
        if (forceRefresh || !refreshedThisProcess.getAndSet(true)) {
            runCatching { downloadText(CATALOG_URL, MAX_CATALOG_BYTES) }
                .onSuccess { json -> saveAtomically(file, json) }
        }
        val json = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        return parseCatalog(json ?: bundledFallback())
    }

    private fun bundledFallback(): String =
        runCatching {
            appContext!!
                .assets
                .open(FALLBACK_ASSET)
                .bufferedReader()
                .use { it.readText() }
        }.getOrDefault("{\"schemaVersion\":1,\"regions\":[]}")

    internal fun parseCatalog(json: String): List<CatalogRegion> =
        runCatching {
            val root = JSONObject(json)
            val regions = root.getJSONArray("regions")
            (0 until regions.length()).mapNotNull { index ->
                runCatching { parseRegion(regions.getJSONObject(index)) }.getOrNull()
            }
        }.getOrDefault(emptyList())

    private fun parseRegion(entry: JSONObject): CatalogRegion =
        CatalogRegion(
            id = entry.getString("id"),
            displayName = entry.getString("displayName"),
            // Every region published so far is in Europe - optString covers a stale cached
            // catalog.json written before these fields existed, not a real non-European region.
            // country falls back to the countryCode a stale entry does have, rather than an
            // empty string that would render as a blank group header in Settings.
            continent = entry.optString("continent", "Europe"),
            country = entry.optString("country", entry.optString("countryCode", "Unknown")),
            countryCode = entry.optString("countryCode", ""),
            bounds =
                RegionBounds(
                    south = entry.getDouble("bboxSouth"),
                    west = entry.getDouble("bboxWest"),
                    north = entry.getDouble("bboxNorth"),
                    east = entry.getDouble("bboxEast"),
                ),
            packageUrl = entry.getString("packageUrl"),
            downloadSizeBytes = entry.optLong("downloadSizeBytes", 0L),
            sha256 = entry.getString("sha256"),
            formatVersion = entry.optInt("formatVersion", 1),
        )

    private fun downloadText(
        url: String,
        maximumBytes: Long,
    ): String {
        val connection = openConnection(url)
        return try {
            if (connection.responseCode !in 200..299) error("Region catalog returned HTTP ${connection.responseCode}")
            val declared = connection.contentLengthLong
            check(declared <= 0L || declared <= maximumBytes) { "Region catalog is unexpectedly large" }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun saveAtomically(
        file: File,
        value: String,
    ) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(value, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(value, Charsets.UTF_8)
            temporary.delete()
        }
    }

    companion object {
        private val refreshedThisProcess = AtomicBoolean(false)
        private const val CATALOG_URL = "https://raw.githubusercontent.com/bhavyarupani/roadpulse/main/regions.json"
        private const val FALLBACK_ASSET = "regions-fallback.json"
        private const val CATALOG_FILE = "catalog.json"
        private const val MAX_CATALOG_BYTES = 2L * 1024 * 1024
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val USER_AGENT = "RoadPulse/0.1 personal Android navigation prototype"
    }
}
