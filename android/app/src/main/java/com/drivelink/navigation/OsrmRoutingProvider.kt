package com.drivelink.navigation

import com.drivelink.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * OSRM HTTP API (`/route/v1/driving/...`).
 *
 * The default endpoint is the public OSRM demo server. Its usage policy limits it to reasonable non-commercial use,
 * at most 1 request per second, with an identifying User-Agent, and gives no uptime guarantee. This class enforces
 * the 1 req/s spacing and sends a User-Agent. For production traffic, self-host OSRM (or use a commercial provider)
 * and set ROUTING_BASE_URL (see docs/MAPS_AND_ROUTING.md).
 */
class OsrmRoutingProvider(
    private val baseUrl: String = AppConfig.routingBaseUrl,
    private val client: OkHttpClient = HttpClients.shared,
    private val minIntervalMs: Long = 1_100L,
) : RoutingProvider {
    private val gate = Mutex()
    private var lastCallAtMs = 0L

    override suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RoutePlan = gate.withLock {
        val wait = minIntervalMs - (System.currentTimeMillis() - lastCallAtMs)
        if (wait > 0) delay(wait)
        lastCallAtMs = System.currentTimeMillis()
        val body = withContext(Dispatchers.IO) { fetch(buildUrl(baseUrl, fromLat, fromLon, toLat, toLon)) }
        parse(body)
    }

    private fun fetch(url: String): String {
        try {
            return client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 429) throw RoutingUnavailableException("The routing server is rate-limiting requests. Try again in a moment.")
                // OSRM answers 400 with {"code":"NoRoute"} for unroutable pairs, so parse() must also see error bodies.
                if (!r.isSuccessful && text.isBlank()) throw RoutingUnavailableException("Routing server error (HTTP ${r.code})")
                text
            }
        } catch (e: IOException) {
            throw RoutingUnavailableException("Cannot reach the routing server. Check your connection and try again.", e)
        }
    }

    companion object {
        fun buildUrl(baseUrl: String, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String =
            "${baseUrl.trimEnd('/')}/route/v1/driving/" +
                String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", fromLon, fromLat, toLon, toLat) +
                "?overview=full&geometries=polyline6&steps=true&alternatives=true"

        /** Pure parser, unit-tested without a network. */
        fun parse(body: String): RoutePlan {
            val o = try { JSONObject(body) } catch (e: Exception) { throw RoutingUnavailableException("Unexpected response from the routing server", e) }
            when (val code = o.optString("code")) {
                "Ok" -> Unit
                "NoRoute", "NoSegment" -> throw NoRouteException()
                else -> throw RoutingUnavailableException("Routing failed: $code ${o.optString("message")}".trim())
            }
            val routes = o.optJSONArray("routes")
            if (routes == null || routes.length() == 0) throw NoRouteException()
            val best = routes.getJSONObject(0)
            val leg = best.getJSONArray("legs").getJSONObject(0)
            val steps = leg.optJSONArray("steps")
            val list = ArrayList<RouteStep>()
            if (steps != null) {
                // OSRM step i describes the maneuver at its START and runs until step i+1's maneuver, which matches
                // RouteStep's "instruction + where this step ends". The final "arrive" step only supplies the end point.
                for (i in 0 until steps.length() - 1) {
                    val s = steps.getJSONObject(i)
                    val next = steps.getJSONObject(i + 1).getJSONObject("maneuver").getJSONArray("location")
                    val m = s.getJSONObject("maneuver")
                    list += RouteStep(
                        instruction(m.optString("type"), m.optString("modifier"), s.optString("name"), m.optInt("exit", 0)),
                        next.getDouble(1), next.getDouble(0), formatDistance(s.optDouble("distance", 0.0)))
                }
            }
            val alternatives = (1 until routes.length()).map {
                NavigationHelper.decodePolyline(routes.getJSONObject(it).getString("geometry"), 1e6)
            }
            return RoutePlan(
                points = NavigationHelper.decodePolyline(best.getString("geometry"), 1e6),
                distanceText = formatDistance(best.optDouble("distance", 0.0)),
                durationText = formatDuration(best.optDouble("duration", 0.0)),
                steps = list,
                alternatives = alternatives,
            )
        }

        fun instruction(type: String, modifier: String, name: String, exit: Int): String {
            val onto = if (name.isNotBlank()) " onto $name" else ""
            val dir = when (modifier) {
                "left" -> "Turn left"
                "right" -> "Turn right"
                "slight left" -> "Bear left"
                "slight right" -> "Bear right"
                "sharp left" -> "Take a sharp left"
                "sharp right" -> "Take a sharp right"
                "uturn" -> "Make a U-turn"
                "straight" -> "Continue straight"
                else -> "Continue"
            }
            return when (type) {
                "depart" -> if (name.isNotBlank()) "Head out on $name" else "Start driving"
                "arrive" -> "Arrive at your destination"
                "roundabout", "rotary", "roundabout turn" -> if (exit > 0) "At the roundabout take exit $exit$onto" else "Enter the roundabout$onto"
                "merge" -> "Merge$onto"
                "on ramp" -> "Take the ramp$onto"
                "off ramp" -> "Take the exit$onto"
                "fork" -> "Keep ${if (modifier.contains("left")) "left" else if (modifier.contains("right")) "right" else "straight"} at the fork$onto"
                else -> dir + onto
            }
        }

        fun formatDistance(meters: Double): String = when {
            meters < 1000 -> "${(Math.round(meters / 10.0) * 10).toInt()} m"
            else -> String.format(Locale.US, "%.1f km", meters / 1000.0)
        }

        fun formatDuration(seconds: Double): String {
            val mins = Math.round(seconds / 60.0).toInt().coerceAtLeast(1)
            return if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min"
        }
    }
}
