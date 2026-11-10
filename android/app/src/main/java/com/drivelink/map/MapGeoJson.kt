package com.drivelink.map

import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Pure data -> GeoJSON converters. No styling decisions beyond picking an icon / opacity / scale per feature. */
object MapGeoJson {
    val EMPTY: FeatureCollection = FeatureCollection.fromFeatures(ArrayList<Feature>())

    private fun point(lat: Double, lon: Double): Feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))

    fun vehicles(vehicles: List<MapVehicle>, selectedId: String?): FeatureCollection {
        val features = vehicles.map { v ->
            point(v.lat, v.lon).also { f ->
                f.addStringProperty(MapIds.Prop.ID, v.id)
                f.addNumberProperty(MapIds.Prop.HEADING, v.headingDeg)
                f.addStringProperty(MapIds.Prop.ICON, when {
                    v.id == selectedId -> MapIds.Image.ARROW_SELECTED
                    v.emergencyType != null -> MapIds.Image.ARROW_EMERGENCY
                    v.conflict -> MapIds.Image.ARROW_HIGH
                    v.approaching -> MapIds.Image.ARROW_CAUTION
                    else -> MapIds.Image.ARROW_NORMAL
                })
                f.addNumberProperty(MapIds.Prop.SCALE, when (v.type) {
                    "motorcycle", "scooter" -> 0.8
                    "bus", "truck" -> 1.25
                    else -> 1.0
                })
                f.addNumberProperty(MapIds.Prop.OPACITY, if (v.status == CommStatus.STALE) 0.5 else 1.0)
                f.addBooleanProperty(MapIds.Prop.EMERGENCY, v.emergencyType != null)
                f.addBooleanProperty(MapIds.Prop.APPROACHING, v.approaching)
                f.addBooleanProperty(MapIds.Prop.MERGE, v.mergeRequest != null)
                v.mergeRequest?.let { f.addStringProperty("mergeDir", it) }
                f.addStringProperty("vtype", v.type)
            }
        }
        return FeatureCollection.fromFeatures(features)
    }

    fun line(points: List<Pair<Double, Double>>): Feature? {
        if (points.size < 2) return null
        return Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) }))
    }

    fun routeMain(route: MapRoute?): FeatureCollection =
        line(route?.main.orEmpty())?.let { FeatureCollection.fromFeature(it) } ?: EMPTY

    fun routeAlternatives(route: MapRoute?): FeatureCollection =
        FeatureCollection.fromFeatures(route?.alternatives.orEmpty().mapIndexedNotNull { i, pts ->
            line(pts)?.also { it.addNumberProperty(MapIds.Prop.IDX, i) } // index drives the per-alternative colour
        })

    fun destination(d: MapDestination?): FeatureCollection =
        d?.let { FeatureCollection.fromFeature(point(it.lat, it.lon)) } ?: EMPTY

    fun hazards(list: List<MapHazard>): FeatureCollection = FeatureCollection.fromFeatures(list.map { h ->
        point(h.lat, h.lon).also { f ->
            f.addStringProperty(MapIds.Prop.ID, h.id)
            f.addStringProperty(MapIds.Prop.ICON, MapIds.Image.hazard(h.categoryKey))
            f.addBooleanProperty(MapIds.Prop.SEVERE, h.severe)
            f.addNumberProperty(MapIds.Prop.OPACITY, if (h.faded) 0.6 else 1.0)
        }
    })

    fun me(me: MapMe?): FeatureCollection =
        me?.let {
            val f = point(it.lat, it.lon)
            // Heading is only attached when known, so the arrow layer (filtered on hasHeading) never shows an invented direction.
            if (it.headingDeg != null) { f.addNumberProperty(MapIds.Prop.HEADING, it.headingDeg); f.addBooleanProperty(MapIds.Prop.HAS_HEADING, true) }
            else f.addBooleanProperty(MapIds.Prop.HAS_HEADING, false)
            FeatureCollection.fromFeature(f)
        } ?: EMPTY

    /** Selected vehicle and selected destination overlays share one source, told apart by a `kind` property. */
    fun selection(vehicle: MapVehicle?, destination: MapDestination?): FeatureCollection {
        val list = ArrayList<Feature>()
        vehicle?.let { list += point(it.lat, it.lon).also { f -> f.addStringProperty(MapIds.Prop.KIND, "vehicle") } }
        destination?.let { list += point(it.lat, it.lon).also { f -> f.addStringProperty(MapIds.Prop.KIND, "destination") } }
        return FeatureCollection.fromFeatures(list)
    }
}
