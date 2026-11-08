package com.drivelink.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivelink.BuildConfig
import kotlinx.coroutines.delay

/** True only in debug builds. Release builds never show the panel or its toggle. */
val MAP_DEBUG_ENABLED: Boolean get() = BuildConfig.DEBUG

/**
 * Developer-only logical layer tree. Shows real state read from the live MapLibre style: nothing is simulated.
 * Refreshes once per second while visible.
 */
@Composable
fun MapDebugPanel(handle: MapHandle, modifier: Modifier = Modifier) {
    if (!MAP_DEBUG_ENABLED) return
    var snap by remember { mutableStateOf<MapTreeSnapshot?>(null) }
    var expanded by remember { mutableStateOf(setOf<LogicalGroup>()) }
    var tick by remember { mutableStateOf(0) } // forces a re-read of toggle state right after a switch is flipped
    LaunchedEffect(handle, tick) {
        while (true) { snap = handle.layers?.snapshot(); delay(1000) }
    }
    val mono = FontFamily.Monospace
    Column(
        modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), RoundedCornerShape(16.dp))
            .heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("DEBUG · map layer tree", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        val s = snap
        if (s == null || !s.styleReady) { Text("Style not loaded", fontSize = 12.sp); return@Column }
        val vehicles = s.sources.firstOrNull { it.id == MapIds.Source.VEHICLES }
        val route = s.sources.firstOrNull { it.id == MapIds.Source.ROUTE_MAIN }
        Text("Vehicle source: ${vehicles?.featureCount ?: 0} features, ${vehicles?.updates ?: 0} updates, last ${vehicles?.lastUpdateAgeMs?.let { "${it} ms ago" } ?: "never"}", fontSize = 11.sp, fontFamily = mono)
        Text("Route source: ${if ((route?.featureCount ?: 0) > 0) "present" else "empty"} (${route?.updates ?: 0} updates)", fontSize = 11.sp, fontFamily = mono)
        Text("Style layers: ${s.layers.size} (owned: ${s.layers.count { it.owned }}), sources: ${s.sources.size}", fontSize = 11.sp, fontFamily = mono)

        LogicalGroup.entries.forEach { g ->
            val inGroup = s.layers.filter { it.group == g }
            if (inGroup.isEmpty()) return@forEach
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(g.path + "  (${inGroup.size})", fontSize = 11.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                Switch(handle.layers?.isGroupVisible(g) ?: true, { on -> handle.layers?.setGroupVisible(g, on); tick++ })
            }
            if (g.owned || g in expanded) {
                inGroup.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("#${l.index} ${l.id}\n${l.type} · src=${l.sourceId ?: "-"} (${l.sourceType ?: "-"})", fontSize = 10.sp, fontFamily = mono,
                            modifier = Modifier.weight(1f))
                        if (l.owned) Switch(l.visible, { on -> handle.layers?.setVisibility(l.id, on); tick++ })
                    }
                }
            }
            if (!g.owned) Text(if (g in expanded) "  ▾ hide layer ids" else "  ▸ show layer ids", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickableToggle { expanded = if (g in expanded) expanded - g else expanded + g })
        }
        Text("Sources", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
        s.sources.forEach { src ->
            Text("${src.id} · ${src.type}" + (src.featureCount?.let { " · $it features · ${src.updates} updates" } ?: ""), fontSize = 10.sp, fontFamily = mono)
        }
    }
}

private fun Modifier.clickableToggle(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))
