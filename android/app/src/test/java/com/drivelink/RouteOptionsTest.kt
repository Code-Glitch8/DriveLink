package com.drivelink

import com.drivelink.hazards.Hazard
import com.drivelink.hazards.HazardCategory
import com.drivelink.hazards.HazardConfidence
import com.drivelink.navigation.RouteAdvisor
import com.drivelink.navigation.RouteKind
import com.drivelink.navigation.RouteOption
import com.drivelink.navigation.RoutePlan
import com.drivelink.navigation.RouteUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteOptionsTest {
    // Two roads between A (28.0,77.0) and B (28.0,77.02): a direct east-west line, and a detour 2 km north.
    private val direct = (0..20).map { 28.0 to 77.0 + it * 0.001 }
    private val detour = listOf(28.0 to 77.0, 28.018 to 77.002) + (3..17).map { 28.018 to 77.0 + it * 0.001 } + listOf(28.018 to 77.018, 28.0 to 77.02)

    private fun opt(id: Int, pts: List<Pair<Double, Double>>, dist: Double, dur: Double, kind: RouteKind = RouteKind.ALTERNATIVE) =
        RouteOption(id, kind, pts, dist, dur, emptyList(), emptyList())

    private fun hz(lat: Double, lon: Double, cat: HazardCategory = HazardCategory.ACCIDENT, conf: HazardConfidence = HazardConfidence.MEDIUM, id: String = "h") =
        Hazard(id, cat, lat, lon, null, 0, 0, Long.MAX_VALUE, true, 2, 0, conf, null)

    @Test fun majorRoadsKeepsBiggestNamedInDrivingOrder() {
        val steps = listOf("" to 100.0, "Ring Road" to 3000.0, "Link St" to 100.0, "NH 48" to 5000.0, "Ring Road" to 500.0, "Side Ln" to 400.0)
        assertEquals(listOf("Ring Road", "NH 48"), RouteUtil.majorRoads(steps, max = 2))
        assertEquals(listOf("Ring Road", "NH 48"), RouteUtil.majorRoads(steps, max = 3)) // Link/Side are < 12 %
        assertTrue(RouteUtil.majorRoads(listOf("" to 10.0)).isEmpty())
    }

    @Test fun nearDuplicateRoutesAreDropped() {
        val shifted = direct.map { it.first + 0.0001 to it.second } // ~11 m off
        val kept = RouteUtil.dropNearDuplicates(listOf(opt(0, direct, 2000.0, 200.0), opt(1, shifted, 2005.0, 202.0), opt(2, detour, 3800.0, 400.0)))
        assertEquals(listOf(0, 2), kept.map { it.id })
    }

    @Test fun labelsFastestShortestAlternativeAndRenumbers() {
        val a = opt(0, direct, 5000.0, 600.0)     // slow but short
        val b = opt(1, direct, 6000.0, 500.0)     // fastest
        val c = opt(2, detour, 7000.0, 700.0)
        val l = RouteUtil.label(listOf(a, b, c))
        assertEquals(listOf(500.0, 600.0, 700.0), l.map { it.durationS })
        assertEquals(listOf(RouteKind.FASTEST, RouteKind.SHORTEST, RouteKind.ALTERNATIVE), l.map { it.kind })
        assertEquals(listOf(0, 1, 2), l.map { it.id })
    }

    @Test fun fastestIsAlsoShortestSoNoShortestLabel() {
        val l = RouteUtil.label(listOf(opt(0, direct, 5000.0, 400.0), opt(1, detour, 6000.0, 500.0)))
        assertEquals(listOf(RouteKind.FASTEST, RouteKind.ALTERNATIVE), l.map { it.kind })
    }

    @Test fun planSelectionSwapsMainAndAlternatives() {
        val l = RouteUtil.label(listOf(opt(0, direct, 2000.0, 200.0), opt(1, detour, 3800.0, 400.0)))
        val plan = RoutePlan(l[0].points, l[0].distanceText, l[0].durationText, l[0].steps, listOf(l[1].points), l, 0)
        val p2 = plan.select(1)
        assertEquals(detour, p2.points); assertEquals(listOf(direct), p2.alternatives); assertEquals(1, p2.selected)
        assertEquals("3 min", plan.durationText); assertEquals("7 min", p2.durationText)
        assertEquals(0, p2.optionIndexOfAlternative(0)); assertEquals(1, plan.optionIndexOfAlternative(0))
        assertEquals(plan, plan.select(9)) // out of range is a no-op
    }

    @Test fun noHazardsNoAdvice() {
        val l = RouteUtil.label(listOf(opt(0, direct, 2000.0, 200.0), opt(1, detour, 3800.0, 400.0)))
        val a = RouteAdvisor.advise(l, 0, emptyList(), 0)
        assertNull(a.warning); assertNull(a.suggestedOptionId)
    }

    @Test fun hazardOnSelectedRouteWarnsAndSuggestsTheCleanAlternativeWithDeltas() {
        val l = RouteUtil.label(listOf(opt(0, direct, 2000.0, 200.0), opt(1, detour, 3800.0, 380.0)))
        val a = RouteAdvisor.advise(l, 0, listOf(hz(28.0, 77.010)), 0)
        assertEquals("Reported accident on this route in 980 m", a.warning)
        assertEquals(1, a.suggestedOptionId)
        assertEquals(180.0, a.timeDeltaS!!, 1e-9)
        assertEquals("Alternative route avoids the reported hazard (+3 min, +1.8 km)", a.suggestion)
    }

    @Test fun warningNeverClaimsBlockedAndFlagsUnconfirmed() {
        val l = RouteUtil.label(listOf(opt(0, direct, 2000.0, 200.0)))
        val a = RouteAdvisor.advise(l, 0, listOf(hz(28.0, 77.010, HazardCategory.ROAD_CLOSURE, HazardConfidence.LOW)), 0)
        assertTrue(a.warning!!.contains("unconfirmed"))
        assertFalse(a.warning!!.lowercase().contains("blocked"))
        assertNull(a.suggestedOptionId) // nothing better to offer
    }

    @Test fun hazardOnEveryRouteGivesNoSuggestion() {
        val l = RouteUtil.label(listOf(opt(0, direct, 2000.0, 200.0), opt(1, detour, 3800.0, 400.0)))
        val a = RouteAdvisor.advise(l, 0, listOf(hz(28.0, 77.010, id = "a"), hz(28.018, 77.010, id = "b")), 0)
        assertNotNull(a.warning); assertNull(a.suggestedOptionId)
    }

    @Test fun lowSeverityAndExpiredAndFarHazardsAreIgnored() {
        val l = RouteUtil.label(listOf(opt(0, direct, 2000.0, 200.0), opt(1, detour, 3800.0, 400.0)))
        val pothole = hz(28.0, 77.010, HazardCategory.POTHOLE)
        val jam = hz(28.0, 77.012, HazardCategory.TRAFFIC_CONGESTION)
        val expired = hz(28.0, 77.014).copy(expiresAtMs = 5)
        val far = hz(28.01, 77.010)
        assertNull(RouteAdvisor.advise(l, 0, listOf(pothole, jam, expired, far), 10).warning)
        // but they are still listed for the route assessment
        assertEquals(2, RouteAdvisor.assess(l[0], listOf(pothole, jam, expired, far), 10).hazards.size)
    }

    @Test fun disputedReportCountsLessThanConfirmedOne() {
        val l = RouteUtil.label(listOf(opt(0, direct, 2000.0, 200.0), opt(1, detour, 3800.0, 400.0)))
        val weak = RouteAdvisor.assess(l[0], listOf(hz(28.0, 77.010, conf = HazardConfidence.DISPUTED)), 0).score
        val strong = RouteAdvisor.assess(l[0], listOf(hz(28.0, 77.010, conf = HazardConfidence.HIGH)), 0).score
        assertTrue(weak < strong)
        // a disputed report alone is below the threshold for suggesting a detour
        assertNull(RouteAdvisor.advise(l, 0, listOf(hz(28.0, 77.010, conf = HazardConfidence.DISPUTED)), 0).suggestedOptionId)
    }

    @Test fun deltaTextHelpers() {
        assertEquals("+4 min", RouteAdvisor.signedMin(240.0)); assertEquals("-2 min", RouteAdvisor.signedMin(-120.0))
        assertEquals("about the same time", RouteAdvisor.signedMin(10.0))
        assertEquals("+1.8 km", RouteAdvisor.signedKm(1800.0)); assertEquals("-0.5 km", RouteAdvisor.signedKm(-500.0))
    }
}
