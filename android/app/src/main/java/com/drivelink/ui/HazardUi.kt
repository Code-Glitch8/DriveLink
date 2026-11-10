package com.drivelink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivelink.hazards.Hazard
import com.drivelink.hazards.HazardCategory
import com.drivelink.hazards.HazardConfidence
import com.drivelink.hazards.HazardGeometry

/** Same colours as the map badges so a category looks the same in the list and on the map. */
fun hazardColor(c: HazardCategory): Color = when (c) {
    HazardCategory.ACCIDENT -> Color(0xFFD32F2F)
    HazardCategory.ROAD_BLOCKAGE -> Color(0xFFC62828)
    HazardCategory.CONSTRUCTION -> Color(0xFFEF6C00)
    HazardCategory.POTHOLE -> Color(0xFFF9A825)
    HazardCategory.VEHICLE_BREAKDOWN -> Color(0xFF1565C0)
    HazardCategory.TRAFFIC_CONGESTION -> Color(0xFFB26A00)
    HazardCategory.ROAD_CLOSURE -> Color(0xFF8E0000)
    HazardCategory.OTHER_DANGER -> Color(0xFF6A1B9A)
}

private fun hazardGlyph(c: HazardCategory) = when (c) {
    HazardCategory.ACCIDENT -> "!"
    HazardCategory.ROAD_BLOCKAGE -> "—"
    HazardCategory.CONSTRUCTION -> "⛏"
    HazardCategory.POTHOLE -> "●"
    HazardCategory.VEHICLE_BREAKDOWN -> "🚗"
    HazardCategory.TRAFFIC_CONGESTION -> "≡"
    HazardCategory.ROAD_CLOSURE -> "✕"
    HazardCategory.OTHER_DANGER -> "!"
}

@Composable
fun HazardBadge(c: HazardCategory, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(hazardColor(c)), contentAlignment = Alignment.Center) {
        Text(hazardGlyph(c), color = Color.White, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * One-tap report: big targets, no typing. Reports the current GPS position. Available while driving on purpose
 * (a passenger or a quick glance), but nothing here needs text input.
 */
@Composable
fun HazardReportDialog(onPick: (HazardCategory) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(24.dp), title = { Text("Report a hazard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reported at your current position and visible to nearby drivers.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HazardCategory.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c ->
                            Row(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onPick(c) }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HazardBadge(c, 30.dp)
                                Text(c.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** Full details. Callers only show this while parked / not driving; the voting buttons are the "safe interaction". */
@Composable
fun HazardDetailDialog(h: Hazard, distanceM: Double?, nowMs: Long, onConfirm: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val confidence = when (h.confidence) {
        HazardConfidence.VERIFIED -> "Verified"
        HazardConfidence.HIGH -> "Reported by ${h.confirmations} drivers"
        HazardConfidence.MEDIUM -> "Reported by ${h.confirmations} drivers"
        HazardConfidence.LOW -> "Reported by 1 driver — unconfirmed"
        HazardConfidence.DISPUTED -> "Disputed — some drivers say it is gone"
    }
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(24.dp),
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { HazardBadge(h.category); Text(h.category.label) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                h.description?.let { Text(it) }
                Text("Reported ${HazardGeometry.ageText(nowMs, h.createdAtMs)}" + (distanceM?.let { " · ${HazardGeometry.distanceText(it)} away" } ?: ""), fontSize = 14.sp)
                Text(confidence, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (h.clearVotes > 0) Text("${h.clearVotes} reported cleared", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Auto-expires ${HazardGeometry.remainingText(nowMs, h.expiresAtMs).removePrefix("in ")} unless confirmed", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    FilledTonalButton(onConfirm, Modifier.weight(1f).height(48.dp), enabled = h.myVote != "confirm") { Text(if (h.myVote == "confirm") "Confirmed" else "Still there") }
                    OutlinedButton(onClear, Modifier.weight(1f).height(48.dp), enabled = h.myVote != "clear") { Text(if (h.myVote == "clear") "Marked gone" else "It's gone") }
                }
            }
        }, confirmButton = { TextButton(onDismiss) { Text("Close") } })
}

/** Short, glanceable warning for the closest relevant hazard ahead. */
@Composable
fun HazardBanner(h: Hazard, distanceM: Double, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(hazardColor(h.category).copy(alpha = 0.95f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Text(hazardGlyph(h.category), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text("${h.category.label} ahead", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${HazardGeometry.distanceText(distanceM)}" + if (h.confidence == HazardConfidence.LOW) " · unconfirmed" else "", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
        }
    }
}

/** Local broadcast kind for the existing phone-to-phone HAZARD_ALERT message. */
fun HazardCategory.nearbyKind(): String = when (this) {
    HazardCategory.ACCIDENT -> "ACCIDENT"
    HazardCategory.ROAD_BLOCKAGE -> "OBSTRUCTION"
    HazardCategory.POTHOLE -> "ROAD_DAMAGE"
    HazardCategory.VEHICLE_BREAKDOWN -> "BROKEN_DOWN"
    HazardCategory.ROAD_CLOSURE -> "ROAD_CLOSURE"
    HazardCategory.CONSTRUCTION, HazardCategory.TRAFFIC_CONGESTION, HazardCategory.OTHER_DANGER -> "OTHER"
}

/** Wall-clock that ticks every [periodMs] so expiry-based filters re-run even when no data changes. */
@Composable
fun rememberNow(periodMs: Long = 10_000L): androidx.compose.runtime.State<Long> =
    androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(periodMs); value = System.currentTimeMillis() } }
