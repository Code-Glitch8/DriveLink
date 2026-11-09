package com.drivelink.location

import kotlin.math.*

/** One position fix. For peers, timestampMs is the local receive time. */
data class LocationSample(
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val bearing: Double,
    val accuracyM: Double,
    val timestampMs: Long,
    /** Extra GPS metadata (defaults keep peer samples and tests unchanged). */
    val hasSpeed: Boolean = true,
    val hasCourse: Boolean = false,
    val speedAccuracyMps: Double = -1.0,
    val fixAgeMs: Long = 0L,
    val fixTimeMs: Long = timestampMs,
)

data class Vec2(val x: Double, val y: Double)

object Geo {
    private const val R = 6_371_000.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * R * asin(sqrt(a))
    }

    /** Initial bearing from point 1 to point 2, degrees 0..360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** East/north offset in metres of (lat, lon) relative to origin (equirectangular, fine for < 5 km). */
    fun toLocal(lat0: Double, lon0: Double, lat: Double, lon: Double): Vec2 {
        val x = Math.toRadians(lon - lon0) * R * cos(Math.toRadians(lat0))
        val y = Math.toRadians(lat - lat0) * R
        return Vec2(x, y)
    }

    fun velocity(speedKmh: Double, headingDeg: Double): Vec2 {
        val s = speedKmh / 3.6
        val h = Math.toRadians(headingDeg)
        return Vec2(s * sin(h), s * cos(h))
    }

    /** Where the other vehicle is relative to my heading. */
    fun relativeDirection(myHeadingDeg: Double, bearingToOtherDeg: Double): String {
        val rel = ((bearingToOtherDeg - myHeadingDeg) % 360 + 360) % 360
        return when {
            rel < 45 || rel >= 315 -> "ahead"
            rel < 135 -> "right"
            rel < 225 -> "behind"
            else -> "left"
        }
    }
}
