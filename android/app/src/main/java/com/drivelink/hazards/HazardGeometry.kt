package com.drivelink.hazards

import com.drivelink.location.Geo
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** A (lat, lon) bounding box. */
data class BBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

/** A hazard that matters to a route, with how far along the route it is. */
data class RouteHazard(val hazard: Hazard, val distanceFromRouteM: Double, val alongRouteM: Double)

object HazardGeometry {
    /** Box around a point. Longitude span is widened with latitude so the box stays ~radius metres wide. */
    fun bboxAround(lat: Double, lon: Double, radiusM: Double): BBox {
        val dLat = radiusM / 111_000.0
        val dLon = radiusM / (111_000.0 * max(cos(Math.toRadians(lat)), 0.01))
        return BBox((lat - dLat).coerceAtLeast(-90.0), (lon - dLon).coerceAtLeast(-180.0), (lat + dLat).coerceAtMost(90.0), (lon + dLon).coerceAtMost(180.0))
    }

    /**
     * Covers a route with at most [maxBoxes] boxes, each no larger than [maxSpanDeg] (the server rejects larger).
     * Points are binned into a coarse grid and only occupied cells are queried, so a diagonal route does not
     * request the whole bounding rectangle.
     */
    fun bboxesForRoute(points: List<Pair<Double, Double>>, bufferM: Double, maxSpanDeg: Double = 1.0, maxBoxes: Int = 6): List<BBox> {
        if (points.isEmpty()) return emptyList()
        val cells = LinkedHashMap<Pair<Int, Int>, BBox>()
        for ((lat, lon) in points) {
            val key = Math.floor(lat / maxSpanDeg).toInt() to Math.floor(lon / maxSpanDeg).toInt()
            val b = bboxAround(lat, lon, bufferM)
            cells[key] = cells[key]?.let { BBox(min(it.minLat, b.minLat), min(it.minLon, b.minLon), max(it.maxLat, b.maxLat), max(it.maxLon, b.maxLon)) } ?: b
        }
        // Cell boxes can exceed the span by the buffer; clamp so the server never rejects them.
        return cells.values.take(maxBoxes).map { b ->
            val cLat = (b.minLat + b.maxLat) / 2; val cLon = (b.minLon + b.maxLon) / 2
            val h = min((b.maxLat - b.minLat) / 2, 0.95 * maxSpanDeg / 2 + 0.05)
            val w = min((b.maxLon - b.minLon) / 2, 0.95 * maxSpanDeg / 2 + 0.05)
            BBox(cLat - h, cLon - w, cLat + h, cLon + w)
        }
    }

    /** Shortest distance (m) from a point to a polyline, plus the along-track distance to the closest point. */
    fun distanceToPolyline(lat: Double, lon: Double, line: List<Pair<Double, Double>>): Pair<Double, Double> {
        if (line.isEmpty()) return Double.MAX_VALUE to 0.0
        if (line.size == 1) return Geo.distanceM(lat, lon, line[0].first, line[0].second) to 0.0
        var best = Double.MAX_VALUE; var bestAlong = 0.0; var acc = 0.0
        for (i in 0 until line.size - 1) {
            val (aLat, aLon) = line[i]; val (bLat, bLon) = line[i + 1]
            val segLen = Geo.distanceM(aLat, aLon, bLat, bLon)
            val b = Geo.toLocal(aLat, aLon, bLat, bLon)
            val p = Geo.toLocal(aLat, aLon, lat, lon)
            val len2 = b.x * b.x + b.y * b.y
            val t = if (len2 < 1e-9) 0.0 else ((p.x * b.x + p.y * b.y) / len2).coerceIn(0.0, 1.0)
            val dx = p.x - b.x * t; val dy = p.y - b.y * t
            val d = Math.hypot(dx, dy)
            if (d < best) { best = d; bestAlong = acc + segLen * t }
            acc += segLen
        }
        return best to bestAlong
    }

    /**
     * Active hazards whose position lies within [bufferM] of the route, nearest-along-route first.
     * Only hazards the user should plausibly react to are returned: informational low-severity ones (e.g. a
     * pothole 40 m off the road on a motorway) fall outside the buffer anyway.
     */
    fun hazardsOnRoute(route: List<Pair<Double, Double>>, hazards: List<Hazard>, nowMs: Long, bufferM: Double = 50.0): List<RouteHazard> =
        hazards.asSequence().filter { it.isActive(nowMs) }.mapNotNull { h ->
            val (d, along) = distanceToPolyline(h.lat, h.lon, route)
            if (d <= bufferM) RouteHazard(h, d, along) else null
        }.sortedBy { it.alongRouteM }.toList()

    /**
     * Hazards worth showing/announcing around the current position: within the category's relevance radius and not
     * clearly behind us (when we know our heading and are moving).
     */
    fun nearbyRelevant(hazards: List<Hazard>, lat: Double, lon: Double, headingDeg: Double?, moving: Boolean, nowMs: Long): List<Pair<Hazard, Double>> =
        hazards.asSequence().filter { it.isActive(nowMs) }.mapNotNull { h ->
            val d = Geo.distanceM(lat, lon, h.lat, h.lon)
            if (d > h.category.relevanceRadiusM) return@mapNotNull null
            if (moving && headingDeg != null && d > 60) {
                val rel = com.drivelink.orientation.HeadingMath.absDelta(headingDeg, Geo.bearingDeg(lat, lon, h.lat, h.lon))
                if (rel > 100) return@mapNotNull null // behind us
            }
            h to d
        }.sortedBy { it.second }.toList()

    fun ageText(nowMs: Long, thenMs: Long): String {
        val m = ((nowMs - thenMs) / 60_000).coerceAtLeast(0)
        return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
    }

    /** "about 25 min" / "about 3 h" until [expiresMs]; "soon" when under a minute or already past. */
    fun remainingText(nowMs: Long, expiresMs: Long): String {
        val m = (expiresMs - nowMs) / 60_000
        return when { m < 1 -> "soon"; m < 60 -> "in about $m min"; m < 1440 -> "in about ${m / 60} h"; else -> "in about ${m / 1440} d" }
    }

    fun distanceText(m: Double): String = if (m < 1000) "${(Math.round(m / 10.0) * 10).toInt()} m" else "%.1f km".format(m / 1000)
}
