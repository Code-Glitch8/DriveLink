package com.drivelink.map

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

/** Declarative description of a layer this manager owns. [create] must return a NEW Layer each call. */
class LayerSpec(
    val id: String,
    val group: LogicalGroup,
    val sourceId: String,
    /** Insert beneath the base style's first label layer so labels stay readable above the route. */
    val belowLabels: Boolean,
    val create: () -> Layer,
)

data class LayerInfo(
    val id: String, val type: String, val sourceId: String?, val sourceType: String?,
    val visible: Boolean, val index: Int, val owned: Boolean, val group: LogicalGroup,
)

data class SourceInfo(val id: String, val type: String, val featureCount: Int?, val updates: Int, val lastUpdateAgeMs: Long?)

data class MapTreeSnapshot(
    val styleReady: Boolean,
    val layers: List<LayerInfo>,
    val sources: List<SourceInfo>,
)

/**
 * Owns every DriveLink-specific source and layer and maps the base style onto the logical layer tree.
 *
 * Threading: main thread only (MapLibre requirement).
 * Data updates ([updateSource]) never touch layers, so the map is not rebuilt when vehicles move.
 * Structural changes ([addLayer], [removeLayer], [moveLayer]) re-install the owned layers; this is rare and cheap.
 * Everything is cached, so a style reload (theme change, fallback style) restores sources, layers, visibility and data.
 */
class MapLayerManager {
    private class Stats(var updates: Int = 0, var lastMs: Long = 0L, var features: Int = 0)

    private var style: Style? = null
    private val specs = LinkedHashMap<String, LayerSpec>()
    private val order = ArrayList<String>() // bottom -> top
    private val sourceIds = LinkedHashSet<String>()
    private val data = HashMap<String, FeatureCollection>()
    private val stats = HashMap<String, Stats>()
    private val hidden = HashSet<String>()
    private val hiddenGroups = HashSet<LogicalGroup>()
    private val overrides = HashMap<String, Array<PropertyValue<*>>>()

    val isStyleReady: Boolean get() = style != null

    // ------------------------------------------------------------------ lifecycle
    fun onStyleLoaded(s: Style) {
        style = s
        MapImages.install(s)
        sourceIds.forEach { ensureSource(s, it) }
        reinstall(s)
        hidden.forEach { id -> s.getLayer(id)?.let { setVis(it, false) } }
        applyGroupVisibility(s)
    }

    fun onStyleLost() { style = null }

    // ------------------------------------------------------------------ operations
    /** Add (or replace) an owned layer. [index] is its position in the owned stack, 0 = bottom. */
    fun addLayer(spec: LayerSpec, index: Int = order.size) {
        order.remove(spec.id)
        specs[spec.id] = spec
        sourceIds += spec.sourceId
        order.add(index.coerceIn(0, order.size), spec.id)
        style?.let { ensureSource(it, spec.sourceId); reinstall(it) }
    }

    fun removeLayer(id: String) {
        specs.remove(id); order.remove(id); overrides.remove(id); hidden.remove(id)
        style?.removeLayer(id)
    }

    /** Apply paint/layout properties now and after every future style reload. */
    fun updateLayer(id: String, vararg props: PropertyValue<*>) {
        @Suppress("UNCHECKED_CAST")
        overrides[id] = props as Array<PropertyValue<*>>
        style?.getLayer(id)?.setProperties(*props)
    }

    fun setVisibility(id: String, visible: Boolean) {
        if (visible) hidden.remove(id) else hidden.add(id)
        style?.getLayer(id)?.let { setVis(it, visible) }
    }

    fun setGroupVisible(group: LogicalGroup, visible: Boolean) {
        if (visible) hiddenGroups.remove(group) else hiddenGroups.add(group)
        if (group.owned) {
            specs.values.filter { it.group == group }.forEach { setVisibility(it.id, visible) }
        } else {
            style?.let { applyGroupVisibility(it) }
        }
    }

    fun isGroupVisible(group: LogicalGroup): Boolean = group !in hiddenGroups

