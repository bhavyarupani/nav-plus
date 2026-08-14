package com.roadpulse.auto.alerts

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class OpenGatsoUpdateResult(
    val pointCount: Int,
    val dataFile: File,
)

class OpenGatsoDataUpdater(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun currentDataFile(): File = File(File(appContext.filesDir, DATA_DIRECTORY), DATA_FILENAME)

    fun isRefreshDue(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val dataFile = currentDataFile()
        return !dataFile.isFile || nowMillis - dataFile.lastModified() >= AUTO_REFRESH_AGE_MILLIS
    }

    fun downloadLatest(): OpenGatsoUpdateResult =
        synchronized(DOWNLOAD_LOCK) {
            val directory = File(appContext.filesDir, DATA_DIRECTORY).apply { mkdirs() }
            val temporary = File.createTempFile("open-gatso-", ".csv", directory)

            try {
                downloadCsvTo(temporary)
                val count =
                    FileInputStream(temporary).reader(Charsets.UTF_8).use {
                        OpenGatsoCsvParser.countValid(it)
                    }
                require(count >= MINIMUM_EXPECTED_POINTS) { "Open-GATSO dataset is unexpectedly small" }

                val destination = currentDataFile()
                if (destination.exists()) check(destination.delete()) { "Unable to replace old Open-GATSO data" }
                check(temporary.renameTo(destination)) { "Unable to activate downloaded Open-GATSO data" }
                return OpenGatsoUpdateResult(count, destination)
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }

    private fun downloadCsvTo(destination: File) {
        val connection = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "RoadPulse/0.1")

        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Open-GATSO download failed with HTTP ${connection.responseCode}"
            }
            val contentLength = connection.contentLength.toLong()
            check(contentLength in 1..MAXIMUM_ARCHIVE_BYTES) { "Unexpected Open-GATSO archive size" }

            ZipInputStream(BufferedInputStream(connection.inputStream)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && entry.name != DATA_FILENAME) {
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                check(entry != null && !entry.isDirectory) { "$DATA_FILENAME is missing from Open-GATSO archive" }

                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= MAXIMUM_CSV_BYTES) { "Open-GATSO CSV exceeds the safety limit" }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DOWNLOAD_URL =
            "https://raw.githubusercontent.com/1e1/Open-GATSO-POI/gh-pages/RELEASES/EU-gatso-csv_files.zip"
        const val PROJECT_URL = "https://github.com/1e1/Open-GATSO-POI"
        const val ATTRIBUTION =
            "Alert data: Open-GATSO-POI; Lufop (CC BY-SA 4.0); French Ministry of the Interior (Open Licence 2.0)"

        private const val DATA_DIRECTORY = "open-gatso"
        private const val DATA_FILENAME = "GATSO_ALL.csv"
        private const val MINIMUM_EXPECTED_POINTS = 1_000
        private const val MAXIMUM_ARCHIVE_BYTES = 5_000_000L
        private const val MAXIMUM_CSV_BYTES = 10_000_000L
        private const val AUTO_REFRESH_AGE_MILLIS = 18 * 60 * 60 * 1_000L
        private val DOWNLOAD_LOCK = Any()
    }
}
