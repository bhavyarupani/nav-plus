package com.roadpulse.auto.engine

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

sealed class RegionDownloadResult {
    data class Success(
        val region: InstalledRegion,
    ) : RegionDownloadResult()

    data class Failed(
        val reason: String,
    ) : RegionDownloadResult()
}

/**
 * Downloads, verifies, and atomically installs a [CatalogRegion]'s `.rpregion` package (a plain
 * `tar.gz`: `manifest.json` + `tiles.mbtiles` + `graphhopper/` + `search.db` - see
 * `tools/region-build/package-region.sh`). Follows this project's existing download-loop shape
 * (see `DwdRoadWeatherRepository.downloadToFile`) with two additions this use case needs that
 * none of the existing repositories do: progress reporting (a real, multi-hundred-MB download a
 * user is actively watching - see [RegionDownloadService]) and inline SHA-256 verification
 * (computed during the same read pass as the download, no second file read needed).
 */
class RegionDownloadManager(
    private val regionInstallStore: RegionInstallStore,
) {
    fun download(
        region: CatalogRegion,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): RegionDownloadResult {
        val partFile = regionInstallStore.downloadFileFor(region.id)
        return try {
            val actualHash = downloadWithProgress(region.packageUrl, partFile, onProgress)
            if (!actualHash.equals(region.sha256, ignoreCase = true)) {
                return RegionDownloadResult.Failed("Downloaded package checksum did not match - try again")
            }
            val staging = regionInstallStore.stagingDirFor(region.id)
            staging.deleteRecursively()
            staging.mkdirs()
            extractAndVerify(partFile, staging)
            val installed = regionInstallStore.installFromStaging(region.id, staging)
            RegionDownloadResult.Success(installed)
        } catch (e: Exception) {
            RegionDownloadResult.Failed(e.message ?: "Download failed")
        } finally {
            partFile.delete()
        }
    }

    private fun downloadWithProgress(
        url: String,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val connection = openConnection(url)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (connection.responseCode !in 200..299) error("Download returned HTTP ${connection.responseCode}")
            val totalBytes = connection.contentLengthLong
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    var sinceLastProgress = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        total += count
                        sinceLastProgress += count
                        if (sinceLastProgress >= PROGRESS_STEP_BYTES) {
                            onProgress(total, totalBytes)
                            sinceLastProgress = 0
                        }
                    }
                    onProgress(total, totalBytes)
                }
            }
        } finally {
            connection.disconnect()
        }
        return digestToHex(digest)
    }

    /** Extracts every entry into [destination], rejecting any entry whose name would escape it
     * (defense in depth against a malformed/malicious archive), then verifies each extracted
     * file's hash against the package's own inner `manifest.json` - a second-line check behind
     * the outer package-level hash already verified in [download], catching a corrupted archive
     * that would otherwise still happen to match its outer hash. */
    private fun extractAndVerify(
        archive: File,
        destination: File,
    ) {
        val destinationPath = destination.canonicalPath
        TarArchiveInputStream(GzipCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            var entry = tar.getNextEntry()
            while (entry != null) {
                val outFile = File(destination, entry.name)
                check(outFile.canonicalPath == destinationPath || outFile.canonicalPath.startsWith("$destinationPath${File.separator}")) {
                    "Archive entry escapes destination directory: ${entry.name}"
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output -> tar.copyTo(output) }
                }
                entry = tar.getNextEntry()
            }
        }
        val manifest = JSONObject(File(destination, "manifest.json").readText())
        val files = manifest.getJSONObject("files")
        for (name in files.keys()) {
            val expectedHash = files.getJSONObject(name).getString("sha256")
            val actualHash = sha256(File(destination, name))
            check(actualHash.equals(expectedHash, ignoreCase = true)) { "Checksum mismatch for $name in downloaded package" }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digestToHex(digest)
    }

    private fun digestToHex(digest: MessageDigest): String =
        digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    private fun openConnection(url: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val USER_AGENT = "RoadPulse/0.1 personal Android navigation prototype"
        const val PROGRESS_STEP_BYTES = 256 * 1024L
    }
}
