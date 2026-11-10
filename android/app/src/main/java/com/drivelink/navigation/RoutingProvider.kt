package com.drivelink.navigation

/** A routing backend. Independent of the map renderer: swap OSRM for Valhalla/GraphHopper/self-hosted without touching the map. */
interface RoutingProvider {
    /** @throws NoRouteException when the provider answers but no drivable route exists. */
    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RoutePlan
}

class NoRouteException(message: String = "No drivable route found to that destination") : Exception(message)
class RoutingUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
