package com.drivelink.map

/**
 * Logical DriveLink map tree. Groups 1 and 2 are classified from the base style's layers (OpenFreeMap / OpenMapTiles
 * schema); groups 3, 4 and 5 are layers owned and managed by [MapLayerManager].
 */
enum class LogicalGroup(val path: String, val owned: Boolean) {
    BASE_BACKGROUND("1. Base map / Background", false),
    BASE_LAND("1. Base map / Land", false),
    BASE_WATER("1. Base map / Water", false),
    BASE_PARKS("1. Base map / Parks and green areas", false),
    BASE_BUILDINGS("1. Base map / Buildings", false),
    ROAD_MOTORWAY("2. Road network / Motorways", false),
    ROAD_PRIMARY("2. Road network / Primary roads", false),
    ROAD_SECONDARY("2. Road network / Secondary roads", false),
    ROAD_RESIDENTIAL("2. Road network / Residential and other roads", false),
    ROAD_LABELS("2. Road network / Road labels", false),
    BASE_OTHER("Other style layers (places, POIs, boundaries, ...)", false),
    NAV_ROUTE_ALT("3. Navigation / Alternative routes", true),
    NAV_ROUTE_CASING("3. Navigation / Route casing", true),
    NAV_ROUTE_MAIN("3. Navigation / Main route", true),
    NAV_DESTINATION("3. Navigation / Destination marker", true),
    NAV_CURRENT_LOCATION("3. Navigation / Current location marker", true),
    VEH_NEARBY("4. Vehicle communication / Nearby vehicles", true),
    VEH_DIRECTION("4. Vehicle communication / Vehicle direction indicators", true),
    VEH_APPROACHING("4. Vehicle communication / Approaching vehicle indicators", true),
    VEH_MERGE("4. Vehicle communication / Lane merge request indicators", true),
    VEH_EMERGENCY("4. Vehicle communication / Emergency vehicle indicators", true),
    HAZARDS("3. Navigation / Shared hazards", true),
    UI_SELECTED_VEHICLE("5. User interface / Selected vehicle", true),
    UI_SELECTED_DESTINATION("5. User interface / Selected destination", true),
}

/** Centralised identifiers. Never hard-code these strings elsewhere. */
object MapIds {
    object Source {
        const val ROUTE_MAIN = "dl-src-route-main"
        const val ROUTE_ALT = "dl-src-route-alt"
        const val DESTINATION = "dl-src-destination"
        const val ME = "dl-src-me"
        const val VEHICLES = "dl-src-vehicles"
        const val UI_SELECTION = "dl-src-ui-selection"
        const val HAZARDS = "dl-src-hazards"
    }

    object Layer {
        const val ROUTE_ALT = "dl-nav-route-alt"
        const val ROUTE_CASING = "dl-nav-route-casing"
        const val ROUTE_MAIN = "dl-nav-route-main"
        const val DESTINATION = "dl-nav-destination"
        const val HAZARD_ICON = "dl-nav-hazard-icon"
        const val HAZARD_RING = "dl-nav-hazard-ring"
        const val ME_HALO = "dl-nav-me-halo"
        const val ME = "dl-nav-me"
        const val ME_ARROW = "dl-nav-me-arrow"
        const val VEH_EMERGENCY_HALO = "dl-veh-emergency-halo"
        const val VEH_APPROACHING = "dl-veh-approaching"
        const val VEH_MERGE = "dl-veh-merge"
        const val VEH_DIRECTION = "dl-veh-direction"
        const val UI_SELECTED_VEHICLE = "dl-ui-selected-vehicle"
        const val UI_SELECTED_DESTINATION = "dl-ui-selected-destination"
    }

    object Image {
        const val ARROW_NORMAL = "dl-arrow-normal"
        const val ARROW_CAUTION = "dl-arrow-caution"
        const val ARROW_HIGH = "dl-arrow-high"
        const val ARROW_EMERGENCY = "dl-arrow-emergency"
        const val ARROW_SELECTED = "dl-arrow-selected"
        fun hazard(categoryKey: String) = "dl-hazard-$categoryKey"
    }

    /** Feature property names inside the vehicle GeoJSON source. */
    object Prop {
        const val ID = "id"
        const val HEADING = "heading"
        const val ICON = "icon"
        const val SCALE = "scale"
        const val OPACITY = "opacity"
        const val EMERGENCY = "emergency"
        const val APPROACHING = "approaching"
        const val MERGE = "merge"
        const val KIND = "kind"
        const val HAS_HEADING = "hasHeading"
        const val IDX = "idx"
        const val SEVERE = "severe"
    }
}

/** Pure string-based classifier so it can be unit-tested without the MapLibre runtime. */
object StyleLayerClassifier {
    fun classify(id: String, type: String, sourceLayer: String): LogicalGroup {
        val i = id.lowercase()
        val sl = sourceLayer.lowercase()
        val t = type.lowercase()
        return when {
            t.contains("background") -> LogicalGroup.BASE_BACKGROUND
            sl == "water" || sl == "waterway" || sl == "water_name" || (sl.isEmpty() && i.startsWith("water")) -> LogicalGroup.BASE_WATER
            sl == "park" || sl == "landcover" && listOf("wood", "forest", "grass", "park", "scrub", "wetland").any { i.contains(it) } -> LogicalGroup.BASE_PARKS
            sl == "landuse" || sl == "landcover" -> LogicalGroup.BASE_LAND
            sl == "building" || t.contains("extrusion") -> LogicalGroup.BASE_BUILDINGS
            sl == "transportation_name" -> LogicalGroup.ROAD_LABELS
            sl == "transportation" -> when {
                i.contains("motorway") || i.contains("trunk") -> LogicalGroup.ROAD_MOTORWAY
                i.contains("primary") -> LogicalGroup.ROAD_PRIMARY
                i.contains("secondary") || i.contains("tertiary") -> LogicalGroup.ROAD_SECONDARY
                i.contains("rail") || i.contains("ferry") || i.contains("aerialway") -> LogicalGroup.BASE_OTHER
                else -> LogicalGroup.ROAD_RESIDENTIAL
            }
            else -> LogicalGroup.BASE_OTHER
        }
    }
}
