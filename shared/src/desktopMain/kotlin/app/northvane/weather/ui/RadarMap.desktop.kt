package app.northvane.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.northvane.core.model.GeoPoint
import app.northvane.core.net.Request
import app.northvane.core.net.fetchBytes
import app.northvane.core.radar.RadarFrame
import app.northvane.core.radar.RadarImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

private const val RADAR_ALPHA = 0.75f
private const val SATELLITE_ALPHA = 0.85f
private const val TILE_DP = 256f
private const val MIN_ZOOM = 3f
private const val MAX_ZOOM = 12f
/** Radar and satellite tiles get no sharper past this; they're stretched instead. */
private const val MAX_OVERLAY_Z = 10
private const val WORLD = 2 * PI * 6378137.0

/**
 * Base map: the U.S. Geological Survey's National Map topographic tiles
 * (public domain, no key). In dark mode they're drawn inverted and dimmed.
 */
private const val BASE_TEMPLATE = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}"
private const val BASE_MAX_Z = 16

/** Turns the light topo map dark: inverted, desaturated and dimmed. */
private val darkFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
    androidx.compose.ui.graphics.ColorMatrix(
        floatArrayOf(
            -0.55f, -0.15f, -0.15f, 0f, 235f,
            -0.15f, -0.55f, -0.15f, 0f, 235f,
            -0.15f, -0.15f, -0.50f, 0f, 240f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** Web Mercator position of [p], 0..1 across the world from the top left. */
private fun project(p: GeoPoint): Offset {
    val lat = p.latitude.coerceIn(-85.0, 85.0) * PI / 180
    val x = (p.longitude + 180) / 360
    val y = (1 - ln(tan(lat) + 1 / kotlin.math.cos(lat)) / PI) / 2
    return Offset(x.toFloat(), y.toFloat())
}

private fun tileBbox(x: Int, y: Int, z: Int): String {
    val size = WORLD / (1 shl z)
    val minX = -WORLD / 2 + x * size
    val maxY = WORLD / 2 - y * size
    return "$minX,${maxY - size},${minX + size},$maxY"
}

private fun tileUrl(template: String, x: Int, y: Int, z: Int): String =
    template.replace("{bbox-epsg-3857}", tileBbox(x, y, z))
        .replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

/** Downloaded tiles (null: settled, nothing to draw), shared by every map on screen. */
private object Tiles {
    val images = mutableStateMapOf<String, ImageBitmap?>()
    private val order = ArrayDeque<String>()
    private val loading = mutableSetOf<String>()
    private val failedAt = mutableMapOf<String, Long>()
    val limit = Semaphore(6)
    private const val MAX = 800
    private const val RETRY_MS = 30_000L

    fun wanted(url: String): Boolean = synchronized(this) {
        if (url in images || url in loading) return false
        failedAt[url]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return false }
        loading += url
        true
    }

    fun done(url: String, image: ImageBitmap?, settled: Boolean) = synchronized(this) {
        loading -= url
        if (!settled) {
            failedAt[url] = System.currentTimeMillis()
            return
        }
        failedAt -= url
        images[url] = image
        order.addLast(url)
        while (order.size > MAX) images.remove(order.removeFirst())
    }
}

private fun pictureBitmap(image: RadarImage): ImageBitmap {
    val argb = image.argb()
    val bytes = ByteArray(argb.size * 4)
    argb.forEachIndexed { i, c ->
        bytes[i * 4] = (c shr 16 and 0xFF).toByte()
        bytes[i * 4 + 1] = (c shr 8 and 0xFF).toByte()
        bytes[i * 4 + 2] = (c and 0xFF).toByte()
        bytes[i * 4 + 3] = (c ushr 24).toByte()
    }
    val info = org.jetbrains.skia.ImageInfo(image.width, image.height, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
    return org.jetbrains.skia.Image.makeRaster(info, bytes, image.width * 4).toComposeImageBitmap()
}

/**
 * The radar map on the desktop, drawn by the app itself: the USGS base map,
 * satellite and radar tiles (or the on-device forecast picture), alert areas
 * and the place's pin. Drag to pan; scroll, double-click or use +/- to zoom.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun RadarMap(
    center: GeoPoint,
    frames: List<RadarFrame>,
    index: Int,
    dark: Boolean,
    modifier: Modifier,
    pin: GeoPoint?,
    alertAreas: List<Pair<List<List<GeoPoint>>, String>>,
    satelliteTiles: String?,
) {
    val http = LocalPlatform.current.http
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    var zoom by remember { mutableStateOf(7f) }
    var middle by remember(center) { mutableStateOf(project(center)) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pictures = remember(frames) { frames.map { f -> f.image?.let { it to pictureBitmap(it) } } }

    fun worldPx() = TILE_DP * density * 2f.pow(zoom)
    fun screen(p: Offset): Offset = Offset(
        (p.x - middle.x) * worldPx() + size.width / 2f,
        (p.y - middle.y) * worldPx() + size.height / 2f,
    )
    fun zoomAt(by: Float, at: Offset = Offset(size.width / 2f, size.height / 2f)) {
        val before = Offset(middle.x + (at.x - size.width / 2f) / worldPx(), middle.y + (at.y - size.height / 2f) / worldPx())
        zoom = (zoom + by).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Keep the point under the cursor where it was.
        middle = Offset(before.x - (at.x - size.width / 2f) / worldPx(), before.y - (at.y - size.height / 2f) / worldPx())
    }

    /** The tiles a layer needs on screen at zoom [z]: (url, x, y, z). */
    fun visible(template: String, z: Int): List<Triple<String, IntOffset, Int>> {
        if (size == IntSize.Zero) return emptyList()
        val n = 1 shl z
        val tilePx = worldPx() / n
        val left = middle.x * n - size.width / 2f / tilePx
        val top = middle.y * n - size.height / 2f / tilePx
        val x0 = floor(left).toInt()
        val y0 = floor(top).toInt().coerceAtLeast(0)
        val x1 = floor(left + size.width / tilePx).toInt()
        val y1 = floor(top + size.height / tilePx).toInt().coerceAtMost(n - 1)
        return (y0..y1).flatMap { y -> (x0..x1).map { x -> Triple(tileUrl(template, ((x % n) + n) % n, y, z), IntOffset(x, y), z) } }
    }

    fun load(urls: List<String>) {
        urls.filter(Tiles::wanted).forEach { url ->
            scope.launch(Dispatchers.IO) {
                Tiles.limit.withPermit {
                    val result = runCatching { http.fetchBytes(Request.Builder().url(url).build(), maxBytes = 4L * 1024 * 1024) }.getOrNull()
                    val image = result?.takeIf { it.code in 200..299 }?.bytes
                        ?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
                    // A missing tile (404, 204, not an image) is remembered as empty; a failed request is tried again.
                    val settled = result != null && (result.code in 200..299 || result.code == 404 || result.code == 204)
                    withContext(Dispatchers.Main) { Tiles.done(url, image, settled) }
                }
            }
        }
    }

    val baseZ = floor(zoom).toInt().coerceIn(0, BASE_MAX_Z)
    val overlayZ = minOf(baseZ, MAX_OVERLAY_Z)
    val base = visible(BASE_TEMPLATE, baseZ)
    val satellite = satelliteTiles?.let { visible(it, overlayZ) }.orEmpty()
    val radar = frames.map { f -> if (f.image == null && f.tileUrl.isNotEmpty()) visible(f.tileUrl, overlayZ) else emptyList() }
    // The frame on screen first, then the rest, so the loop plays smoothly once loaded.
    LaunchedEffect(base, satellite, radar, index) {
        load(base.map { it.first })
        load(satellite.map { it.first })
        radar.getOrNull(index)?.let { load(it.map { t -> t.first }) }
        radar.forEachIndexed { i, tiles -> if (i != index) load(tiles.map { it.first }) }
    }

    val pinColor = MaterialTheme.colorScheme.primary
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size = it }
                .semantics { contentDescription = "Radar map. Drag to move, scroll to zoom." }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        middle = Offset(middle.x - drag.x / worldPx(), (middle.y - drag.y / worldPx()).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoomAt(1f, it) }) }
                .onPointerEvent(PointerEventType.Scroll) { e ->
                    val change = e.changes.first()
                    zoomAt(-change.scrollDelta.y * 0.5f, change.position)
                },
        ) {
            val world = worldPx()
            /** Draws a layer's tiles where they sit, stretched when the map is between zoom levels. */
            fun DrawScope.layer(tiles: List<Triple<String, IntOffset, Int>>, alpha: Float, filter: androidx.compose.ui.graphics.ColorFilter? = null) {
                tiles.forEach { (url, xy, z) ->
                    val image = Tiles.images[url] ?: return@forEach
                    val tilePx = world / (1 shl z)
                    val left = xy.x * tilePx - middle.x * world + size.width / 2f
                    val top = xy.y * tilePx - middle.y * world + size.height / 2f
                    drawImage(
                        image, dstOffset = IntOffset(left.toInt(), top.toInt()),
                        dstSize = IntSize(tilePx.toInt() + 1, tilePx.toInt() + 1), alpha = alpha, colorFilter = filter,
                    )
                }
            }
            drawRect(if (dark) Color(0xFF1B1F24) else Color(0xFFE8ECEF))
            layer(base, 1f, if (dark) darkFilter else null)
            layer(satellite, SATELLITE_ALPHA)
            val frame = frames.getOrNull(index)
            val picture = pictures.getOrNull(index)
            if (picture != null) {
                val (img, bitmap) = picture
                val nw = screen(project(GeoPoint(img.north, img.west)))
                val se = screen(project(GeoPoint(img.south, img.east)))
                drawImage(
                    bitmap, dstOffset = IntOffset(nw.x.toInt(), nw.y.toInt()),
                    dstSize = IntSize((se.x - nw.x).toInt().coerceAtLeast(1), (se.y - nw.y).toInt().coerceAtLeast(1)),
                    alpha = RADAR_ALPHA,
                )
            } else if (frame != null) {
                layer(radar.getOrNull(index).orEmpty(), RADAR_ALPHA)
            }
            alertAreas.forEach { (rings, hex) ->
                val color = runCatching { Color(("FF" + hex.removePrefix("#")).toLong(16)) }.getOrDefault(Color.Gray)
                rings.filter { it.size >= 3 }.forEach { ring ->
                    val path = Path().apply {
                        ring.forEachIndexed { i, p -> screen(project(p)).let { o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                        close()
                    }
                    drawPath(path, color.copy(alpha = 0.12f))
                    drawPath(path, color, style = Stroke(width = 2.5f * density))
                }
            }
            pin?.let { p ->
                val o = screen(project(p))
                drawCircle(Color.White, radius = 8f * density, center = o)
                drawCircle(pinColor, radius = 5.5f * density, center = o)
            }
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(8.dp)) {
            FilledTonalIconButton(onClick = { zoomAt(1f) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
            FilledTonalIconButton(onClick = { zoomAt(-1f) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        }
        Surface(
            shape = RoundedCornerShape(topStart = 6.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Text(
                "Map: USGS The National Map",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
