package co.screenmate.can.autovolume

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Dark palette (Tesla's UI is dark; this reads well on the car screen and OLED). */
object Palette {
    const val BG = 0xFF15171C.toInt()
    const val CARD = 0xFF20242C.toInt()
    const val CARD_HI = 0xFF272C36.toInt()
    const val TEXT = 0xFFECEFF4.toInt()
    const val TEXT_DIM = 0xFF98A0AE.toInt()
    const val ACCENT = 0xFF54B9F6.toInt()
    const val ACCENT_DIM = 0x5554B9F6
    const val GRID = 0xFF333A45.toInt()
    const val RUNNING = 0xFF5CC98B.toInt()
    const val PAUSED = 0xFFF2B45B.toInt()
    const val WAITING = 0xFF7C8695.toInt()
    const val TRACK = 0xFF3A414D.toInt()
}

/** dp/sp helpers + a rounded card background, so the UI stays code-only (no res XML). */
object Ui {
    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

    fun card(radiusDp: Float, color: Int, density: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(color)
        }

    fun pill(radiusDp: Float, fill: Int, stroke: Int, strokeDp: Float, density: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke((strokeDp * density).toInt(), stroke)
        }
}
