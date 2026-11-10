package com.drivelink.search

import com.drivelink.config.AppConfig
import com.drivelink.navigation.HttpClients
import com.drivelink.navigation.Place
import com.drivelink.navigation.PlaceCategory
import com.drivelink.navigation.RoutingUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Photon (https://photon.komoot.io) — an OpenStreetMap geocoder built for search-as-you-type, with location bias.
 * No API key. The public server asks for "reasonable" use: DriveLink debounces typing, requires 3+ characters,
 * cancels superseded requests, caches results and sends an identifying User-Agent (see [SmartSearch]).
 * For heavy production traffic point PHOTON_BASE_URL at a self-hosted instance.
 */
class PhotonGeocoder(
    private val baseUrl: String = AppConfig.photonBaseUrl,
    private val client: OkHttpClient = HttpClients.shared,
) {
    suspend fun search(query: String, biasLat: Double?, biasLon: Double?, limit: Int = 8): List<Place> {
        val body = withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(buildUrl(baseUrl, query, biasLat, biasLon, limit)).build()).execute().use { r ->
                    if (r.code == 429) throw RoutingUnavailableException("The place-search server is busy. Try again in a moment.")
                    if (!r.isSuccessful) throw RoutingUnavailableException("Place search failed (HTTP ${r.code})")
                    r.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                throw RoutingUnavailableException("Cannot reach the place-search server. Check your connection.", e)
            }
        }
        return parse(body)
    }

    companion object {
        fun buildUrl(baseUrl: String, query: String, biasLat: Double?, biasLon: Double?, limit: Int): String {
            val b = "${baseUrl.trimEnd('/')}/api/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query.trim()).addQueryParameter("limit", limit.coerceIn(1, 15).toString())
            // Photon boosts results near lat/lon, which is what makes "Central Mall" resolve to the one near the driver.
            if (biasLat != null && biasLon != null) b.addQueryParameter("lat", "%.4f".format(java.util.Locale.US, biasLat)).addQueryParameter("lon", "%.4f".format(java.util.Locale.US, biasLon))
            return b.build().toString()
        }

        /** Pure parser (GeoJSON FeatureCollection). Skips malformed features instead of failing the whole result. */
        fun parse(body: String): List<Place> {
            val features = try { JSONObject(body).optJSONArray("features") } catch (_: Exception) { null } ?: return emptyList()
            val out = ArrayList<Place>()
            for (i in 0 until features.length()) {
                val f = features.optJSONObject(i) ?: continue
                val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                if (coords.length() < 2) continue
                val lon = (coords.opt(0) as? Number)?.toDouble() ?: continue
                val lat = (coords.opt(1) as? Number)?.toDouble() ?: continue
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                val p = f.optJSONObject("properties") ?: continue
                val street = p.optString("street").takeIf { it.isNotBlank() }
                val house = p.optString("housenumber").takeIf { it.isNotBlank() }
                val name = p.optString("name").takeIf { it.isNotBlank() }
                    ?: street?.let { s -> if (house != null) "$house $s" else s }
                    ?: continue
                val category = category(p.optString("osm_key"), p.optString("osm_value"), p.optString("type"), p.optString("name").isBlank())
                out += Place(name, lat, lon, subtitle(p, street, house, name), category)
            }
            return out
        }

        /** "Street 12 · District, City, State" without repeating the title. */
        internal fun subtitle(p: JSONObject, street: String?, house: String?, title: String): String? {
            val parts = ArrayList<String>()
            val streetLine = street?.let { s -> if (house != null) "$s $house" else s }
            if (streetLine != null && !streetLine.equals(title, true) && !title.contains(streetLine, true)) parts += streetLine
            for (k in listOf("district", "city", "county", "state")) {
                val v = p.optString(k).takeIf { it.isNotBlank() } ?: continue
                if (v.equals(title, true) || parts.any { it.equals(v, true) }) continue
                parts += v
                if (parts.size >= 3) break
            }
            if (parts.size < 2) p.optString("country").takeIf { it.isNotBlank() && !it.equals(title, true) }?.let { parts += it }
            return parts.joinToString(", ").ifBlank { null }
        }

        internal fun category(osmKey: String, osmValue: String, type: String, isAddress: Boolean): PlaceCategory = when {
            osmKey == "place" || type in setOf("city", "district", "locality", "county", "state", "country") -> PlaceCategory.CITY
            osmKey == "highway" && osmValue in setOf("bus_stop", "platform") -> PlaceCategory.TRANSPORT
            osmKey == "railway" || osmKey == "aeroway" -> PlaceCategory.TRANSPORT
            osmKey == "amenity" && osmValue in setOf("restaurant", "cafe", "fast_food", "bar", "pub", "food_court", "ice_cream") -> PlaceCategory.FOOD
            osmKey == "amenity" && osmValue in setOf("fuel", "charging_station") -> PlaceCategory.FUEL
            osmKey == "amenity" && osmValue in setOf("hospital", "clinic", "doctors", "pharmacy", "dentist") -> PlaceCategory.HEALTH
            osmKey == "amenity" && osmValue in setOf("school", "university", "college", "kindergarten", "library") -> PlaceCategory.EDUCATION
            osmKey == "amenity" && osmValue in setOf("bus_station", "taxi", "parking") -> PlaceCategory.TRANSPORT
            osmKey == "shop" -> PlaceCategory.SHOP
            osmKey == "tourism" && osmValue in setOf("hotel", "hostel", "guest_house", "motel", "apartment") -> PlaceCategory.LODGING
            osmKey == "tourism" || osmKey == "historic" || osmKey == "man_made" -> PlaceCategory.LANDMARK
            osmKey == "leisure" || osmKey == "natural" -> PlaceCategory.PARK
            osmKey == "building" || osmKey == "highway" || isAddress -> PlaceCategory.ADDRESS
            else -> PlaceCategory.OTHER
        }
    }
}