    /** Move an owned layer directly above [aboveId] (or to the top when null). Returns false for unknown ids. */
    fun moveLayer(id: String, aboveId: String?): Boolean {
        if (id !in specs) return false
        order.remove(id)
        val at = aboveId?.let { order.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: order.size
        order.add(at, id)
        style?.let { reinstall(it) }
        return true
    }

    fun updateSource(sourceId: String, fc: FeatureCollection) {
        data[sourceId] = fc
        val st = stats.getOrPut(sourceId) { Stats() }
        st.updates++; st.lastMs = System.currentTimeMillis(); st.features = fc.features()?.size ?: 0
        style?.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(fc)
    }

    // ------------------------------------------------------------------ DriveLink data (typed convenience)
    private var vehicles: List<MapVehicle> = emptyList()
    private var selectedVehicleId: String? = null
    private var destination: MapDestination? = null

    fun setVehicles(list: List<MapVehicle>, selectedId: String?) {
        vehicles = list; selectedVehicleId = selectedId
        updateSource(MapIds.Source.VEHICLES, MapGeoJson.vehicles(list, selectedId))
        pushSelection()
    }

    fun setRoute(route: MapRoute?) {
        updateSource(MapIds.Source.ROUTE_MAIN, MapGeoJson.routeMain(route))
        updateSource(MapIds.Source.ROUTE_ALT, MapGeoJson.routeAlternatives(route))
    }

    fun setDestination(d: MapDestination?) {
        destination = d
        updateSource(MapIds.Source.DESTINATION, MapGeoJson.destination(d))
        pushSelection()
    }

    fun setHazards(list: List<MapHazard>) = updateSource(MapIds.Source.HAZARDS, MapGeoJson.hazards(list))

    fun setMe(me: MapMe?) = updateSource(MapIds.Source.ME, MapGeoJson.me(me))

    private fun pushSelection() {
        updateSource(MapIds.Source.UI_SELECTION, MapGeoJson.selection(vehicles.firstOrNull { it.id == selectedVehicleId }, destination))
    }

    // ------------------------------------------------------------------ introspection (debug panel)
    fun snapshot(): MapTreeSnapshot {
        val s = style ?: return MapTreeSnapshot(false, emptyList(), emptyList())
        val now = System.currentTimeMillis()
        val layers = s.layers.mapIndexed { i, l ->
            val owned = specs[l.id]
            val srcId = sourceIdOf(l)
            LayerInfo(
                id = l.id, type = l.javaClass.simpleName, sourceId = srcId,
                sourceType = srcId?.let { id -> s.getSource(id)?.javaClass?.simpleName },
                visible = l.visibility.value != Property.NONE, index = i, owned = owned != null,
                group = owned?.group ?: StyleLayerClassifier.classify(l.id, l.javaClass.simpleName, sourceLayerOf(l)),
            )
        }
        val sources = s.sources.map { src ->
            val st = stats[src.id]
            SourceInfo(src.id, src.javaClass.simpleName, st?.features, st?.updates ?: 0, st?.takeIf { it.lastMs > 0 }?.let { now - it.lastMs })
        }
        return MapTreeSnapshot(true, layers, sources)
    }

    // ------------------------------------------------------------------ internals
    private fun ensureSource(s: Style, id: String) {
        if (s.getSource(id) == null) s.addSource(GeoJsonSource(id, data[id] ?: MapGeoJson.EMPTY))
    }

    private fun reinstall(s: Style) {
        order.forEach { s.removeLayer(it) }
        val labelId = s.layers.firstOrNull { it is SymbolLayer }?.id
        for (id in order) {
            val spec = specs[id] ?: continue
            ensureSource(s, spec.sourceId)
            val layer = spec.create()
            overrides[id]?.let { layer.setProperties(*it) }
            if (id in hidden) setVis(layer, false)
            if (spec.belowLabels && labelId != null) s.addLayerBelow(layer, labelId) else s.addLayer(layer)
        }
    }

    private fun applyGroupVisibility(s: Style) {
        if (hiddenGroups.isEmpty()) return
        s.layers.forEach { l ->
            if (l.id in specs) return@forEach
            val g = StyleLayerClassifier.classify(l.id, l.javaClass.simpleName, sourceLayerOf(l))
            if (g in hiddenGroups) setVis(l, false)
        }
    }

    private fun setVis(layer: Layer, visible: Boolean) {
        layer.setProperties(PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE))
    }

    private fun sourceIdOf(l: Layer): String? = when (l) {
        is FillLayer -> l.sourceId
        is LineLayer -> l.sourceId
        is SymbolLayer -> l.sourceId
        is CircleLayer -> l.sourceId
        is FillExtrusionLayer -> l.sourceId
        else -> null
    }

    private fun sourceLayerOf(l: Layer): String = when (l) {
        is FillLayer -> l.sourceLayer
        is LineLayer -> l.sourceLayer
        is SymbolLayer -> l.sourceLayer
        is CircleLayer -> l.sourceLayer
        is FillExtrusionLayer -> l.sourceLayer
        else -> ""
    }.orEmpty()

