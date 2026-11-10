package com.drivelink.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Data-source credit required by OpenStreetMap / OpenFreeMap / OpenMapTiles. Keep it visible at all times. */
@Composable
fun MapAttribution(modifier: Modifier = Modifier) {
    Text(
        MapStyles.ATTRIBUTION, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        modifier = modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun MapControlButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) }
}

@Composable
fun MapStatusBanner(status: MapStatus, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val text = when (status) {
        is MapStatus.Fallback -> status.message
        is MapStatus.Offline -> status.message
        MapStatus.Loading -> "Loading map…"
        MapStatus.Ready -> return
    }
    val color = if (status is MapStatus.Offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), RoundedCornerShape(14.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp))
        if (status !is MapStatus.Loading) TextButton(onRetry) { Text("Retry", fontSize = 12.sp) }
    }
}
