package com.drivelink.map

/** Style helpers. */
object MapStyles {
    /**
     * Minimal style with only a background layer. Used when no tile style can be loaded (e.g. offline) so that
     * vehicle, route and location layers, which do not depend on base tiles, keep working.
     */
    fun offlineJson(dark: Boolean): String {
        val bg = if (dark) "#0f1520" else "#e9eef5"
        return """{"version":8,"name":"DriveLink offline","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"$bg"}}]}"""
    }

    const val ATTRIBUTION = "© OpenStreetMap contributors · OpenFreeMap · © OpenMapTiles"
}
