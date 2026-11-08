package app.northvane.core.radar

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan
import app.northvane.core.util.fmt
import app.northvane.core.util.toRadians
import app.northvane.core.util.toDegrees

/** A block of Web Mercator tiles at one zoom, stitched into one image. */
data class TileArea(val zoom: Int, val x0: Int, val y0: Int, val cols: Int, val rows: Int) {
    val width: Int get() = cols * TILE
    val height: Int get() = rows * TILE
    val north: Double get() = tileLat(y0, zoom)
    val south: Double get() = tileLat(y0 + rows, zoom)
    val west: Double get() = tileLon(x0, zoom)
    val east: Double get() = tileLon(x0 + cols, zoom)

    fun tiles(): List<Triple<Int, Int, Int>> = (0 until rows).flatMap { r -> (0 until cols).map { c -> Triple(zoom, x0 + c, y0 + r) } }

    companion object {
        const val TILE = 256

        /** Tiles around a point, with the point near the middle. */
        fun around(latitude: Double, longitude: Double, zoom: Int = 7, cols: Int = 3, rows: Int = 4): TileArea {
            val n = 1 shl zoom
            val x = (longitude + 180.0) / 360.0 * n
            val latRad = toRadians(latitude.coerceIn(-85.0, 85.0))
            val y = (1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0 * n
            val x0 = floor(x - cols / 2.0).toInt().coerceIn(0, n - cols)
            val y0 = floor(y - rows / 2.0).toInt().coerceIn(0, n - rows)
            return TileArea(zoom, x0, y0, cols, rows)
        }

        fun tileLon(x: Int, zoom: Int): Double = x.toDouble() / (1 shl zoom) * 360.0 - 180.0

        fun tileLat(y: Int, zoom: Int): Double = toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl zoom)))))

        /** Fills an XYZ template, or a `{bbox-epsg-3857}` one (as MapLibre does for WMS-style services). */
        fun tileUrl(template: String, z: Int, x: Int, y: Int): String {
            if ("{bbox-epsg-3857}" in template) {
                val size = 2 * PI * 6378137.0 / (1 shl z)
                val minX = -PI * 6378137.0 + x * size
                val maxY = PI * 6378137.0 - y * size
                val bbox = "%.4f,%.4f,%.4f,%.4f".fmt(minX, maxY - size, minX + size, maxY)
                return template.replace("{bbox-epsg-3857}", bbox)
            }
            return template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
        }
    }
}
