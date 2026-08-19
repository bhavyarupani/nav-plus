package com.roadpulse.auto.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Covers [RegionCatalogRepository.parseCatalog] directly (pure, no network) - the fetch/cache/
 * fallback ladder itself needs a real network call to exercise end to end, so isn't retested here
 * beyond confirming a missing cache with no bundled asset degrades to an empty list, not a crash. */
class RegionCatalogRepositoryTest {
    private fun repository(): RegionCatalogRepository = RegionCatalogRepository(Files.createTempDirectory("region-catalog-test").toFile())

    @Test
    fun `parseCatalog reads a well-formed catalog`() {
        val json =
            """
            {
              "schemaVersion": 1,
              "regions": [
                {
                  "id": "de-hb", "displayName": "Bremen", "countryCode": "DE",
                  "bboxSouth": 53.01, "bboxWest": 8.48, "bboxNorth": 53.61, "bboxEast": 8.99,
                  "packageUrl": "https://example.invalid/de-hb.rpregion",
                  "downloadSizeBytes": 15230000, "sha256": "abc123", "formatVersion": 1
                }
              ]
            }
            """.trimIndent()

        val regions = repository().parseCatalog(json)

        assertEquals(1, regions.size)
        assertEquals("de-hb", regions[0].id)
        assertEquals("Bremen", regions[0].displayName)
        assertEquals(53.01, regions[0].bounds.south, 0.0001)
        assertEquals("abc123", regions[0].sha256)
    }

    @Test
    fun `parseCatalog skips a malformed entry rather than failing the whole catalog`() {
        val json =
            """
            {
              "schemaVersion": 1,
              "regions": [
                { "id": "de-hb", "displayName": "Bremen" },
                {
                  "id": "de-by", "displayName": "Bavaria", "countryCode": "DE",
                  "bboxSouth": 47.2, "bboxWest": 8.9, "bboxNorth": 50.6, "bboxEast": 13.9,
                  "packageUrl": "https://example.invalid/de-by.rpregion",
                  "downloadSizeBytes": 90000000, "sha256": "def456", "formatVersion": 1
                }
              ]
            }
            """.trimIndent()

        val regions = repository().parseCatalog(json)

        assertEquals(1, regions.size)
        assertEquals("de-by", regions[0].id)
    }

    @Test
    fun `parseCatalog on invalid JSON returns an empty list rather than throwing`() {
        assertTrue(repository().parseCatalog("not json at all").isEmpty())
    }

    @Test
    fun `currentCatalog with no cache and no bundled asset is empty, not a crash`() {
        // Real network access isn't exercised in unit tests - this repository has no Context, so
        // both the download and the bundled-asset fallback are unavailable, exactly like a real
        // first-run-offline device with no cached catalog yet.
        val regions = repository().currentCatalog()
        assertTrue(regions.isEmpty())
    }

    @Test
    fun `currentCatalog serves the cached copy once one exists`() {
        val cacheDir = Files.createTempDirectory("region-catalog-cache-test").toFile()
        val repository = RegionCatalogRepository(cacheDir)
        // currentCatalog() only skips its network refresh once per process (a deliberate,
        // production-correct AtomicBoolean shared across every repository instance - see its doc
        // comment) - burn that one-shot here first so the real fixture write below can't get
        // clobbered by a live fetch racing in underneath it.
        repository.currentCatalog()
        File(cacheDir, "catalog.json").writeText(
            """{"schemaVersion":1,"regions":[
                {"id":"de-hb","displayName":"Bremen","bboxSouth":53.01,"bboxWest":8.48,
                 "bboxNorth":53.61,"bboxEast":8.99,"packageUrl":"https://example.invalid/de-hb.rpregion",
                 "sha256":"abc123"}
            ]}""",
        )

        val regions = repository.currentCatalog()

        assertEquals(1, regions.size)
        assertEquals("de-hb", regions[0].id)
    }
}
