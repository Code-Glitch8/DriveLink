package com.drivelink.navigation

import com.drivelink.hazards.HazardGeometry

/** Pure helpers for building [RouteOption]s from raw provider output. */
object RouteUtil {
    /**
     * Major roads of a route: named roads covering the most distance, in the order they are driven.
     * Unnamed segments are ignored; at most [max] names, each needing at least [minShare] of the named distance.
     */
    fun majorRoads(steps: List<Pair<String, Double>>, max: Int = 3, minShare: Double = 0.12): List<String> {
        val dist = LinkedHashMap<String, Double>()
        steps.forEach { (n, d) -> if (n.isNotBlank()) dist[n.trim()] = (dist[n.trim()] ?: 0.0) + d }
        val total = dist.values.sum()
        if (total <= 0) return emptyList()
        val top = dist.entries.filter { it.value / total >= minShare }.sortedByDescending { it.value }.take(max).map { it.key }.toSet()
        return dist.keys.filter { it in top }
    }

    /** Fraction (0..1) of [a]'s sampled points that lie within [thresholdM] of polyline [b]. */
    fun fractionNear(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>, thresholdM: Double = 40.0, maxSamples: Int = 60): Double {
        if (a.isEmpty() || b.size < 2) return 0.0
        val stride = (a.size / maxSamples).coerceAtLeast(1)
        var n = 0; var near = 0
        for (i in a.indices step stride) {
            n++
            if (HazardGeometry.distanceToPolyline(a[i].first, a[i].second, b).first <= thresholdM) near++
        }
        return near.toDouble() / n
    }

    /** Drops candidates that essentially retrace an already kept route (OSRM sometimes returns near-identical alternatives). */
    fun dropNearDuplicates(routes: List<RouteOption>, similarity: Double = 0.9): List<RouteOption> {
        val kept = ArrayList<RouteOption>()
        for (r in routes) if (kept.none { fractionNear(r.points, it.points) >= similarity && fractionNear(it.points, r.points) >= similarity }) kept += r
        return kept
    }

    /** Orders by duration, labels the quickest FASTEST, the clearly shortest SHORTEST (if different), others ALTERNATIVE, and re-numbers ids. */
    fun label(routes: List<RouteOption>): List<RouteOption> {
        if (routes.isEmpty()) return routes
        val byTime = routes.sortedBy { it.durationS }
        val fastest = byTime.first()
        val shortest = routes.minByOrNull { it.distanceM }!!
        val shortestDistinct = shortest !== fastest && shortest.distanceM < fastest.distanceM * 0.97
        return byTime.mapIndexed { i, r ->
            val kind = when { r === fastest -> RouteKind.FASTEST; shortestDistinct && r === shortest -> RouteKind.SHORTEST; else -> RouteKind.ALTERNATIVE }
            r.copy(id = i, kind = kind)
        }
    }
}
