package com.drivelink.ui

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivelink.location.SpeedReading
import com.drivelink.orientation.MapOrientationMode

/**
 * Large, glanceable speed read-out. Fixed minimum width so digits never shift the layout; the number tweens
 * between values instead of jumping; when the speed is not trustworthy it shows an em dash and says so, rather
 * than keeping the last number on screen.
 */
@Composable
fun SpeedDisplay(reading: SpeedReading, modifier: Modifier = Modifier) {
    val target = (reading as? SpeedReading.Value)?.kmh ?: 0
    val shown by animateIntAsState(target, tween(350), label = "speed")
    val available = reading is SpeedReading.Value
    val color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.semantics { contentDescription = if (available) "Speed $target kilometres per hour" else "Speed unavailable" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (available) shown.toString() else "—",
            Modifier.widthIn(min = 96.dp), color = color, fontSize = 54.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center, maxLines = 1,
        )
        Text(
            if (available) "km/h" else "GPS speed unavailable",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        )
    }
}

/**
 * Orientation + recenter controls that sit on the map. [following] is false after the user pans away, in which
 * case Recenter is emphasised.
 */
@Composable
fun OrientationControls(
    mode: MapOrientationMode,
    following: Boolean,
    onToggleMode: () -> Unit,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        RoundMapButton(
            if (mode == MapOrientationMode.HEADING_UP) "▲" else "N", onToggleMode,
            if (mode == MapOrientationMode.HEADING_UP) "Heading-up. Tap for north-up" else "North-up. Tap for heading-up",
        )
        RoundMapButton("◎", onRecenter, "Recenter on my position", highlighted = !following)
    }
}

@Composable
fun RoundMapButton(label: String, onClick: () -> Unit, description: String, highlighted: Boolean = false, modifier: Modifier = Modifier) {
    val bg = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    val fg = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier.size(48.dp).clip(CircleShape).background(bg).clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}
