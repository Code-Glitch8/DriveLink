package com.drivelink.navigation

import com.drivelink.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException

/**
 * Place search fallback via Nominatim (OpenStreetMap data), used only when the on-device Geocoder is missing or empty.
 * The public server's policy: max 1 request/second, identifying User-Agent, no search-as-you-type, cache results.
 * DriveLink only searches when the user presses Go, and spaces calls 1.1 s apart.
 */
class NominatimGeocoder(
    private val baseUrl: String = AppConfig.geocoderBaseUrl,
    private val client: OkHttpClient = HttpClients.shared,
    private val minIntervalMs: Long = 1_100L,
) {
    private val gate = Mutex()
    private var lastCallAtMs = 0L

    suspend fun search(query: String, limit: Int = 5): List<Place> = gate.withLock {
        val wait = minIntervalMs - (System.currentTimeMillis() - lastCallAtMs)
        if (wait > 0) delay(wait)
        lastCallAtMs = System.currentTimeMillis()
        val url = "${baseUrl.trimEnd('/')}/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("format", "jsonv2").addQueryParameter("limit", limit.toString()).build()
        val body = withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.code == 429) throw RoutingUnavailableException("The place-search server is rate-limiting requests. Try again in a moment.")
                    if (!r.isSuccessful) throw RoutingUnavailableException("Place search failed (HTTP ${r.code})")
                    r.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                throw RoutingUnavailableException("Cannot reach the place-search server. Check your connection.", e)
            }
        }
        parse(body)
    }

    companion object {
        fun parse(body: String): List<Place> {
            val arr = try { JSONArray(body) } catch (_: Exception) { return emptyList() }
            return (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                Place(o.optString("display_name").ifBlank { "Selected place" }, lat, lon)
            }
        }
    }
}
