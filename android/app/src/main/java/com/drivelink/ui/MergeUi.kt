package com.drivelink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivelink.driving.MergeAlert
import com.drivelink.driving.MyMerge

/** Left/right chevron drawn with a Path, so it needs no icon dependency and stays crisp at any size. */
@Composable
fun MergeArrow(left: Boolean, color: Color, size: Dp = 36.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val p = Path().apply {
            if (left) { moveTo(w * 0.15f, h * 0.5f); lineTo(w * 0.6f, h * 0.12f); lineTo(w * 0.6f, h * 0.36f); lineTo(w * 0.92f, h * 0.36f); lineTo(w * 0.92f, h * 0.64f); lineTo(w * 0.6f, h * 0.64f); lineTo(w * 0.6f, h * 0.88f) }
            else { moveTo(w * 0.85f, h * 0.5f); lineTo(w * 0.4f, h * 0.12f); lineTo(w * 0.4f, h * 0.36f); lineTo(w * 0.08f, h * 0.36f); lineTo(w * 0.08f, h * 0.64f); lineTo(w * 0.4f, h * 0.64f); lineTo(w * 0.4f, h * 0.88f) }
            close()
        }
        drawPath(p, color)
    }
}

/** Temporary alert for an incoming merge request. Disappears on its own (expiry) or when the sender cancels. */
@Composable
fun MergeAlertBanner(a: MergeAlert, modifier: Modifier = Modifier) {
    val left = a.direction == "LEFT"
    val bg = if (a.uncertain) Color(0xFF8A6D00) else Color(0xFFB35A00)
    Row(modifier.fillMaxWidth().background(bg, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MergeArrow(left, Color.White)
        Column(Modifier.weight(1f)) {
            Text(if (a.uncertain) "Vehicle nearby may merge ${if (left) "left" else "right"}" else "Vehicle merging ${if (left) "left" else "right"}",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${a.label}" + (if (a.side != "nearby") " · on your ${a.side}" else "") + " · advisory", color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** Shown to the driver who sent a merge signal. One large Cancel target; no typing. */
@Composable
fun MyMergeBar(m: MyMerge, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val left = m.direction == "LEFT"
    Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MergeArrow(left, MaterialTheme.colorScheme.primary, 30.dp)
        Text("Merge ${if (left) "left" else "right"} signalled to ${m.recipientCount}", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        OutlinedButton(onCancel, Modifier.height(44.dp)) { Text("Cancel") }
    }
}
