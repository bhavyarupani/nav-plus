package com.roadpulse.auto.engine

import com.roadpulse.auto.traffic.RoadCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Covers [RegionInstallStore]'s atomic-install guarantee and region lookups, using its
 * file-based test constructor (no `Context`/`AssetManager` needed for anything except
 * [RegionInstallStore.seedBundledRegionsIfNeeded], which is a no-op without one). */
class RegionInstallStoreTest {
    private fun store(): Pair<RegionInstallStore, File> {
        val root = Files.createTempDirectory("region-install-store-test").toFile()
        return RegionInstallStore(root) to root
    }

    private fun writeManifest(
        directory: File,
        id: String,
        bounds: RegionBounds = RegionBounds(south = 53.0, west = 8.0, north = 54.0, east = 9.0),
    ) {
        directory.mkdirs()
        File(directory, "manifest.json").writeText(
            """
            {
              "id": "$id",
              "displayName": "$id",
              "boundsSouth": ${bounds.south},
              "boundsWest": ${bounds.west},
              "boundsNorth": ${bounds.north},
              "boundsEast": ${bounds.east},
              "installedSizeBytes": 1234
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `installFromStaging moves a fully staged region into installed`() {
        val (store, _) = store()
        val staging = store.stagingDirFor("de-hb")
        writeManifest(staging, "de-hb")

        val installed = store.installFromStaging("de-hb", staging)

        assertEquals("de-hb", installed.id)
        assertTrue(store.isInstalled("de-hb"))
        assertFalse(staging.exists())
    }

    @Test
    fun `installFromStaging without a manifest never touches installed state`() {
        val (store, _) = store()
        val staging = store.stagingDirFor("de-hb")
        staging.mkdirs()
        File(staging, "tiles.mbtiles").writeText("not actually the real data, just a marker")

        assertThrows(IllegalStateException::class.java) {
            store.installFromStaging("de-hb", staging)
        }
        assertFalse(store.isInstalled("de-hb"))
    }

    @Test
    fun `installFromStaging replaces a previous install of the same id atomically`() {
        val (store, _) = store()
        val firstStaging = store.stagingDirFor("de-hb")
        writeManifest(firstStaging, "de-hb")
        store.installFromStaging("de-hb", firstStaging)
        val firstInstalledSize = store.region("de-hb")?.installedSizeBytes

        val secondStaging = store.stagingDirFor("de-hb")
        writeManifest(secondStaging, "de-hb")
        File(secondStaging, "extra-file").writeText("distinguishes this install from the first")
        val reinstalled = store.installFromStaging("de-hb", secondStaging)

        assertEquals(firstInstalledSize, reinstalled.installedSizeBytes)
        assertTrue(File(reinstalled.directory, "extra-file").isFile)
    }

    @Test
    fun `regionContaining finds the installed region whose bounds contain the coordinate`() {
        val (store, _) = store()
        writeManifest(
            store.stagingDirFor("de-hb").also { it.mkdirs() },
            "de-hb",
            RegionBounds(south = 53.01, west = 8.48, north = 53.61, east = 8.99),
        )
        store.installFromStaging("de-hb", store.stagingDirFor("de-hb"))

        val insideBremen = store.regionContaining(RoadCoordinate(53.08, 8.80))
        val outsideBremen = store.regionContaining(RoadCoordinate(48.77, 9.17)) // Stuttgart area

        assertEquals("de-hb", insideBremen?.id)
        assertNull(outsideBremen)
    }

    @Test
    fun `deleteRegion removes an installed region`() {
        val (store, _) = store()
        writeManifest(store.stagingDirFor("de-hb"), "de-hb")
        store.installFromStaging("de-hb", store.stagingDirFor("de-hb"))
        assertTrue(store.isInstalled("de-hb"))

        store.deleteRegion("de-hb")

        assertFalse(store.isInstalled("de-hb"))
        assertNull(store.region("de-hb"))
    }

    @Test
    fun `sweepOrphans removes only entries older than the orphan age`() {
        val (store, root) = store()
        val downloadsDir = File(root, "downloads").apply { mkdirs() }
        val staleDownload = File(downloadsDir, "de-by.rpregion.part").apply { writeText("partial") }
        val freshDownload = File(downloadsDir, "de-hb.rpregion.part").apply { writeText("partial") }
        val oldTimestamp = System.currentTimeMillis() - (60 * 60_000L)

        staleDownload.setLastModified(oldTimestamp)

        store.sweepOrphans()

        assertFalse(staleDownload.exists())
        assertTrue(freshDownload.exists())
    }

    @Test
    fun `installedRegions is empty when nothing has been installed`() {
        val (store, _) = store()
        assertTrue(store.installedRegions().isEmpty())
    }
}
