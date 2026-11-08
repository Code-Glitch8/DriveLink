package com.drivelink.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Fused location with adaptive rate: ~1 s while moving, ~5 s while stationary. Always check accuracyM before
 * trusting a fix; smartphone GPS is not lane-accurate.
 */
class LocationProvider(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private var onSample: ((LocationSample) -> Unit)? = null
    private var intervalMs = 1000L
    private var running = false
    private var lastBearing = 0.0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            if (loc.hasBearing() && loc.speed > 1.0f) lastBearing = loc.bearing.toDouble()
            val s = LocationSample(
                loc.latitude, loc.longitude,
                if (loc.hasSpeed()) (loc.speed * 3.6).coerceAtLeast(0.0) else 0.0,
                lastBearing,
                if (loc.hasAccuracy()) loc.accuracy.toDouble() else 9999.0,
                System.currentTimeMillis(),
            )
            onSample?.invoke(s)
            val wanted = if (s.speedKmh > 3.0) 1000L else 5000L
            if (wanted != intervalMs && running) { intervalMs = wanted; request() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun request() {
        client.removeLocationUpdates(callback)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2).build()
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    /** Caller must have location permission. */
    fun start(listener: (LocationSample) -> Unit) { onSample = listener; running = true; intervalMs = 1000L; request() }

    fun stop() { running = false; client.removeLocationUpdates(callback); onSample = null }
}
