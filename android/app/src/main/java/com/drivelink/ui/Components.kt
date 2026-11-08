package com.drivelink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val BrandBrush = Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF6366F1)))

@Composable
fun BrandMark(size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(BrandBrush), contentAlignment = Alignment.Center) {
        Text("DL", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.36f).sp)
    }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)), tonalElevation = 1.dp, content = content)
}

@Composable
fun Chip(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val bg: Brush = if (enabled) BrandBrush else SolidColor(Color(0xFF3A4452))
    Box(modifier.height(54.dp).clip(RoundedCornerShape(16.dp)).background(bg).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}
