@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.northvane.weather.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import app.northvane.core.model.GeoPoint
import app.northvane.core.radar.RadarFrame
import app.northvane.core.radar.RadarImage
import app.northvane.weather.ios.toNSData
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.CValue
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGContextRef
import platform.CoreLocation.CLLocationCoordinate2D
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.Foundation.NSCache
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSString
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithURL
import platform.MapKit.MKCoordinateRegionMakeWithDistance
import platform.MapKit.MKMapPointForCoordinate
import platform.MapKit.MKMapRect
import platform.MapKit.MKMapRectMake
import platform.MapKit.MKMapRectWorld
import platform.MapKit.MKMapSizeWorld
import platform.MapKit.MKMapView
import platform.MapKit.MKMapViewDelegateProtocol
import platform.MapKit.MKOverlayLevelAboveLabels
import platform.MapKit.MKOverlayLevelAboveRoads
import platform.MapKit.MKOverlayProtocol
import platform.MapKit.MKOverlayRenderer
import platform.MapKit.MKPointAnnotation
import platform.MapKit.MKPolygon
import platform.MapKit.MKPolygonRenderer
import platform.MapKit.addOverlay
import platform.MapKit.insertOverlay
import platform.MapKit.removeOverlay
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsPopContext
import platform.UIKit.UIGraphicsPushContext
import platform.UIKit.UIImage
import platform.UIKit.UIUserInterfaceStyle
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.round

private const val RADAR_ALPHA = 0.75
private const val SATELLITE_ALPHA = 0.85
private const val WORLD = 2 * PI * 6378137.0

/** EPSG:3857 bbox of an XYZ tile, as WMS-style radar URLs want it. */
private fun tileBbox(x: Int, y: Int, z: Int): String {
    val size = WORLD / (1 shl z)
    val minX = -WORLD / 2 + x * size
    val maxY = WORLD / 2 - y * size
    return "$minX,${maxY - size},${minX + size},$maxY"
}

private fun tileUrl(template: String, x: Int, y: Int, z: Int): String =
    template.replace("{bbox-epsg-3857}", tileBbox(x, y, z))
        .replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

/** What a raster layer shows: map tiles from a URL template, or one picture drawn on the phone. */
private sealed interface Raster {
    class Tiles(val template: String) : Raster
    class Picture(val image: RadarImage) : Raster
}

private class RasterOverlay(val raster: Raster) : NSObject(), MKOverlayProtocol {
    /** For a picture: its own area, so MapKit only asks for what it covers. */
    private val bounds: CValue<MKMapRect> = when (raster) {
        is Raster.Tiles -> MKMapRectWorld.readValue()
        is Raster.Picture -> {
            val nw = MKMapPointForCoordinate(CLLocationCoordinate2DMake(raster.image.north, raster.image.west))
            val se = MKMapPointForCoordinate(CLLocationCoordinate2DMake(raster.image.south, raster.image.east))
            val (x0, y0) = nw.useContents { x to y }
            val (x1, y1) = se.useContents { x to y }
            MKMapRectMake(x0, y0, x1 - x0, y1 - y0)
        }
    }

    override fun boundingMapRect(): CValue<MKMapRect> = bounds
    override fun coordinate(): CValue<CLLocationCoordinate2D> = CLLocationCoordinate2DMake(0.0, 0.0)
}

/** Downloaded tiles, shared by every layer; NSCache is thread-safe and gives memory back under pressure. */
private val tileCache = NSCache().apply { countLimit = 600u }
private val loadingLock = SynchronizedObject()
private val loading = mutableSetOf<String>()

/** Tiles that failed for a passing reason (offline, timeout, server error), with when: tried again after a while. */
private val failedAt = mutableMapOf<String, Double>()
private const val RETRY_SECONDS = 30.0

/**
 * Draws a [RasterOverlay]: tiles fetched on demand (MapKit calls this on
 * background threads), or the forecast picture stretched over its area.
 */
private class RasterRenderer(private val layer: RasterOverlay) : MKOverlayRenderer(overlay = layer) {
    private val picture: UIImage? = (layer.raster as? Raster.Picture)?.image?.let { pictureImage(it) }

    private fun zoomFor(scale: Double): Int =
        round(log2(scale * MKMapSizeWorld.readValue().useContents { width } / 256.0)).toInt().coerceIn(0, 10)

