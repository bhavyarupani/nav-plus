package com.roadpulse.auto.engine

import android.content.Context
import android.util.Log
import com.roadpulse.auto.traffic.RoadCoordinate
import org.json.JSONObject
import java.io.File

/** An installed region's on-disk files - the shape every downloaded `.rpregion` archive and the
 * bundled Bremen region both extract into, so callers never need to know which of the two it was. */
data class InstalledRegion(
    val id: String,
    val displayName: String,
    val bounds: RegionBounds,
    val directory: File,
    val installedSizeBytes: Long,
) {
    val tilesFile: File get() = File(directory, "tiles.mbtiles")
    val graphHopperDir: File get() = File(directory, "graphhopper")
    val searchDbFile: File get() = File(directory, "search.db")
}

/**
 * Owns every installed region's on-disk state under `filesDir/regions/`, and seeds the bundled
 * Bremen region from APK assets the first time the app runs. This is the single source of truth
 * [LocalMbtilesServer]/[GraphHopperRoutingEngine]/[OfflineSearchEngine] read from - none of them
 * touch `context.assets` directly anymore, since a region's files are real filesystem paths here
 * whether they were seeded from the APK or downloaded and extracted.
 *
 * Install is atomic: a region only ever appears under `regions/installed/<id>/` once every file
 * has been extracted and verified in `regions/staging/<id>/` first, then moved into place with a
 * single directory rename ([File.renameTo], atomic on the same filesystem) - so a process death
 * mid-install can never leave a half-written region visible to [installedRegions].
 */
class RegionInstallStore private constructor(
    private val appContext: Context?,
    private val regionsRoot: File,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        File(context.applicationContext.filesDir, "regions"),
    )

    /** For tests - no real `Context`, so [seedBundledRegionsIfNeeded] is a no-op (there are no
     * bundled assets to seed from without one). */
    internal constructor(regionsRoot: File) : this(null, regionsRoot)

    private val installedRoot = File(regionsRoot, "installed").apply { mkdirs() }
    val downloadsDir: File = File(regionsRoot, "downloads").apply { mkdirs() }
    val stagingDir: File = File(regionsRoot, "staging").apply { mkdirs() }

    fun installedRegions(): List<InstalledRegion> =
        installedRoot
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { dir -> runCatching { readManifest(dir) }.getOrNull() }

    fun isInstalled(id: String): Boolean = File(File(installedRoot, id), "manifest.json").isFile

    fun region(id: String): InstalledRegion? =
        File(installedRoot, id).takeIf(File::isDirectory)?.let { runCatching { readManifest(it) }.getOrNull() }

    /** The installed region whose bounds contain [coordinate], if any - used to pick which
     * region's tiles/routing graph/search index apply to a given point. */
    fun regionContaining(coordinate: RoadCoordinate): InstalledRegion? = installedRegions().firstOrNull { it.bounds.contains(coordinate) }

    fun stagingDirFor(id: String): File = File(stagingDir, id)

    fun downloadFileFor(id: String): File = File(downloadsDir, "$id.rpregion.part")

    /** Idempotent - copies every region bundled under `assets/regions-bundled/` into
     * `regions/installed/` if not already present. Call once, from [RoadPulseApplication.onCreate]. */
    @Synchronized
    fun seedBundledRegionsIfNeeded() {
        val context = appContext ?: return
        val bundledIds = context.assets.list(BUNDLED_ASSET_DIR).orEmpty()
        for (id in bundledIds) {
            if (isInstalled(id)) continue
            runCatching { seedBundledRegion(context, id) }
                .onFailure { Log.w(TAG, "Failed to seed bundled region $id", it) }
        }
    }

    private fun seedBundledRegion(
        context: Context,
        id: String,
    ) {
        val staging = stagingDirFor(id)
        staging.deleteRecursively()
        staging.mkdirs()
        copyAssetTree(context, "$BUNDLED_ASSET_DIR/$id", staging)
        installFromStaging(id, staging)
    }

    /** Moves a fully-extracted, verified region from `staging/<id>` into `installed/<id>` with a
     * single atomic directory rename. Used by the bundled-region seed path above and by
     * [RegionDownloadManager] once a downloaded archive's extraction and checksums check out. */
    fun installFromStaging(
        id: String,
        staging: File,
    ): InstalledRegion {
        check(File(staging, "manifest.json").isFile) { "Staged region $id is missing manifest.json" }
        val destination = File(installedRoot, id)
        destination.deleteRecursively()
        check(staging.renameTo(destination)) { "Failed to install region $id (rename $staging -> $destination failed)" }
        return readManifest(destination)
    }

    fun deleteRegion(id: String) {
        File(installedRoot, id).deleteRecursively()
    }

    /** Removes abandoned partial downloads/extractions left behind by a process death mid-install -
     * anything older than [ORPHAN_AGE_MILLIS] in `downloads/`/`staging/` that never got installed. */
    fun sweepOrphans(nowMillis: Long = System.currentTimeMillis()) {
        for (dir in listOf(downloadsDir, stagingDir)) {
            dir.listFiles().orEmpty().forEach { entry ->
                if (nowMillis - entry.lastModified() > ORPHAN_AGE_MILLIS) {
                    entry.deleteRecursively()
                }
            }
        }
    }

    private fun readManifest(directory: File): InstalledRegion {
        val json = JSONObject(File(directory, "manifest.json").readText())
        return InstalledRegion(
            id = json.getString("id"),
            displayName = json.getString("displayName"),
            bounds =
                RegionBounds(
                    south = json.getDouble("boundsSouth"),
                    west = json.getDouble("boundsWest"),
                    north = json.getDouble("boundsNorth"),
                    east = json.getDouble("boundsEast"),
                ),
            directory = directory,
            installedSizeBytes = json.optLong("installedSizeBytes", 0L),
        )
    }

    private fun copyAssetTree(
        context: Context,
        assetPath: String,
        destination: File,
    ) {
        val entries = context.assets.list(assetPath)
        if (entries.isNullOrEmpty()) {
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        for (entry in entries) {
            copyAssetTree(context, "$assetPath/$entry", File(destination, entry))
        }
    }

    private companion object {
        const val TAG = "RegionInstallStore"
        const val BUNDLED_ASSET_DIR = "regions-bundled"
        const val ORPHAN_AGE_MILLIS = 30 * 60_000L
    }
}
