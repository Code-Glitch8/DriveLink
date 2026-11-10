package com.drivelink.orientation

enum class HeadingSource { NONE, SENSOR, GPS }

/** One compass reading, already converted to TRUE north by the sensor layer where a declination was available. */
data class SensorHeading(val deg: Double, val reliable: Boolean, val atMs: Long)

/** One GPS course reading. [course] is only meaningful when [hasCourse]; [speedMps] is the raw fix speed. */
data class GpsCourse(val deg: Double, val hasCourse: Boolean, val speedMps: Double, val accuracyM: Double, val atMs: Long)

data class HeadingReading(val deg: Double?, val source: HeadingSource) {
    companion object { val None = HeadingReading(null, HeadingSource.NONE) }
}

/**
 * Chooses and smooths the heading shown on the map.
 *
 * - Stationary / slow: compass (rotation-vector) heading. GPS course is unreliable below walking pace.
 * - Moving: GPS course, but only if the fix has a course and decent accuracy.
 * - Between [SLOW_MPS] and [FAST_MPS] we keep whichever source we were already using (hysteresis) so the map
 *   does not flip-flop around the threshold.
 * - If the preferred source is missing or stale we fall back to the other one; if both are, we report
 *   [HeadingReading.None] so the UI can keep the last heading rather than show an invented one.
 * - Output is smoothed with a circular low-pass filter (correct across 0°/360°) and a small dead-band so
 *   sensor jitter does not cause constant micro-rotations.
 */
class HeadingFusion(
    private val staleMs: Long = 3_000L,
    private val deadBandDeg: Double = 1.5,
) {
    private var sensor: SensorHeading? = null
    private var gps: GpsCourse? = null
    private var smoothed: Double? = null
    private var source = HeadingSource.NONE
    private var preferGps = false
    private var lastAtMs = 0L

    fun onSensor(h: SensorHeading) { sensor = h }
    fun onGps(c: GpsCourse) { gps = c }

    fun reset() { sensor = null; gps = null; smoothed = null; source = HeadingSource.NONE; preferGps = false; lastAtMs = 0L }

    /** Pure selection step, exposed for tests. Returns the raw (unsmoothed) heading and its source. */
    fun select(nowMs: Long): Pair<Double, HeadingSource>? {
        val s = sensor?.takeIf { it.reliable && nowMs - it.atMs <= staleMs }
        val g = gps?.takeIf { it.hasCourse && it.accuracyM <= MAX_GPS_ACCURACY_M && nowMs - it.atMs <= staleMs }
        val speed = gps?.takeIf { nowMs - it.atMs <= staleMs }?.speedMps ?: 0.0
        preferGps = when {
            speed >= FAST_MPS -> true
            speed <= SLOW_MPS -> false
            else -> preferGps
        }
        return when {
            preferGps && g != null -> HeadingMath.normalize(g.deg) to HeadingSource.GPS
            s != null -> HeadingMath.normalize(s.deg) to HeadingSource.SENSOR
            g != null && speed >= SLOW_MPS -> HeadingMath.normalize(g.deg) to HeadingSource.GPS
            else -> null
        }
    }

    /** Call at a modest rate (e.g. on every sensor/GPS event). Returns the smoothed heading. */
    fun current(nowMs: Long): HeadingReading {
        val sel = select(nowMs) ?: return HeadingReading.None
        val (raw, src) = sel
        val prev = smoothed
        val dt = (nowMs - lastAtMs).coerceIn(0L, 1_000L)
        lastAtMs = nowMs
        val next = when {
            prev == null || src != source -> raw // new source: snap, don't swing through a large arc
            HeadingMath.absDelta(prev, raw) < deadBandDeg -> prev
            else -> {
                // Time-constant based low-pass: alpha grows with dt so the result is frame-rate independent.
                val tau = if (src == HeadingSource.GPS) GPS_TAU_MS else SENSOR_TAU_MS
                val alpha = dt / (tau + dt)
                HeadingMath.lerp(prev, raw, alpha.coerceAtLeast(MIN_ALPHA))
            }
        }
        smoothed = next
        source = src
        return HeadingReading(next, src)
    }

    companion object {
        const val SLOW_MPS = 1.5   // ≈ 5.4 km/h
        const val FAST_MPS = 3.0   // ≈ 10.8 km/h
        const val MAX_GPS_ACCURACY_M = 30.0
        const val SENSOR_TAU_MS = 250.0
        const val GPS_TAU_MS = 400.0
        const val MIN_ALPHA = 0.05
    }
}

/**
 * Decides whether a new camera update is worth issuing. Prevents re-animating the camera for jitter: a new
 * command is only allowed after a minimum interval AND a meaningful change in heading or position.
 */
class CameraUpdateGate(
    private val minIntervalMs: Long = 200L,
    private val minHeadingDeg: Double = 2.0,
    private val minMoveM: Double = 1.5,
) {
    private var lastAtMs = Long.MIN_VALUE
    private var lastHeading: Double? = null
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var hasPos = false

    fun reset() { lastAtMs = Long.MIN_VALUE; lastHeading = null; hasPos = false }

    fun shouldUpdate(nowMs: Long, lat: Double, lon: Double, headingDeg: Double?): Boolean {
        if (lastAtMs != Long.MIN_VALUE && nowMs - lastAtMs < minIntervalMs) return false
        val movedM = if (hasPos) com.drivelink.location.Geo.distanceM(lastLat, lastLon, lat, lon) else Double.MAX_VALUE
        val turned = when {
            headingDeg == null -> false
            lastHeading == null -> true
            else -> HeadingMath.absDelta(lastHeading!!, headingDeg) >= minHeadingDeg
        }
        if (!turned && movedM < minMoveM) return false
        lastAtMs = nowMs; lastHeading = headingDeg ?: lastHeading; lastLat = lat; lastLon = lon; hasPos = true
        return true
    }
}
