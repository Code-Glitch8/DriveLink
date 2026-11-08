package com.drivelink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Dark = darkColorScheme(
    primary = Color(0xFF4C8DFF), onPrimary = Color.White, primaryContainer = Color(0xFF16305F), onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF7C83FF), background = Color(0xFF0A0E14), onBackground = Color(0xFFE8EDF5),
    surface = Color(0xFF111826), onSurface = Color(0xFFE8EDF5), surfaceVariant = Color(0xFF162033), onSurfaceVariant = Color(0xFF8B98B0),
    outline = Color(0xFF2C3A55), error = Color(0xFFFF6B6B),
)
private val Light = lightColorScheme(
    primary = Color(0xFF2563EB), onPrimary = Color.White, primaryContainer = Color(0xFFDCE7FF), onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF4F46E5), background = Color(0xFFF4F6FB), onBackground = Color(0xFF0F1A2B),
    surface = Color.White, onSurface = Color(0xFF0F1A2B), surfaceVariant = Color(0xFFF1F4FA), onSurfaceVariant = Color(0xFF5D6B82),
    outline = Color(0xFFD5DDEA), error = Color(0xFFD93025),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp),
)

@Composable
fun DriveLinkTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, shapes = AppShapes, content = content)
}

fun levelColor(level: Int): Color = when (level) {
    3 -> Color(0xFFB3001B)
    2 -> Color(0xFFE5392B)
    1 -> Color(0xFFD99100)
    else -> Color(0xFF2B3647)
}
