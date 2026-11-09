package com.drivelink.navigation

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PlaceCategory(val label: String) {
    LANDMARK("Landmark"), ADDRESS("Address"), CITY("City / area"), TRANSPORT("Transport"), FOOD("Food & drink"), SHOP("Shop"),
    FUEL("Fuel & charging"), HEALTH("Health"), LODGING("Hotel"), PARK("Park"), EDUCATION("Education"), OTHER("Place")
}

/** A search result / destination. [subtitle] carries the locality so same-named places can be told apart. */
data class Place(
    val name: String, val lat: Double, val lon: Double,
    val subtitle: String? = null,
    val category: PlaceCategory = PlaceCategory.OTHER,
)
data class RouteStep(val instruction: String, val endLat: Double, val endLon: Double, val distanceText: String)
data class RoutePlan(
    val points: List<Pair<Double, Double>>,
    val distanceText: String,
    val durationText: String,
    val steps: List<RouteStep>,
    val alternatives: List<List<Pair<Double, Double>>> = emptyList(),
)

/**
 * Facade used by the UI. Search: Android Geocoder first, Nominatim (OpenStreetMap) as a fallback.
 * Routing: a [RoutingProvider] (OSRM by default). Nothing here touches the map renderer or needs an API key.
 */
object NavigationHelper {
    @Volatile var routing: RoutingProvider = OsrmRoutingProvider()
    private val nominatim by lazy { NominatimGeocoder() }

    @Suppress("DEPRECATION")
    suspend fun search(context: Context, query: String): List<Place> {
        val local = withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) emptyList()
            else try {
                Geocoder(context).getFromLocationName(query, 5).orEmpty().map { Place(it.getAddressLine(0) ?: query, it.latitude, it.longitude) }
            } catch (_: Exception) { emptyList() } // the platform Geocoder throws on network problems; fall through to Nominatim
        }
        return local.ifEmpty { nominatim.search(query) }
    }

    suspend fun route(fromLat: Double, fromLon: Double, to: Place): RoutePlan =
        routing.route(fromLat, fromLon, to.lat, to.lon)

    /** Hand-off for full voice turn-by-turn: Google Maps if installed, otherwise any app that handles geo: links. */
    fun openTurnByTurn(context: Context, to: Place): Boolean {
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${to.lat},${to.lon}&mode=d")).setPackage("com.google.android.apps.maps"),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:${to.lat},${to.lon}?q=${to.lat},${to.lon}(${Uri.encode(to.name)})")),
        )
        for (i in attempts) {
            try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) { /* try next */ }
        }
        return false
    }

    /** Decodes an encoded polyline into (lat, lon) pairs. [precision] is 1e5 (Google) or 1e6 (OSRM polyline6). */
    fun decodePolyline(encoded: String, precision: Double = 1e5): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var index = 0; var lat = 0; var lng = 0
        while (index < encoded.length) {
            var shift = 0; var result = 0; var b: Int
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(lat / precision to lng / precision)
        }
        return out
    }
}
