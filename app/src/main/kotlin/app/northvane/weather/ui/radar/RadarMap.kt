package app.northvane.weather.ui.radar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.northvane.core.model.GeoPoint
import app.northvane.core.radar.RadarFrame
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import android.graphics.Bitmap
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

// Free, keyless vector basemaps (OpenStreetMap data) — no Google/Mapbox key needed.
private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/positron"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val RADAR_OPACITY = 0.78f
private const val DEFAULT_ZOOM = 6.5
private const val SATELLITE_OPACITY = 0.85f
private const val SAT_SOURCE = "satellite-src"
private const val SAT_LAYER = "satellite-layer"
private const val PIN_SOURCE = "pin-src"
private const val PIN_HALO = "pin-halo"
private const val PIN_DOT = "pin-dot"
private const val PIN_COLOR = "#2563EB"

/**
 * MapLibre map with one raster layer per radar frame. All frames load up front
 * (at zero opacity) so animation is just an opacity swap, with no flicker.
 */
@Composable
fun RadarMap(
    center: GeoPoint,
    frames: List<RadarFrame>,
    index: Int,
    dark: Boolean,
    modifier: Modifier = Modifier,
    /** Marker for the selected location. */
    pin: GeoPoint? = null,
    /** GOES satellite tiles drawn beneath the radar, or null for none. */
    satelliteTiles: String? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).also { it.onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var layerIds by remember { mutableStateOf<List<String>>(emptyList()) }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val state = lifecycleOwner.lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.setRotateGesturesEnabled(false)
            m.uiSettings.setTiltGesturesEnabled(false)
            m.uiSettings.setCompassEnabled(false)
            m.uiSettings.setLogoEnabled(false)
            m.setMinZoomPreference(3.0)
            m.setMaxZoomPreference(10.0)
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(center.latitude, center.longitude))
                .zoom(DEFAULT_ZOOM)
                .build()
            map = m
        }
    }

    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromUri(if (dark) DARK_STYLE else LIGHT_STYLE)) { loaded ->
            // The basemaps' labels are tuned for an empty map; over bright radar they need more contrast.
            loaded.layers.filterIsInstance<SymbolLayer>().forEach { label ->
                label.setProperties(
                    PropertyFactory.textColor(if (dark) "#F2FFFFFF" else "#FF1F2328"),
                    PropertyFactory.textHaloColor(if (dark) "#E6000000" else "#F2FFFFFF"),
                    PropertyFactory.textHaloWidth(1.6f),
                    PropertyFactory.textHaloBlur(0.4f),
                )
            }
            style = loaded
        }
    }

    LaunchedEffect(map, center) {
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(center.latitude, center.longitude), DEFAULT_ZOOM))
    }

    // Forecast frames are drawn on the phone: about a million pixels each, so build the bitmaps off
    // the main thread, once per set of frames (a light/dark style change reuses them).
    val prepared by androidx.compose.runtime.produceState<Pair<List<RadarFrame>, List<Bitmap?>>?>(null, frames) {
        value = frames to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            frames.map { f -> f.image?.let { Bitmap.createBitmap(it.argb(), it.width, it.height, Bitmap.Config.ARGB_8888) } }
        }
    }

    LaunchedEffect(style, prepared) {
        val s = style ?: return@LaunchedEffect
        val (frames, bitmaps) = prepared ?: return@LaunchedEffect
        layerIds.forEach { id ->
            s.removeLayer(id)
            s.removeSource(id.replace("radar-layer-", "radar-src-"))
        }
        // Keep place labels readable by drawing radar beneath the first label layer.
        val labelLayer = s.layers.firstOrNull { it is SymbolLayer }?.id
        layerIds = frames.mapIndexed { i, frame ->
            val sourceId = "radar-src-$i"
            val layerId = "radar-layer-$i"
            val image = frame.image
            val bitmap = bitmaps[i]
            if (image != null && bitmap != null) {
                // Drawn on the phone from model data: one image stretched over its tile area.
                val quad = LatLngQuad(
                    LatLng(image.north, image.west), LatLng(image.north, image.east),
                    LatLng(image.south, image.east), LatLng(image.south, image.west),
                )
                s.addSource(ImageSource(sourceId, quad, bitmap))
            } else {
                val tiles = TileSet("2.2.0", frame.tileUrl)
                tiles.setMinZoom(0f)
                tiles.setMaxZoom(10f)
                s.addSource(RasterSource(sourceId, tiles, 256))
            }
            val layer = RasterLayer(layerId, sourceId).withProperties(
                PropertyFactory.rasterOpacity(0f),
                PropertyFactory.rasterFadeDuration(0f),
            )
            if (labelLayer != null) s.addLayerBelow(layer, labelLayer) else s.addLayer(layer)
            layerId
        }
    }

    // Satellite imagery: under the radar and the place labels, over the basemap.
    LaunchedEffect(style, satelliteTiles) {
        val s = style ?: return@LaunchedEffect
        s.removeLayer(SAT_LAYER)
        s.removeSource(SAT_SOURCE)
        val url = satelliteTiles ?: return@LaunchedEffect
        val tiles = TileSet("2.2.0", url)
        tiles.setMinZoom(0f)
        tiles.setMaxZoom(10f)
        s.addSource(RasterSource(SAT_SOURCE, tiles, 256))
        val layer = RasterLayer(SAT_LAYER, SAT_SOURCE).withProperties(
            PropertyFactory.rasterOpacity(SATELLITE_OPACITY),
            PropertyFactory.rasterFadeDuration(0f),
        )
        val below = s.layers.firstOrNull { it.id.startsWith("radar-layer-") }?.id ?: s.layers.firstOrNull { it is SymbolLayer }?.id
        if (below != null) s.addLayerBelow(layer, below) else s.addLayer(layer)
    }

    // Location pin, always on top.
    LaunchedEffect(style, pin) {
        val s = style ?: return@LaunchedEffect
        listOf(PIN_DOT, PIN_HALO).forEach { s.removeLayer(it) }
        s.removeSource(PIN_SOURCE)
        val p = pin ?: return@LaunchedEffect
        s.addSource(GeoJsonSource(PIN_SOURCE, Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude))))
        s.addLayer(
            CircleLayer(PIN_HALO, PIN_SOURCE).withProperties(
                PropertyFactory.circleRadius(15f),
                PropertyFactory.circleColor(PIN_COLOR),
                PropertyFactory.circleOpacity(0.22f),
            ),
        )
        s.addLayer(
            CircleLayer(PIN_DOT, PIN_SOURCE).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(PIN_COLOR),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.5f),
            ),
        )
    }

    LaunchedEffect(style, layerIds, index) {
        val s = style ?: return@LaunchedEffect
        layerIds.forEachIndexed { i, id ->
            s.getLayer(id)?.setProperties(PropertyFactory.rasterOpacity(if (i == index) RADAR_OPACITY else 0f))
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
