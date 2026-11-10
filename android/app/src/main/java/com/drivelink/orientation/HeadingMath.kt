package com.drivelink.orientation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2

/** Pure circular-angle helpers. Everything is in degrees; results are normalised to [0, 360). */
object HeadingMath {
    fun normalize(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else if (r >= 360.0) 0.0 else r
    }

    /** Signed shortest rotation from [from] to [to], in (-180, 180]. Handles the 359° → 1° wrap. */
    fun delta(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    fun absDelta(a: Double, b: Double): Double = abs(delta(a, b))

    /** Moves [current] toward [target] by [alpha] (0..1) along the shortest arc. */
    fun lerp(current: Double, target: Double, alpha: Double): Double =
        normalize(current + delta(current, target) * alpha.coerceIn(0.0, 1.0))

    /** Mean of angles (vector average) — safe across the wrap. Null for an empty list or exactly opposed angles. */
    fun mean(angles: List<Double>): Double? {
        if (angles.isEmpty()) return null
        var x = 0.0; var y = 0.0
        angles.forEach { x += cos(Math.toRadians(it)); y += sin(Math.toRadians(it)) }
        if (abs(x) < 1e-9 && abs(y) < 1e-9) return null
        return normalize(Math.toDegrees(atan2(y, x)))
    }
}
