package com.drivelink.navigation

import com.drivelink.hazards.Hazard
import com.drivelink.hazards.HazardConfidence
import com.drivelink.hazards.HazardGeometry
import com.drivelink.hazards.RouteHazard

/** Hazards found along one route option. */
data class RouteAssessment(
    val optionId: Int,
    val hazards: List<RouteHazard>,
    /** Weighted impact score: severity x confidence. Used to compare routes; not shown to the user. */
    val score: Double,
) {
    val passesThroughHazard: Boolean get() = hazards.isNotEmpty()
}

data class RouteAdvice(
    /** Concise warning for the selected route, or null when nothing relevant was reported on it. */
    val warning: String?,
    /** An alternative with fewer/less severe reported hazards, if one exists. */
    val suggestedOptionId: Int?,
    val timeDeltaS: Double?,
    val distanceDeltaM: Double?,
    val suggestion: String?,
)

/**
 * Hazard-aware route comparison. Hazards come from other drivers and are mostly unverified, so the wording is
 * always "reported" / "may affect" and never "blocked" or "closed" as established fact. Only hazards that could
 * change a driver's decision count: severity >= 2 (accident, blockage, closure, breakdown, other danger).
 * Construction, potholes and congestion are shown on the route list but never trigger a reroute suggestion.
 */
object RouteAdvisor {
    const val ROUTE_BUFFER_M = 50.0
    private const val MIN_SCORE_GAIN = 1.0    // suggested route must remove at least this much impact
    private const val MAX_EXTRA_TIME_FACTOR = 2.0 // ignore detours that more than double the trip time

    private fun weight(c: HazardConfidence) = when (c) {
        HazardConfidence.VERIFIED -> 1.2; HazardConfidence.HIGH -> 1.0; HazardConfidence.MEDIUM -> 0.8
        HazardConfidence.LOW -> 0.5; HazardConfidence.DISPUTED -> 0.3
    }

    fun assess(option: RouteOption, hazards: List<Hazard>, nowMs: Long): RouteAssessment {
        val onRoute = HazardGeometry.hazardsOnRoute(option.points, hazards, nowMs, ROUTE_BUFFER_M)
        val score = onRoute.filter { it.hazard.category.severity >= 2 }.sumOf { it.hazard.category.severity * weight(it.hazard.confidence) }
        return RouteAssessment(option.id, onRoute, score)
    }

    fun advise(options: List<RouteOption>, selectedIndex: Int, hazards: List<Hazard>, nowMs: Long): RouteAdvice {
        val sel = options.getOrNull(selectedIndex) ?: return RouteAdvice(null, null, null, null, null)
        val assessments = options.map { assess(it, hazards, nowMs) }
        val mine = assessments[selectedIndex]
        val relevant = mine.hazards.filter { it.hazard.category.severity >= 2 }
        if (relevant.isEmpty()) return RouteAdvice(null, null, null, null, null)

        val first = relevant.first()
        val all = relevant.map { it.hazard }
        val unverified = all.all { it.confidence == HazardConfidence.LOW }
        val warning = buildString {
            append(if (relevant.size == 1) "Reported ${first.hazard.category.label.lowercase()}" else "${relevant.size} reported hazards")
            append(" on this route")
            append(" in ${HazardGeometry.distanceText(first.alongRouteM)}")
            if (unverified) append(" (unconfirmed)")
        }

        val better = options.indices.filter { it != selectedIndex }
            .map { it to assessments[it] }
            .filter { (i, a) -> a.score <= mine.score - MIN_SCORE_GAIN && options[i].durationS <= sel.durationS * MAX_EXTRA_TIME_FACTOR }
            .minWithOrNull(compareBy({ it.second.score }, { options[it.first].durationS }))
            ?: return RouteAdvice(warning, null, null, null, null)

        val alt = options[better.first]
        val dt = alt.durationS - sel.durationS
        val dd = alt.distanceM - sel.distanceM
        val text = "${alt.kind.label} route avoids the reported hazard" + (if (relevant.size > 1) "s" else "") +
            " (${signedMin(dt)}, ${signedKm(dd)})"
        return RouteAdvice(warning, alt.id, dt, dd, text)
    }

    internal fun signedMin(s: Double): String {
        val m = Math.round(s / 60.0).toInt()
        return when { m == 0 -> "about the same time"; m > 0 -> "+$m min"; else -> "$m min" }
    }

    internal fun signedKm(m: Double): String {
        val km = m / 1000.0
        val v = "%.1f".format(java.util.Locale.US, Math.abs(km))
        return if (Math.abs(km) < 0.05) "same distance" else if (km > 0) "+$v km" else "-$v km"
    }
}
