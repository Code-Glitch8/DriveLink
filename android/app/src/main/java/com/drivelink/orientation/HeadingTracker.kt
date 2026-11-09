package com.drivelink.orientation

import android.content.Context
import com.drivelink.location.LocationSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-selectable map orientation. Remembered for the lifetime of the app process ("session"). */
enum class MapOrientationMode { HEADING_UP, NORTH_UP }

object MapOrientationSession {
    @Volatile var mode: MapOrientationMode = MapOrientationMode.HEADING_UP
}

/**
 * Single shared heading pipeline: compass sensor + GPS course -> [HeadingFusion] -> [heading].
 * Reference counted so the sensors only run while something (the driving screen or a running journey) needs
 * them, and always stop when the last user releases. Callers: [acquire] when they start, [release] when they stop.
 */
object HeadingTracker {
    private val fusion = HeadingFusion()
    private val _heading = MutableStateFlow(HeadingReading.None)
    val heading: StateFlow<HeadingReading> = _heading.asStateFlow()

    private var sensor: OrientationSensor? = null
    private var refs = 0
    private var lastDeclinationMs = 0L

    /** False when the device has no usable orientation sensor (GPS course is then the only source). */
    @Volatile var sensorAvailable: Boolean = true; private set

    @Synchronized fun acquire(context: Context) {
        refs++
        if (refs > 1) return
        val s = sensor ?: OrientationSensor(context) { h -> onSensor(h) }.also { sensor = it }
        sensorAvailable = s.available
        fusion.reset()
        _heading.value = HeadingReading.None
        s.start()
    }

    @Synchronized fun release() {
        if (refs == 0) return
        refs--
        if (refs == 0) sensor?.stop()
    }

    @Synchronized private fun onSensor(h: SensorHeading) {
        fusion.onSensor(h)
        publish(h.atMs)
    }

    /** Feed every GPS fix. Safe to call whether or not the sensor is running. */
    @Synchronized fun onGps(s: LocationSample) {
        fusion.onGps(GpsCourse(s.bearing, s.hasCourse, s.speedKmh / 3.6, s.accuracyM, s.timestampMs))
        val now = System.currentTimeMillis()
        if (refs > 0 && now - lastDeclinationMs > 10 * 60_000L && s.accuracyM < 500) {
            lastDeclinationMs = now
            sensor?.setLocation(s.lat, s.lon)
        }
        publish(now)
    }

    private fun publish(nowMs: Long) {
        val r = fusion.current(nowMs)
        val old = _heading.value
        val changed = r.source != old.source || (r.deg != null && (old.deg == null || HeadingMath.absDelta(old.deg, r.deg) >= 0.5))
        if (changed && r.deg != null) _heading.value = r
        // When no reliable source exists we deliberately keep the previous value; callers check age via source=NONE only on start.
    }

    /** Latest smoothed heading, or null if none has been established yet. */
    fun current(): Double? = _heading.value.deg
}
