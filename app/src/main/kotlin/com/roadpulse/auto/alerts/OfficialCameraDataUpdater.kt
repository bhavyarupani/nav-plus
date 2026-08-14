package com.roadpulse.auto.alerts

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

enum class OfficialCameraFeed(
    val source: CameraDataSource,
    val downloadUrl: String,
    val fileName: String,
    val minimumExpectedPoints: Int,
    val maximumBytes: Long,
    val refreshAgeMillis: Long,
) {
    FRANCE(
        CameraDataSource.FRANCE_INTERIOR,
        "https://www.data.gouv.fr/api/1/datasets/r/17f7cfd9-a5fe-4b6a-9f5d-3625feaa396e",
        "france-fixed.csv",
        2_500,
        2_000_000,
        7 * 24 * 60 * 60 * 1_000L,
    ),
    BRUSSELS(
        CameraDataSource.BRUSSELS_MOBILITY,
        "https://data.mobility.brussels/geoserver/bm_security/wfs" +
            "?outputFormat=json&request=GetFeature&service=wfs&srsName=EPSG%3A4326" +
            "&typeName=bm_security%3Aspeedcameras&version=1.1.0",
        "brussels-speed-cameras.json",
        20,
        5_000_000,
        24 * 60 * 60 * 1_000L,
    ),
    LUXEMBOURG(
        CameraDataSource.LUXEMBOURG_PCH,
        "https://data.public.lu/fr/datasets/r/3e0d3aae-471d-4a1c-9fb4-6d269672f423",
        "luxembourg-fixed.geojson",
        10,
        2_000_000,
        7 * 24 * 60 * 60 * 1_000L,
    ),
}

data class OfficialCameraFeedStatus(
    val feed: OfficialCameraFeed,
    val file: File,
    val pointCount: Int,
)

data class OfficialCameraRefreshResult(
    val updated: List<OfficialCameraFeedStatus>,
    val current: List<OfficialCameraFeedStatus>,
    val failures: Map<OfficialCameraFeed, String>,
) {
    val totalPointCount: Int get() = current.sumOf(OfficialCameraFeedStatus::pointCount)
}

class OfficialCameraDataUpdater(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val directory =
        File(File(appContext.filesDir, DATA_DIRECTORY), OFFICIAL_DIRECTORY)
            .apply { mkdirs() }
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun fileFor(feed: OfficialCameraFeed): File = File(directory, feed.fileName)

    fun isRefreshDue(nowMillis: Long = System.currentTimeMillis()): Boolean =
        OfficialCameraFeed.entries.any { feed ->
            val file = fileFor(feed)
            !file.isFile || nowMillis - file.lastModified() >= feed.refreshAgeMillis
        }

    fun statuses(): List<OfficialCameraFeedStatus> =
        OfficialCameraFeed.entries.mapNotNull { feed ->
            val file = fileFor(feed)
            if (!file.isFile) return@mapNotNull null
            OfficialCameraFeedStatus(feed, file, preferences.getInt(countKey(feed), 0))
        }

    fun refresh(force: Boolean = false): OfficialCameraRefreshResult =
        synchronized(UPDATE_LOCK) {
            val updated = mutableListOf<OfficialCameraFeedStatus>()
            val failures = linkedMapOf<OfficialCameraFeed, String>()
            OfficialCameraFeed.entries.forEach { feed ->
                val destination = fileFor(feed)
                val due =
                    !destination.isFile ||
                        System.currentTimeMillis() - destination.lastModified() >= feed.refreshAgeMillis
                if (!force && !due) return@forEach
                runCatching { downloadAndValidate(feed, destination) }
                    .onSuccess { status ->
                        updated += status
                        preferences.edit { putInt(countKey(feed), status.pointCount) }
                    }.onFailure { error -> failures[feed] = error.message ?: error.javaClass.simpleName }
            }
            OfficialCameraRefreshResult(updated, statuses(), failures)
        }

    private fun downloadAndValidate(
        feed: OfficialCameraFeed,
        destination: File,
    ): OfficialCameraFeedStatus {
        val temporary = File.createTempFile("${feed.name.lowercase()}-", ".download", directory)
        try {
            download(feed, temporary)
            val count = parseFeed(feed, temporary).size
            require(count >= feed.minimumExpectedPoints) {
                "${feed.source.displayName} returned only $count valid points"
            }
            val backup = File(directory, "${destination.name}.previous")
            backup.delete()
            if (destination.isFile) {
                check(destination.renameTo(backup)) { "Unable to prepare ${feed.fileName} update" }
            }
            if (!temporary.renameTo(destination)) {
                if (backup.isFile) backup.renameTo(destination)
                error("Unable to activate ${feed.fileName}")
            }
            backup.delete()
            return OfficialCameraFeedStatus(feed, destination, count)
        } finally {
            temporary.delete()
        }
    }

    private fun download(
        feed: OfficialCameraFeed,
        destination: File,
    ) {
        val connection = URL(feed.downloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "RoadPulse/0.2 personal Android navigation prototype")
        try {
            check(connection.responseCode in 200..299) {
                "${feed.source.displayName} returned HTTP ${connection.responseCode}"
            }
            val announcedSize = connection.contentLengthLong
            require(announcedSize <= 0L || announcedSize <= feed.maximumBytes) {
                "${feed.source.displayName} response is unexpectedly large"
            }
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= feed.maximumBytes) {
                            "${feed.source.displayName} response exceeds safety limit"
                        }
                        output.write(buffer, 0, read)
                    }
                    require(total > 0L) { "${feed.source.displayName} returned an empty response" }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        internal fun parseFeed(
            feed: OfficialCameraFeed,
            file: File,
        ): List<SourcedCameraPoi> =
            when (feed) {
                OfficialCameraFeed.FRANCE ->
                    FileInputStream(file).use { input ->
                        OfficialCameraDataParser.parseFrance(InputStreamReader(input, Charsets.ISO_8859_1))
                    }
                OfficialCameraFeed.BRUSSELS ->
                    OfficialCameraDataParser.parseBrussels(file.readText(Charsets.UTF_8))
                OfficialCameraFeed.LUXEMBOURG ->
                    OfficialCameraDataParser.parseLuxembourg(file.readText(Charsets.UTF_8))
            }

        private fun countKey(feed: OfficialCameraFeed): String = "${feed.name.lowercase()}_count"

        const val ATTRIBUTION =
            "Official sources: French Ministry of the Interior (Open Licence 2.0); " +
                "Brussels Mobility (CC0); Luxembourg Roads Administration (CC0)"

        private const val DATA_DIRECTORY = "camera-data"
        private const val OFFICIAL_DIRECTORY = "official"
        private const val PREFERENCES_NAME = "official_camera_feed_status"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private val UPDATE_LOCK = Any()
    }
}
