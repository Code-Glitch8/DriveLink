package com.drivelink.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.drivelink.hazards.HazardCategory
import org.maplibre.android.maps.Style

/**
 * One distinct badge per hazard category, drawn in code (no image assets, no emoji/font dependency).
 * Shape AND colour differ per category so they stay distinguishable for colour-blind users:
 * triangle = accident/danger, circle = blockage/closure/traffic, diamond = construction, etc.
 */
object HazardIcons {
    private const val S = 96
    private val WHITE = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private fun fill(hex: String) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(hex); style = Paint.Style.FILL }
    private fun outline() = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7f; strokeJoin = Paint.Join.ROUND }
    private fun glyph(w: Float = 8f) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND }

    private fun triangle(c: Canvas, hex: String) {
        val p = Path().apply { moveTo(S / 2f, 8f); lineTo(S - 8f, S - 12f); lineTo(8f, S - 12f); close() }
        c.drawPath(p, outline()); c.drawPath(p, fill(hex))
    }
    private fun circle(c: Canvas, hex: String) {
        c.drawCircle(S / 2f, S / 2f, 38f, outline()); c.drawCircle(S / 2f, S / 2f, 38f, fill(hex))
    }
    private fun diamond(c: Canvas, hex: String) {
        val p = Path().apply { moveTo(S / 2f, 6f); lineTo(S - 6f, S / 2f); lineTo(S / 2f, S - 6f); lineTo(6f, S / 2f); close() }
        c.drawPath(p, outline()); c.drawPath(p, fill(hex))
    }
    private fun square(c: Canvas, hex: String) {
        val r = RectF(10f, 10f, S - 10f, S - 10f)
        c.drawRoundRect(r, 16f, 16f, outline()); c.drawRoundRect(r, 16f, 16f, fill(hex))
    }

    private fun draw(cat: HazardCategory): Bitmap {
        val bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val g = glyph()
        when (cat) {
            HazardCategory.ACCIDENT -> { // red triangle with "!"
                triangle(c, "#D32F2F"); c.drawLine(S / 2f, 34f, S / 2f, 60f, glyph(9f)); c.drawCircle(S / 2f, 71f, 5f, WHITE)
            }
            HazardCategory.ROAD_BLOCKAGE -> { // red circle with a horizontal bar ("no entry")
                circle(c, "#C62828"); c.drawRoundRect(RectF(24f, 41f, S - 24f, 55f), 6f, 6f, WHITE)
            }
            HazardCategory.CONSTRUCTION -> { // orange diamond with diagonal stripes
                diamond(c, "#EF6C00"); c.drawLine(34f, 58f, 58f, 34f, g); c.drawLine(40f, 66f, 66f, 40f, g)
            }
            HazardCategory.POTHOLE -> { // amber square with a dark hole
                square(c, "#F9A825"); c.drawOval(RectF(26f, 38f, S - 26f, 62f), fill("#3E2723"))
            }
            HazardCategory.VEHICLE_BREAKDOWN -> { // blue square with a small car
                square(c, "#1565C0")
                c.drawRoundRect(RectF(23f, 44f, S - 23f, 64f), 6f, 6f, WHITE)
                c.drawRoundRect(RectF(33f, 32f, S - 33f, 48f), 6f, 6f, WHITE)
                c.drawCircle(36f, 66f, 6f, fill("#1565C0")); c.drawCircle(S - 36f, 66f, 6f, fill("#1565C0"))
            }
            HazardCategory.TRAFFIC_CONGESTION -> { // amber circle with three bars
                circle(c, "#B26A00"); for (y in listOf(32f, 46f, 60f)) c.drawLine(28f, y, S - 28f, y, glyph(7f))
            }
            HazardCategory.ROAD_CLOSURE -> { // dark-red circle with a cross
                circle(c, "#8E0000"); c.drawLine(32f, 32f, S - 32f, S - 32f, glyph(9f)); c.drawLine(S - 32f, 32f, 32f, S - 32f, glyph(9f))
            }
            HazardCategory.OTHER_DANGER -> { // purple diamond with "!"
                diamond(c, "#6A1B9A"); c.drawLine(S / 2f, 28f, S / 2f, 52f, glyph(9f)); c.drawCircle(S / 2f, 64f, 5f, WHITE)
            }
        }
        return bmp
    }

    fun install(style: Style) {
        HazardCategory.entries.forEach { style.addImage(MapIds.Image.hazard(it.key), draw(it)) }
    }
}
