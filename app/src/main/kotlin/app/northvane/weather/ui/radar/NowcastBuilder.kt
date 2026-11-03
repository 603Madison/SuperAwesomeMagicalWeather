package app.northvane.weather.ui.radar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import app.northvane.core.model.GeoPoint
import app.northvane.core.radar.Nowcast
import app.northvane.core.radar.RadarFrame
import app.northvane.core.radar.TileArea
import app.northvane.core.repo.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration

/**
 * Builds the radar nowcast: downloads the same radar the map shows for an
 * area around [GeoPoint] at three recent times, measures how the echoes moved
 * and extrapolates the latest frame. About 36 small tiles; the math runs on a
 * background thread.
 */
class NowcastBuilder(private val http: OkHttpClient) {

    suspend fun build(past: List<RadarFrame>, center: GeoPoint, horizonMinutes: Int): List<RadarFrame> {
        val latest = past.lastOrNull() ?: return emptyList()
        val (first, middle) = Nowcast.pickEarlier(past) ?: return emptyList()
        val area = TileArea.around(center.latitude, center.longitude)
        val (firstPixels, middlePixels, latestPixels) = coroutineScope {
            val a = first?.let { async { mosaic(it.tileUrl, area) } }
            val b = async { mosaic(middle.tileUrl, area) }
            val c = async { mosaic(latest.tileUrl, area) }
            Triple(a?.await(), b.await(), c.await())
        }
        if (middlePixels == null || latestPixels == null) return emptyList()
        return withContext(Dispatchers.Default) {
            fun minutes(a: RadarFrame, b: RadarFrame) = Duration.between(a.time, b.time).toMinutes().toDouble()
            val recent = Nowcast.motion(middlePixels, latestPixels, area.width, area.height, minutes(middle, latest))
            val motion = if (first != null && firstPixels != null) {
                Nowcast.combine(Nowcast.motion(firstPixels, middlePixels, area.width, area.height, minutes(first, middle)), recent)
            } else {
                recent.scaled(0.5) // one interval can't tell drift from rain filling in
            }
            Nowcast.frames(area, latestPixels, latest.time, motion, horizonMinutes)
        }
    }

    /** The tiles for one frame stitched into ARGB pixels, or null if too many failed to load. */
    private suspend fun mosaic(template: String, area: TileArea): IntArray? = coroutineScope {
        val limit = Semaphore(4)
        val tiles = area.tiles().map { (z, x, y) ->
            async {
                limit.withPermit { Triple(x, y, downloadBitmap(TileArea.tileUrl(template, z, x, y))) }
            }
        }.awaitAll()
        if (tiles.count { it.third == null } > tiles.size / 3) return@coroutineScope null
        withContext(Dispatchers.Default) {
            val image = createBitmap(area.width, area.height)
            val canvas = Canvas(image)
            tiles.forEach { (x, y, tile) ->
                tile ?: return@forEach
                canvas.drawBitmap(tile, ((x - area.x0) * TileArea.TILE).toFloat(), ((y - area.y0) * TileArea.TILE).toFloat(), null)
                tile.recycle()
            }
            IntArray(area.width * area.height).also { image.getPixels(it, 0, area.width, 0, 0, area.width, area.height); image.recycle() }
        }
    }

    private suspend fun downloadBitmap(url: String): Bitmap? = attempt {
        runInterruptible(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val source = response.body.source()
                if (source.request(MAX_TILE_BYTES + 1)) return@use null // not a radar tile
                val bytes = source.buffer.readByteArray()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    }.getOrNull()

    private companion object {
        const val MAX_TILE_BYTES = 512L * 1024
    }
}
