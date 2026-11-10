package com.drivelink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivelink.hazards.HazardGeometry
import com.drivelink.navigation.Place
import com.drivelink.navigation.RouteAdvice
import com.drivelink.navigation.RouteAssessment
import com.drivelink.navigation.RouteOption
import com.drivelink.search.SearchHit
import com.drivelink.search.SearchState

/**
 * Destination search with live suggestions. Typing is only offered on the pre-drive Home screen; the driving
 * dashboard has no text input at all.
 */
@Composable
fun SmartSearchBar(
    query: String, onQuery: (String) -> Unit, onSubmit: () -> Unit, state: SearchState,
    onPick: (Place) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier,
) {
    AppCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            OutlinedTextField(
                query, onQuery, placeholder = { Text("Search places, landmarks, addresses") }, singleLine = true,
                shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                trailingIcon = { if (query.isNotEmpty()) TextButton({ onQuery("") }) { Text("✕", fontSize = 16.sp) } },
            )
            when (state) {
                SearchState.Idle -> Unit
                SearchState.TooShort -> Hint("Type at least 3 characters")
                SearchState.Loading -> Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("Searching…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SearchState.Empty -> Hint("No places found. Try a nearby landmark, street or city")
                is SearchState.Error -> Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.message, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.error); TextButton(onRetry) { Text("Retry") }
                }
                is SearchState.Results -> {
                    state.hits.forEach { h -> SuggestionRow(h) { onPick(h.place) } }
                    if (state.fromFallback) Hint("Showing basic results (autocomplete unavailable)")
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SuggestionRow(h: SearchHit, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(h.place.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // For ambiguous names the locality is the whole point, so it is always shown.
                val sub = h.place.subtitle ?: h.place.category.label
                Text(sub, fontSize = 12.sp, color = if (h.ambiguous) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                h.distanceM?.let { Text(HazardGeometry.distanceText(it), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(h.place.category.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    }
}

/** Horizontal list of candidate routes. Each card: kind, time, distance, major roads and hazard count. */
@Composable
fun RouteOptionCards(
    options: List<RouteOption>, selected: Int, assessments: List<RouteAssessment>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    val altColors = listOf(Color(0xFF3B82F6), Color(0xFF8E6CEF), Color(0xFF14B8A6), Color(0xFFF59E0B))
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            val hz = assessments.getOrNull(i)
            val accent = if (sel) altColors[0] else altColors[(i % (altColors.size - 1)) + 1]
            Column(
                Modifier.width(168.dp).clickable { onSelect(i) }
                    .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                    .border(if (sel) 2.dp else 1.dp, accent, RoundedCornerShape(16.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(o.kind.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent)
                Text(o.durationText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(o.distanceText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (o.roads.isNotEmpty()) Text("via " + o.roads.joinToString(", "), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hz != null && hz.hazards.isNotEmpty())
                    Text("⚠ ${hz.hazards.size} reported hazard${if (hz.hazards.size > 1) "s" else ""} on route", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFD97706))
                else Text("No reported hazards", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Warning for the selected route plus the optional "switch" suggestion with time/distance deltas. */
@Composable
fun RouteAdviceCard(advice: RouteAdvice, onSwitch: (Int) -> Unit, modifier: Modifier = Modifier) {
    val warning = advice.warning ?: return
    Column(modifier.fillMaxWidth().background(Color(0xFFFFF3E0).copy(alpha = if (MaterialTheme.colorScheme.background.red < 0.5f) 0.15f else 1f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("⚠ $warning", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Reports come from other drivers and may be out of date.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        advice.suggestion?.let { s ->
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s, Modifier.weight(1f), fontSize = 13.sp)
                advice.suggestedOptionId?.let { id -> TextButton({ onSwitch(id) }) { Text("Switch") } }
            }
        }
    }
}
