package com.roadpulse.auto.engine

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Serves vector tiles from a locally-generated MBTiles package (Geofabrik + Planetiler, see
 * ZERO_COST_ARCHITECTURE.md) to MapLibre over a loopback-only HTTP server. This is a self-hosted
 * offline package we generated ourselves, not a live/public tile service - the server never binds
 * to a non-loopback address and never leaves the device.
 *
 * Android has no `com.sun.net.httpserver.HttpServer` (JDK-only, not part of the Android API), so
 * this is a small hand-written HTTP/1.1 GET handler rather than a new dependency.
 *
 * Takes [tilesFile] directly rather than an asset name - [RegionInstallStore] already places every
 * installed region's `tiles.mbtiles` at a real filesystem path (seeded from an APK asset for the
 * bundled region, extracted from a downloaded `.rpregion` archive otherwise), so this class no
 * longer needs to know about `context.assets` at all.
 */
class LocalMbtilesServer(
    private val tilesFile: File,
) {
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var dbPath: String? = null

    @Volatile
    var port: Int = -1
        private set

    /** Opens [tilesFile] and starts listening. Must be called off the main thread - this does a
     * database open. */
    fun start() {
        check(tilesFile.exists() && tilesFile.length() > 0L) { "Missing or empty tiles file: $tilesFile" }
        dbPath = tilesFile.path
        // Each request opens its own read-only connection rather than sharing one SQLiteDatabase
        // instance across handler threads - a single shared instance serializes concurrent reads
        // through one connection, which could delay some of the ~6 simultaneous viewport tile
        // queries enough for MapLibre's client-side cancellation to give up on them.
        SQLiteDatabase.openDatabase(tilesFile.path, null, SQLiteDatabase.OPEN_READONLY).use { probe ->
            Log.d(
                TAG,
                "Opened database, tile count check: ${probe.rawQuery("SELECT COUNT(*) FROM tiles", null).use {
                    it.moveToFirst()
                    it.getInt(0)
                }}",
            )
        }

        // InetAddress.getLoopbackAddress() can resolve to IPv6 (::1) on Android depending on the
        // device's dual-stack preference, while the style JSON and MapLibre's native HTTP client
        // both target IPv4 127.0.0.1 - binding explicitly to IPv4 avoids that mismatch (confirmed
        // via a direct nc test: the server was reachable on ::1 but refused on 127.0.0.1).
        val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        Log.d(TAG, "Listening on 127.0.0.1:$port, bound=${socket.isBound}, closed=${socket.isClosed}")
        val thread =
            Thread {
                Log.d(TAG, "Accept loop starting")
                while (!socket.isClosed) {
                    val client =
                        runCatching { socket.accept() }
                            .onFailure { Log.d(TAG, "accept() failed", it) }
                            .getOrNull() ?: break
                    Log.d(TAG, "Accepted connection from ${client.remoteSocketAddress}")
                    Thread { handleClient(client) }.start()
                }
                Log.d(TAG, "Accept loop exited")
            }
        thread.isDaemon = true
        thread.start()
        serverThread = thread
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverThread = null
        dbPath = null
    }

    /**
     * Handles every request on this connection with HTTP/1.1 keep-alive, rather than closing
     * after one response - MapLibre's native HTTP client caps concurrent connections per host,
     * and forcing a fresh TCP connection per tile (as an earlier version of this server did)
     * exhausted that cap and got the overflow tile requests silently cancelled client-side.
     */
    private fun handleClient(socket: Socket) {
        socket.use {
            runCatching {
                socket.soTimeout = KEEP_ALIVE_TIMEOUT_MILLIS
                val input = socket.getInputStream().bufferedReader()
                val output = BufferedOutputStream(socket.getOutputStream())
                while (true) {
                    val requestLine = runCatching { input.readLine() }.getOrNull() ?: return
                    // Drain remaining request headers until the blank line.
                    while (true) {
                        val headerLine = input.readLine() ?: return
                        if (headerLine.isEmpty()) break
                    }
                    val path = requestLine.split(" ").getOrNull(1) ?: return
                    val match = TILE_PATH_REGEX.matchEntire(path)
                    if (match == null) {
                        writeResponse(output, 404, "text/plain", ByteArray(0))
                        continue
                    }
                    val (zText, xText, yXyzText) = match.destructured
                    val z = zText.toInt()
                    val x = xText.toInt()
                    val yXyz = yXyzText.toInt()
                    // MBTiles stores rows in TMS order (flipped from XYZ, which MapLibre requests).
                    val yTms = (1 shl z) - 1 - yXyz
                    val tile = readTile(z, x, yTms)
                    if (tile == null) {
                        writeResponse(output, 204, "application/x-protobuf", ByteArray(0))
                    } else {
                        writeResponse(output, 200, "application/x-protobuf", tile, gzipped = true)
                    }
                }
            }.onFailure { error -> Log.d(TAG, "Connection ended: ${error.message}") }
        }
    }

    private fun readTile(
        z: Int,
        x: Int,
        y: Int,
    ): ByteArray? {
        val path = dbPath ?: return null
        val startMillis = System.currentTimeMillis()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db
                .rawQuery(
                    "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                    arrayOf(z.toString(), x.toString(), y.toString()),
                ).use { cursor ->
                    val result = if (cursor.moveToFirst()) cursor.getBlob(0) else null
                    Log.d(TAG, "readTile($z,$x,$y) took ${System.currentTimeMillis() - startMillis}ms, found=${result != null}")
                    return result
                }
        }
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        gzipped: Boolean = false,
    ) {
        val statusText =
            if (status == 200) {
                "OK"
            } else if (status == 204) {
                "No Content"
            } else {
                "Not Found"
            }
        val headers =
            buildString {
                append("HTTP/1.1 $status $statusText\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                if (gzipped) append("Content-Encoding: gzip\r\n")
                append("Connection: keep-alive\r\n\r\n")
            }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private companion object {
        const val TAG = "LocalMbtilesServer"
        const val KEEP_ALIVE_TIMEOUT_MILLIS = 15_000
        val TILE_PATH_REGEX = Regex("""^/tiles/(\d+)/(\d+)/(\d+)\.pbf$""")
    }
}
