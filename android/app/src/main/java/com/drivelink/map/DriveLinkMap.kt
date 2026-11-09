package com.drivelink.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * The DriveLink map: MapLibre Native in an AndroidView.
 *
 * The MapView, style and layers are created once per composition. Recomposition only pushes new GeoJSON into
 * existing sources (via [MapLayerManager]); it never recreates the style or the layers.
 */
@Composable
fun DriveLinkMap(
    handle: MapHandle,
    dark: Boolean,
    me: MapMe?,
    destination: MapDestination?,
    route: MapRoute?,
    vehicles: List<MapVehicle>,
    hazards: List<MapHazard> = emptyList(),
    selectedVehicleId: String?,
    camera: CameraCommand?,
    modifier: Modifier = Modifier.fillMaxSize(),
    onVehicleTap: (String?) -> Unit = {},
    onStatus: (MapStatus) -> Unit = {},
    onUserGesture: () -> Unit = {},
    onHazardTap: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tapState = rememberUpdatedState(onVehicleTap)
    val statusState = rememberUpdatedState(onStatus)
    val gestureState = rememberUpdatedState(onUserGesture)
    val hazardTapState = rememberUpdatedState(onHazardTap)
    val controller = remember {
        MapController(context, dark).also { c ->
            c.onVehicleTap = { id -> tapState.value(id) }
            c.onStatus = { s -> statusState.value(s) }
            c.onUserGesture = { gestureState.value() }
            c.onHazardTap = { id -> hazardTapState.value(id) }
        }
    }

    DisposableEffect(lifecycle, controller) {
        handle.controller = controller
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.onStart()
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
                Lifecycle.Event.ON_STOP -> controller.onStop()
                Lifecycle.Event.ON_DESTROY -> controller.destroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer) // replays CREATE/START/RESUME if the lifecycle is already past them
        onDispose {
            lifecycle.removeObserver(observer)
            if (handle.controller === controller) handle.controller = null
            controller.destroy()
        }
    }

    LaunchedEffect(dark) { controller.setTheme(dark) }
    LaunchedEffect(vehicles, selectedVehicleId) { controller.layers.setVehicles(vehicles, selectedVehicleId) }
    LaunchedEffect(hazards) { controller.layers.setHazards(hazards) }
    LaunchedEffect(route) { controller.layers.setRoute(route) }
    LaunchedEffect(destination) { controller.layers.setDestination(destination) }
    LaunchedEffect(me) { controller.layers.setMe(me) }
    LaunchedEffect(camera) { camera?.let { controller.apply(it) } }

    AndroidView(factory = { controller.mapView }, modifier = modifier)
}
