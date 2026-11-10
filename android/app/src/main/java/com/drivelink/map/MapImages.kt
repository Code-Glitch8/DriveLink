package com.drivelink.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.maplibre.android.maps.Style

/** Draws the vehicle direction arrows in code (no bundled image assets, no glyph/font dependency). */
object MapImages {
    private const val SIZE = 72

    private fun arrow(fill: Int): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = SIZE / 2f
        val path = Path().apply {
            moveTo(cx, 6f)                       // nose (points north; rotated by the heading property)
            lineTo(SIZE - 14f, SIZE - 10f)
            lineTo(cx, SIZE - 22f)
            lineTo(14f, SIZE - 10f)
            close()
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f; strokeJoin = Paint.Join.ROUND }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL }
        c.drawPath(path, stroke)
        c.drawPath(path, body)
        return bmp
    }

    fun install(style: Style) {
        mapOf(
            MapIds.Image.ARROW_NORMAL to Color.parseColor("#8FA3C4"),
            MapIds.Image.ARROW_CAUTION to Color.parseColor("#D99100"),
            MapIds.Image.ARROW_HIGH to Color.parseColor("#E5392B"),
            MapIds.Image.ARROW_EMERGENCY to Color.parseColor("#B3001B"),
            MapIds.Image.ARROW_SELECTED to Color.parseColor("#4C8DFF"),
        ).forEach { (name, color) -> style.addImage(name, arrow(color)) }
        HazardIcons.install(style)
    }
}