    // ------------------------------------------------------------------ the default DriveLink layer tree
    /** Registers groups 3-5 of the logical tree (bottom to top). Call once after construction. */
    fun registerDefaultLayerTree() {
        fun c(hex: String) = Color.parseColor(hex)
        val trueExpr = { key: String -> Expression.eq(Expression.get(key), Expression.literal(true)) }

        addLayer(LayerSpec(MapIds.Layer.ROUTE_ALT, LogicalGroup.NAV_ROUTE_ALT, MapIds.Source.ROUTE_ALT, true) {
            LineLayer(MapIds.Layer.ROUTE_ALT, MapIds.Source.ROUTE_ALT).withProperties(
                // Each alternative gets its own colour + a dash so lines stay distinguishable from the solid selected route.
                PropertyFactory.lineColor(Expression.match(Expression.get(MapIds.Prop.IDX), Expression.color(c("#8FA3C4")),
                    Expression.stop(0, Expression.color(c("#8E6CEF"))), Expression.stop(1, Expression.color(c("#14B8A6"))), Expression.stop(2, Expression.color(c("#F59E0B"))))),
                PropertyFactory.lineWidth(6f), PropertyFactory.lineOpacity(0.85f), PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
        })
        addLayer(LayerSpec(MapIds.Layer.ROUTE_CASING, LogicalGroup.NAV_ROUTE_CASING, MapIds.Source.ROUTE_MAIN, true) {
            LineLayer(MapIds.Layer.ROUTE_CASING, MapIds.Source.ROUTE_MAIN).withProperties(
                PropertyFactory.lineColor(c("#0B2A6B")), PropertyFactory.lineWidth(11f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
        })
        addLayer(LayerSpec(MapIds.Layer.ROUTE_MAIN, LogicalGroup.NAV_ROUTE_MAIN, MapIds.Source.ROUTE_MAIN, true) {
            LineLayer(MapIds.Layer.ROUTE_MAIN, MapIds.Source.ROUTE_MAIN).withProperties(
                PropertyFactory.lineColor(c("#3B82F6")), PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
        })
        addLayer(LayerSpec(MapIds.Layer.DESTINATION, LogicalGroup.NAV_DESTINATION, MapIds.Source.DESTINATION, false) {
            CircleLayer(MapIds.Layer.DESTINATION, MapIds.Source.DESTINATION).withProperties(
                PropertyFactory.circleColor(c("#E5392B")), PropertyFactory.circleRadius(9f),
                PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(3f))
        })
        // Shared hazards sit above routes and below my position and vehicles. A red ring marks high-severity ones.
        addLayer(LayerSpec(MapIds.Layer.HAZARD_RING, LogicalGroup.HAZARDS, MapIds.Source.HAZARDS, false) {
            CircleLayer(MapIds.Layer.HAZARD_RING, MapIds.Source.HAZARDS).withProperties(
                PropertyFactory.circleColor(Color.TRANSPARENT), PropertyFactory.circleRadius(26f),
                PropertyFactory.circleStrokeColor(c("#E5392B")), PropertyFactory.circleStrokeWidth(2.5f), PropertyFactory.circleStrokeOpacity(0.8f)
            ).also { it.setFilter(trueExpr(MapIds.Prop.SEVERE)) }
        })
        addLayer(LayerSpec(MapIds.Layer.HAZARD_ICON, LogicalGroup.HAZARDS, MapIds.Source.HAZARDS, false) {
            SymbolLayer(MapIds.Layer.HAZARD_ICON, MapIds.Source.HAZARDS).withProperties(
                PropertyFactory.iconImage(Expression.get(MapIds.Prop.ICON)),
                PropertyFactory.iconSize(0.8f),
                PropertyFactory.iconOpacity(Expression.get(MapIds.Prop.OPACITY)),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true))
        })
        addLayer(LayerSpec(MapIds.Layer.ME_HALO, LogicalGroup.NAV_CURRENT_LOCATION, MapIds.Source.ME, false) {
            CircleLayer(MapIds.Layer.ME_HALO, MapIds.Source.ME).withProperties(
                PropertyFactory.circleColor(c("#4C8DFF")), PropertyFactory.circleRadius(18f), PropertyFactory.circleOpacity(0.22f))
        })
        addLayer(LayerSpec(MapIds.Layer.ME, LogicalGroup.NAV_CURRENT_LOCATION, MapIds.Source.ME, false) {
            CircleLayer(MapIds.Layer.ME, MapIds.Source.ME).withProperties(
                PropertyFactory.circleColor(c("#4C8DFF")), PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(3f))
        })
        addLayer(LayerSpec(MapIds.Layer.ME_ARROW, LogicalGroup.NAV_CURRENT_LOCATION, MapIds.Source.ME, false) {
            SymbolLayer(MapIds.Layer.ME_ARROW, MapIds.Source.ME).withProperties(
                PropertyFactory.iconImage(MapIds.Image.ARROW_SELECTED),
                PropertyFactory.iconRotate(Expression.get(MapIds.Prop.HEADING)),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), // true bearing: stays geographic when the camera rotates
                PropertyFactory.iconSize(1.25f),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)
            ).also { it.setFilter(trueExpr(MapIds.Prop.HAS_HEADING)) }
        })
        // 4. Vehicle communication: halos/rings underneath, direction arrows on top.
        addLayer(LayerSpec(MapIds.Layer.VEH_EMERGENCY_HALO, LogicalGroup.VEH_EMERGENCY, MapIds.Source.VEHICLES, false) {
            CircleLayer(MapIds.Layer.VEH_EMERGENCY_HALO, MapIds.Source.VEHICLES).withProperties(
                PropertyFactory.circleColor(c("#B3001B")), PropertyFactory.circleRadius(30f), PropertyFactory.circleOpacity(0.35f),
                PropertyFactory.circleStrokeColor(c("#FF3B50")), PropertyFactory.circleStrokeWidth(3f)
            ).also { it.setFilter(trueExpr(MapIds.Prop.EMERGENCY)) }
        })
        addLayer(LayerSpec(MapIds.Layer.VEH_APPROACHING, LogicalGroup.VEH_APPROACHING, MapIds.Source.VEHICLES, false) {
            CircleLayer(MapIds.Layer.VEH_APPROACHING, MapIds.Source.VEHICLES).withProperties(
                PropertyFactory.circleColor(c("#D99100")), PropertyFactory.circleRadius(22f), PropertyFactory.circleOpacity(0.18f),
                PropertyFactory.circleStrokeColor(c("#D99100")), PropertyFactory.circleStrokeWidth(2f)
            ).also { it.setFilter(trueExpr(MapIds.Prop.APPROACHING)) }
        })
        addLayer(LayerSpec(MapIds.Layer.VEH_MERGE, LogicalGroup.VEH_MERGE, MapIds.Source.VEHICLES, false) {
            CircleLayer(MapIds.Layer.VEH_MERGE, MapIds.Source.VEHICLES).withProperties(
                PropertyFactory.circleColor(Color.TRANSPARENT), PropertyFactory.circleRadius(27f),
                PropertyFactory.circleStrokeColor(c("#FFC107")), PropertyFactory.circleStrokeWidth(4f)
            ).also { it.setFilter(trueExpr(MapIds.Prop.MERGE)) }
        })
        addLayer(LayerSpec(MapIds.Layer.VEH_DIRECTION, LogicalGroup.VEH_DIRECTION, MapIds.Source.VEHICLES, false) {
            SymbolLayer(MapIds.Layer.VEH_DIRECTION, MapIds.Source.VEHICLES).withProperties(
                PropertyFactory.iconImage(Expression.get(MapIds.Prop.ICON)),
                PropertyFactory.iconRotate(Expression.get(MapIds.Prop.HEADING)),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconSize(Expression.get(MapIds.Prop.SCALE)),
                PropertyFactory.iconOpacity(Expression.get(MapIds.Prop.OPACITY)),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true))
        })
        // 5. User interface overlays above everything else.
        addLayer(LayerSpec(MapIds.Layer.UI_SELECTED_VEHICLE, LogicalGroup.UI_SELECTED_VEHICLE, MapIds.Source.UI_SELECTION, false) {
            CircleLayer(MapIds.Layer.UI_SELECTED_VEHICLE, MapIds.Source.UI_SELECTION).withProperties(
                PropertyFactory.circleColor(Color.TRANSPARENT), PropertyFactory.circleRadius(34f),
                PropertyFactory.circleStrokeColor(c("#4C8DFF")), PropertyFactory.circleStrokeWidth(4f)
            ).also { it.setFilter(Expression.eq(Expression.get(MapIds.Prop.KIND), Expression.literal("vehicle"))) }
        })
        addLayer(LayerSpec(MapIds.Layer.UI_SELECTED_DESTINATION, LogicalGroup.UI_SELECTED_DESTINATION, MapIds.Source.UI_SELECTION, false) {
            CircleLayer(MapIds.Layer.UI_SELECTED_DESTINATION, MapIds.Source.UI_SELECTION).withProperties(
                PropertyFactory.circleColor(Color.TRANSPARENT), PropertyFactory.circleRadius(16f),
                PropertyFactory.circleStrokeColor(c("#E5392B")), PropertyFactory.circleStrokeWidth(3f)
            ).also { it.setFilter(Expression.eq(Expression.get(MapIds.Prop.KIND), Expression.literal("destination"))) }
        })
    }
}
