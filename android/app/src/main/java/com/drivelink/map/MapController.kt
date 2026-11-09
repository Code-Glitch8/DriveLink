package com.drivelink.map

import android.content.Context
import android.graphics.RectF
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Owns the MapView, its lifecycle, the style fallback chain and the [MapLayerManager].
 * Style chain: preferred theme style -> light style -> offline plain background, so vehicles and routes
 * (which do not depend on base tiles) keep working without a network.
 */
class MapController(context: Context, private var dark: Boolean) {
    private sealed interface StyleSource {
        data class Url(val url: String) : StyleSource
        data object Offline : StyleSource
    }

    val layers = MapLayerManager().also { it.registerDefaultLayerTree() }
    val mapView: MapView

    var onStatus: (MapStatus) -> Unit = {}
    var onVehicleTap: (String?) -> Unit = {}
    /** Fired when the user pans/zooms/rotates the map by hand (not for our own camera commands). */
    var onUserGesture: () -> Unit = {}

    private var map: MapLibreMap? = null
    private var chain: List<StyleSource> = emptyList()
    private var attempt = 0
    private var generation = 0
    private var styleReady = false
    private var pendingCamera: CameraCommand? = null
    private var started = false
    private var resumed = false
    private var destroyed = false

    init {
        MapLibre.getInstance(context.applicationContext)
        mapView = MapView(context)
        mapView.onCreate(null)
        mapView.addOnDidFailLoadingMapListener { message -> onStyleFailure(message) }
        mapView.getMapAsync { m ->
            if (destroyed) return@getMapAsync
            map = m
            configure(m)
            reloadStyle()
            pendingCamera?.let { apply(it) }
        }
    }

    private fun configure(m: MapLibreMap) {
        m.uiSettings.isAttributionEnabled = false // attribution is drawn permanently by MapAttribution (Compose)
        m.uiSettings.isLogoEnabled = false
        m.uiSettings.isCompassEnabled = false
        m.uiSettings.isTiltGesturesEnabled = false
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserGesture()
        }
        m.cameraPosition = CameraPosition.Builder().target(LatLng(28.6139, 77.2090)).zoom(4.5).build()
        val tapPad = mapView.resources.displayMetrics.density * 28f
        m.addOnMapClickListener { ll ->
            val p = m.projection.toScreenLocation(ll)
            val area = RectF(p.x - tapPad, p.y - tapPad, p.x + tapPad, p.y + tapPad)
            val id = if (layers.isStyleReady) {
                m.queryRenderedFeatures(area, MapIds.Layer.VEH_DIRECTION).firstOrNull()?.getStringProperty(MapIds.Prop.ID)
            } else null
            onVehicleTap(id)
            id != null
        }
    }

    // ------------------------------------------------------------------ style loading
    fun setTheme(isDark: Boolean) {
        if (isDark == dark) return
        dark = isDark
        if (map != null) reloadStyle()
    }

    fun reloadStyle() {
        generation++
        attempt = 0
        chain = buildList {
            if (dark) add(com.drivelink.config.AppConfig.mapStyleDarkUrl)
            add(com.drivelink.config.AppConfig.mapStyleLightUrl)
        }.distinct().map<String, StyleSource> { StyleSource.Url(it) } + StyleSource.Offline
        loadAttempt()
    }

    private fun loadAttempt() {
        val m = map ?: return
        val gen = generation
        styleReady = false
        layers.onStyleLost()
        onStatus(MapStatus.Loading)
        val src = chain[attempt]
        val builder = when (src) {
            is StyleSource.Url -> Style.Builder().fromUri(src.url)
            StyleSource.Offline -> Style.Builder().fromJson(MapStyles.offlineJson(dark))
        }
        m.setStyle(builder) { style ->
            if (gen != generation || destroyed) return@setStyle
            styleReady = true
            layers.onStyleLoaded(style)
            onStatus(when {
                src is StyleSource.Offline -> MapStatus.Offline("Map tiles unavailable (are you offline?). Vehicles and routes are still shown.")
                attempt > 0 -> MapStatus.Fallback("Preferred map style unavailable; showing a fallback style.")
                else -> MapStatus.Ready
            })
        }
        // Watchdog: a style that never answers (captive portal, black-holed DNS) must not hang the map forever.
        mapView.postDelayed({ if (gen == generation && !styleReady && !destroyed) onStyleFailure("timeout") }, STYLE_TIMEOUT_MS)
    }

    private fun onStyleFailure(@Suppress("UNUSED_PARAMETER") message: String?) {
        if (destroyed || styleReady) return // tile-level errors after a good style load are not fatal
        if (attempt < chain.lastIndex) { attempt++; loadAttempt() }
    }

    // ------------------------------------------------------------------ camera
    fun apply(cmd: CameraCommand) {
        val m = map ?: run { pendingCamera = cmd; return }
        try {
            when (cmd) {
                is CameraCommand.Center ->
                    m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(cmd.lat, cmd.lon), cmd.zoom), 800)
                is CameraCommand.Track -> {
                    val b = CameraPosition.Builder().target(LatLng(cmd.lat, cmd.lon)).bearing(cmd.bearing ?: 0.0).tilt(0.0)
                    cmd.zoom?.let { b.zoom(it) } ?: b.zoom(m.cameraPosition.zoom)
                    m.easeCamera(CameraUpdateFactory.newCameraPosition(b.build()), cmd.durationMs.coerceAtLeast(1))
                }
                is CameraCommand.Follow -> m.easeCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(LatLng(cmd.lat, cmd.lon)).zoom(cmd.zoom).bearing(cmd.bearing).tilt(0.0).build()), 900)
                is CameraCommand.Fit -> if (cmd.points.size >= 2) {
                    val b = LatLngBounds.Builder()
                    cmd.points.forEach { b.include(LatLng(it.first, it.second)) }
                    m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), cmd.leftPx, cmd.topPx, cmd.rightPx, cmd.bottomPx), 900)
                }
            }
        } catch (_: Exception) { /* degenerate bounds etc.: leave the camera where it is */ }
    }

    fun zoomBy(delta: Double) { map?.animateCamera(CameraUpdateFactory.zoomBy(delta)) }

    // ------------------------------------------------------------------ lifecycle
    fun onStart() { if (destroyed || started) return; started = true; mapView.onStart() }
    fun onResume() { if (destroyed || resumed) return; resumed = true; mapView.onResume() }
    fun onPause() { if (!resumed || destroyed) return; resumed = false; mapView.onPause() }
    fun onStop() { if (!started || destroyed) return; started = false; mapView.onStop() }
    fun onLowMemory() { if (!destroyed) mapView.onLowMemory() }

    fun destroy() {
        if (destroyed) return
        onPause(); onStop()
        destroyed = true
        layers.onStyleLost()
        map = null
        mapView.onDestroy()
    }

    private companion object { const val STYLE_TIMEOUT_MS = 12_000L }
}

/** Lets screens drive the map (zoom, recenter, retry) and lets the debug panel inspect it. */
class MapHandle {
    internal var controller: MapController? = null
    val layers: MapLayerManager? get() = controller?.layers
    fun zoomBy(delta: Double) { controller?.zoomBy(delta) }
    fun center(lat: Double, lon: Double, zoom: Double = 15.0) { controller?.apply(CameraCommand.Center(lat, lon, zoom)) }
    fun retryStyle() { controller?.reloadStyle() }
}