    private inline fun forTiles(mapRect: CValue<MKMapRect>, z: Int, block: (x: Int, y: Int, rect: CValue<MKMapRect>) -> Unit) {
        val n = 1 shl z
        val tile = MKMapSizeWorld.readValue().useContents { width } / n
        mapRect.useContents {
            val x0 = floor(origin.x / tile).toInt().coerceIn(0, n - 1)
            val x1 = floor((origin.x + size.width - 1) / tile).toInt().coerceIn(0, n - 1)
            val y0 = floor(origin.y / tile).toInt().coerceIn(0, n - 1)
            val y1 = floor((origin.y + size.height - 1) / tile).toInt().coerceIn(0, n - 1)
            for (x in x0..x1) for (y in y0..y1) block(x, y, MKMapRectMake(x * tile, y * tile, tile, tile))
        }
    }

    override fun canDrawMapRect(mapRect: CValue<MKMapRect>, zoomScale: Double): Boolean {
        val tiles = layer.raster as? Raster.Tiles ?: return true
        val z = zoomFor(zoomScale)
        var ready = true
        forTiles(mapRect, z) { x, y, rect ->
            val url = tileUrl(tiles.template, x, y, z)
            if (tileCache.objectForKey(url) == null) {
                val failed = synchronized(loadingLock) { failedAt[url] }
                // Failed a moment ago: draw what there is rather than ask again and again.
                if (failed == null || NSDate().timeIntervalSince1970 - failed > RETRY_SECONDS) {
                    ready = false
                    load(url, rect, zoomScale)
                }
            }
        }
        return ready
    }

    private fun load(url: String, rect: CValue<MKMapRect>, zoomScale: Double) {
        val first = synchronized(loadingLock) { loading.add(url) }
        if (!first) return
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            synchronized(loadingLock) { loading.remove(url); failedAt[url] = Double.MAX_VALUE }
            return
        }
        NSURLSession.sharedSession.dataTaskWithURL(nsUrl) { data, response, _ ->
            val code = (response as? NSHTTPURLResponse)?.statusCode ?: 0L
            val image = if (code in 200L..299L) data?.let { UIImage(data = it) } else null
            // A tile that doesn't exist (404, 204, or not an image) is remembered as empty, so it isn't
            // fetched again and again; a passing failure (offline, timeout, 5xx) is tried again later.
            val settled = image != null || code == 404L || code == 204L || code in 200L..299L
            synchronized(loadingLock) {
                loading.remove(url)
                if (settled) failedAt.remove(url) else failedAt[url] = NSDate().timeIntervalSince1970
            }
            if (settled) {
                tileCache.setObject(image ?: UIImage(), forKey = url)
                dispatch_async(dispatch_get_main_queue()) { setNeedsDisplayInMapRect(rect, zoomScale) }
            }
        }.resume()
    }

    override fun drawMapRect(mapRect: CValue<MKMapRect>, zoomScale: Double, inContext: CGContextRef?) {
        val ctx = inContext ?: return
        UIGraphicsPushContext(ctx)
        when (val raster = layer.raster) {
            is Raster.Picture -> picture?.drawInRect(rectForMapRect(layer.boundingMapRect()))
            is Raster.Tiles -> forTiles(mapRect, zoomFor(zoomScale)) { x, y, rect ->
                val image = tileCache.objectForKey(tileUrl(raster.template, x, y, zoomFor(zoomScale))) as? UIImage
                image?.takeIf { it.CGImage != null }?.drawInRect(rectForMapRect(rect))
            }
        }
        UIGraphicsPopContext()
    }

    private companion object {
        fun pictureImage(image: RadarImage): UIImage? {
            val argb = image.argb()
            val bytes = ByteArray(argb.size * 4)
            for (k in argb.indices) {
                val c = argb[k]
                bytes[k * 4] = (c and 0xFF).toByte()
                bytes[k * 4 + 1] = ((c shr 8) and 0xFF).toByte()
                bytes[k * 4 + 2] = ((c shr 16) and 0xFF).toByte()
                bytes[k * 4 + 3] = ((c ushr 24) and 0xFF).toByte()
            }
            val png = Image.makeRaster(ImageInfo.makeN32(image.width, image.height, ColorAlphaType.UNPREMUL), bytes, image.width * 4)
                .encodeToData(EncodedImageFormat.PNG)?.bytes ?: return null
            return UIImage(data = png.toNSData())
        }
    }
}

private fun colorOf(hex: String, alpha: Double): UIColor {
    val v = hex.removePrefix("#").toLongOrNull(16) ?: 0x888888
    return UIColor(red = ((v shr 16) and 0xFF) / 255.0, green = ((v shr 8) and 0xFF) / 255.0, blue = (v and 0xFF) / 255.0, alpha = alpha)
}

