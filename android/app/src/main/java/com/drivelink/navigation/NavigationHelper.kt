package com.drivelink.navigation

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import com.drivelink.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class Place(val name: String, val lat: Double, val lon: Double)
data class RouteStep(val instruction: String, val endLat: Double, val endLon: Double, val distanceText: String)
data class RoutePlan(val points: List<Pair<Double, Double>>, val distanceText: String, val durationText: String, val steps: List<RouteStep>)

/**
 * Destination search (Android Geocoder), route preview + step list (Google Directions API; the key must have it
 * enabled) and hand-off to the Google Maps app for full turn-by-turn. Independent of Nearby Connections.
 */
object NavigationHelper {
    private val http = OkHttpClient()

    @Suppress("DEPRECATION")
    suspend fun search(context: Context, query: String): List<Place> = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) throw IllegalStateException("Place search is not available on this device")
        Geocoder(context).getFromLocationName(query, 5).orEmpty().map {
            Place(it.getAddressLine(0) ?: query, it.latitude, it.longitude)
        }
    }

    suspend fun route(fromLat: Double, fromLon: Double, to: Place): RoutePlan = withContext(Dispatchers.IO) {
        if (!AppConfig.mapsConfigured) throw IllegalStateException("No Google Maps API key configured")
        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=$fromLat,$fromLon&destination=${to.lat},${to.lon}&mode=driving&key=${AppConfig.mapsKey}"
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        val o = JSONObject(body)
        val status = o.optString("status")
        if (status != "OK") throw IllegalStateException("Directions unavailable: $status ${o.optString("error_message")}".trim())
        val route = o.getJSONArray("routes").getJSONObject(0)
        val leg = route.getJSONArray("legs").getJSONObject(0)
        val steps = leg.getJSONArray("steps")
        val list = (0 until steps.length()).map {
            val s = steps.getJSONObject(it)
            val end = s.getJSONObject("end_location")
            RouteStep(s.optString("html_instructions").replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim(),
                end.getDouble("lat"), end.getDouble("lng"), s.getJSONObject("distance").optString("text"))
        }
        RoutePlan(decodePolyline(route.getJSONObject("overview_polyline").getString("points")),
            leg.getJSONObject("distance").optString("text"), leg.getJSONObject("duration").optString("text"), list)
    }

    /** Full turn-by-turn voice navigation via the Google Maps app. Returns false if it is not installed. */
    fun openTurnByTurn(context: Context, to: Place): Boolean {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${to.lat},${to.lon}&mode=d"))
            .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(i); true } catch (_: Exception) { false }
    }

    fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var index = 0; var lat = 0; var lng = 0
        while (index < encoded.length) {
            var shift = 0; var result = 0; var b: Int
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(lat / 1e5 to lng / 1e5)
        }
        return out
    }
}
