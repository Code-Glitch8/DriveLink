package com.drivelink

import com.drivelink.map.LogicalGroup
import com.drivelink.map.StyleLayerClassifier
import com.drivelink.navigation.NavigationHelper
import com.drivelink.navigation.NoRouteException
import com.drivelink.navigation.NominatimGeocoder
import com.drivelink.navigation.OsrmRoutingProvider
import com.drivelink.navigation.RoutingUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MapAndRoutingTest {
    private val main = "womqu@oymgrCw`EonTozD_pR"
    private val alt = "womqu@oymgrCwcA_g^owHowH"

    private fun osrmBody(code: String = "Ok") = """
        {"code":"$code","routes":[
          {"geometry":"$main","distance":1850.0,"duration":240.0,"legs":[{"steps":[
            {"distance":900.0,"name":"Ring Road","maneuver":{"type":"depart","location":[77.2090,28.6139]}},
            {"distance":950.0,"name":"Mathura Road","maneuver":{"type":"turn","modifier":"left","location":[77.2200,28.6170]}},
            {"distance":0.0,"name":"","maneuver":{"type":"arrive","location":[77.2300,28.6200]}}]}]},
          {"geometry":"$alt","distance":2100.0,"duration":300.0,"legs":[{"steps":[]}]}
        ]}""".trimIndent()

    @Test fun classifierMapsOpenMapTilesLayers() {
        assertEquals(LogicalGroup.BASE_BACKGROUND, StyleLayerClassifier.classify("background", "BackgroundLayer", ""))
        assertEquals(LogicalGroup.BASE_WATER, StyleLayerClassifier.classify("water", "FillLayer", "water"))
        assertEquals(LogicalGroup.BASE_PARKS, StyleLayerClassifier.classify("park", "FillLayer", "park"))
        assertEquals(LogicalGroup.BASE_PARKS, StyleLayerClassifier.classify("landcover_wood", "FillLayer", "landcover"))
        assertEquals(LogicalGroup.BASE_LAND, StyleLayerClassifier.classify("landuse_residential", "FillLayer", "landuse"))
        assertEquals(LogicalGroup.BASE_BUILDINGS, StyleLayerClassifier.classify("building", "FillLayer", "building"))
        assertEquals(LogicalGroup.ROAD_MOTORWAY, StyleLayerClassifier.classify("road_motorway", "LineLayer", "transportation"))
        assertEquals(LogicalGroup.ROAD_PRIMARY, StyleLayerClassifier.classify("road_primary", "LineLayer", "transportation"))
        assertEquals(LogicalGroup.ROAD_SECONDARY, StyleLayerClassifier.classify("road_secondary_tertiary", "LineLayer", "transportation"))
        assertEquals(LogicalGroup.ROAD_RESIDENTIAL, StyleLayerClassifier.classify("road_minor", "LineLayer", "transportation"))
        assertEquals(LogicalGroup.ROAD_LABELS, StyleLayerClassifier.classify("highway-name-major", "SymbolLayer", "transportation_name"))
        assertEquals(LogicalGroup.BASE_OTHER, StyleLayerClassifier.classify("poi_r20", "SymbolLayer", "poi"))
    }

    @Test fun osrmParsesRouteStepsAndAlternatives() {
        val plan = OsrmRoutingProvider.parse(osrmBody())
        assertEquals(3, plan.points.size)
        assertEquals(28.6139, plan.points.first().first, 1e-6)
        assertEquals(77.2300, plan.points.last().second, 1e-6)
        assertEquals("1.9 km", plan.distanceText)
        assertEquals("4 min", plan.durationText)
        assertEquals(2, plan.steps.size) // the zero-length "arrive" step only supplies the end point
        assertEquals("Head out on Ring Road", plan.steps[0].instruction)
        assertEquals(28.6170, plan.steps[0].endLat, 1e-9)
        assertEquals("Turn left onto Mathura Road", plan.steps[1].instruction)
        assertEquals(77.2300, plan.steps[1].endLon, 1e-9)
        assertEquals(1, plan.alternatives.size)
    }

    @Test fun osrmNoRouteAndErrors() {
        try { OsrmRoutingProvider.parse("""{"code":"NoRoute","message":"x"}"""); fail() } catch (_: NoRouteException) {}
        try { OsrmRoutingProvider.parse("""{"code":"Ok","routes":[]}"""); fail() } catch (_: NoRouteException) {}
        try { OsrmRoutingProvider.parse("not json"); fail() } catch (_: RoutingUnavailableException) {}
        try { OsrmRoutingProvider.parse("""{"code":"InvalidQuery"}"""); fail() } catch (_: RoutingUnavailableException) {}
    }

    @Test fun osrmUrlUsesLonLatOrderAndTrimsBase() {
        val u = OsrmRoutingProvider.buildUrl("https://example.org/", 28.6139, 77.2090, 28.62, 77.23)
        assertEquals("https://example.org/route/v1/driving/77.209000,28.613900;77.230000,28.620000?overview=full&geometries=polyline6&steps=true&alternatives=3", u)
    }

    @Test fun formatting() {
        assertEquals("120 m", OsrmRoutingProvider.formatDistance(118.0))
        assertEquals("12.5 km", OsrmRoutingProvider.formatDistance(12_480.0))
        assertEquals("1 min", OsrmRoutingProvider.formatDuration(10.0))
        assertEquals("1 h 5 min", OsrmRoutingProvider.formatDuration(3_900.0))
    }

    @Test fun polyline6AndPolyline5Decode() {
        val pts = NavigationHelper.decodePolyline(main, 1e6)
        assertEquals(77.2200, pts[1].second, 1e-6)
        // Google's documented polyline5 example: (38.5,-120.2), (40.7,-120.95), (43.252,-126.453)
        val g = NavigationHelper.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, g.size)
        assertEquals(38.5, g[0].first, 1e-9)
        assertEquals(-126.453, g[2].second, 1e-9)
    }

    @Test fun nominatimParsesAndSkipsBadRows() {
        val body = """[{"display_name":"India Gate, New Delhi","lat":"28.6129","lon":"77.2295"},{"display_name":"bad","lat":"x","lon":"1"}]"""
        val places = NominatimGeocoder.parse(body)
        assertEquals(1, places.size)
        assertEquals("India Gate, New Delhi", places[0].name)
        assertTrue(NominatimGeocoder.parse("garbage").isEmpty())
    }
}