/** Keeps the map's overlays in step with the composable's arguments. */
private class RadarMapController : NSObject(), MKMapViewDelegateProtocol {
    val map = MKMapView().apply {
        rotateEnabled = false
        pitchEnabled = false
        showsCompass = false
    }
    private var frameOverlays: List<RasterOverlay> = emptyList()
    private var satellite: RasterOverlay? = null
    private var polygons: List<Pair<MKPolygon, String>> = emptyList()
    private var pin: MKPointAnnotation? = null
    private var index = 0
    private val renderers = mutableMapOf<MKOverlayProtocol, MKOverlayRenderer>()

    init {
        map.delegate = this
    }

    override fun mapView(mapView: MKMapView, rendererForOverlay: MKOverlayProtocol): MKOverlayRenderer {
        val r: MKOverlayRenderer = when (rendererForOverlay) {
            is RasterOverlay -> RasterRenderer(rendererForOverlay).apply {
                alpha = when {
                    rendererForOverlay === satellite -> SATELLITE_ALPHA
                    frameOverlays.indexOf(rendererForOverlay) == index -> RADAR_ALPHA
                    else -> 0.0
                }
            }
            is MKPolygon -> {
                val hex = polygons.firstOrNull { it.first === rendererForOverlay }?.second ?: "#888888"
                MKPolygonRenderer(polygon = rendererForOverlay).apply {
                    fillColor = colorOf(hex, 0.12)
                    strokeColor = colorOf(hex, 1.0)
                    lineWidth = 2.5
                }
            }
            else -> MKOverlayRenderer(overlay = rendererForOverlay)
        }
        renderers[rendererForOverlay] = r
        return r
    }

    fun center(p: GeoPoint) {
        map.setRegion(MKCoordinateRegionMakeWithDistance(CLLocationCoordinate2DMake(p.latitude, p.longitude), 400_000.0, 400_000.0), animated = false)
    }

    fun setFrames(overlays: List<RasterOverlay>) {
        frameOverlays.forEach { map.removeOverlay(it); renderers.remove(it) }
        frameOverlays = overlays
        overlays.forEach { map.addOverlay(it, level = MKOverlayLevelAboveRoads) }
    }

    fun show(i: Int) {
        index = i
        frameOverlays.forEachIndexed { k, o -> renderers[o]?.alpha = if (k == i) RADAR_ALPHA else 0.0 }
    }

    fun setSatellite(url: String?) {
        satellite?.let { map.removeOverlay(it); renderers.remove(it) }
        satellite = url?.let { RasterOverlay(Raster.Tiles(it)) }
        // Under the radar: inserted at the bottom of the road level.
        satellite?.let { map.insertOverlay(it, atIndex = 0u, level = MKOverlayLevelAboveRoads) }
    }

    fun setAreas(areas: List<Pair<List<List<GeoPoint>>, String>>) {
        polygons.forEach { map.removeOverlay(it.first); renderers.remove(it.first) }
        polygons = areas.flatMap { (rings, hex) ->
            rings.filter { it.size >= 3 }.map { ring ->
                memScoped {
                    val coords = allocArray<CLLocationCoordinate2D>(ring.size)
                    ring.forEachIndexed { k, p ->
                        coords[k].latitude = p.latitude
                        coords[k].longitude = p.longitude
                    }
                    MKPolygon.polygonWithCoordinates(coords, ring.size.toULong())
                } to hex
            }
        }
        polygons.forEach { map.addOverlay(it.first, level = MKOverlayLevelAboveLabels) }
    }

    fun setPin(p: GeoPoint?) {
        pin?.let { map.removeAnnotation(it) }
        pin = p?.let { point ->
            MKPointAnnotation().apply { setCoordinate(CLLocationCoordinate2DMake(point.latitude, point.longitude)) }
        }
        pin?.let { map.addAnnotation(it) }
    }
}

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
    val controller = remember { RadarMapController() }
    LaunchedEffect(center) { controller.center(center) }
    LaunchedEffect(dark) {
        controller.map.overrideUserInterfaceStyle = if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
    }
    LaunchedEffect(frames) {
        // Each frame is its own layer; only the current one is visible, so stepping through is instant.
        val overlays = frames.map { f -> RasterOverlay(f.image?.let { Raster.Picture(it) } ?: Raster.Tiles(f.tileUrl)) }
        controller.setFrames(overlays)
        controller.show(index)
    }
    LaunchedEffect(index) { controller.show(index) }
    LaunchedEffect(satelliteTiles) { controller.setSatellite(satelliteTiles) }
    LaunchedEffect(alertAreas) { controller.setAreas(alertAreas) }
    LaunchedEffect(pin) { controller.setPin(pin) }
    DisposableEffect(Unit) { onDispose { controller.map.delegate = null } }
    UIKitView(factory = { controller.map }, modifier = modifier)
}
