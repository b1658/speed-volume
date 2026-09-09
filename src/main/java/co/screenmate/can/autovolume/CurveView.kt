package co.screenmate.can.autovolume

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Live visualization of the speed -> volume-boost mapping. Draws the configured curve (with the ramp
 * region shaded), the min/max-speed guides, and a moving marker at the current speed showing the
 * boost the app is targeting vs holding. Makes the otherwise-abstract "detents" tangible: you watch
 * the dot climb the curve as you speed up.
 *
 * All values are pushed in via [set]; the view is otherwise stateless and reads no globals.
 */
class CurveView(context: Context) : View(context) {

    private var minSpeed = VolumeSettings.DEF_MIN_SPEED
    private var maxSpeed = VolumeSettings.DEF_MAX_SPEED
    private var unit = VolumeSettings.DEF_SPEED_UNIT
    private var maxBoost = VolumeSettings.DEF_MAX_BOOST
    private var curve = VolumeSettings.DEF_CURVE
    private var speed: Float? = null
    private var target = 0
    private var held = 0

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Palette.ACCENT
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Palette.ACCENT_DIM }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * d; color = Palette.GRID }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * d; color = Palette.TEXT_DIM; pathEffect =
            android.graphics.DashPathEffect(floatArrayOf(6f * d, 6f * d), 0f)
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * d; color = Palette.RUNNING }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Palette.RUNNING }
    private val heldFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Palette.ACCENT }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT_DIM; textSize = 11f * d }
    private val bigLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT; textSize = 13f * d }

    fun set(
        minSpeed: Int, maxSpeed: Int, maxBoost: Int, curve: SpeedVolumeMapper.Curve,
        speed: Float?, target: Int, held: Int, unit: SpeedUnit = VolumeSettings.DEF_SPEED_UNIT,
    ) {
        this.minSpeed = minSpeed; this.maxSpeed = maxSpeed; this.maxBoost = maxBoost
        this.curve = curve; this.speed = speed; this.target = target; this.held = held
        this.unit = unit
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val height = (170 * d).toInt()
        setMeasuredDimension(MeasureSpec.getSize(w), height)
    }

    override fun onDraw(canvas: Canvas) {
        val padL = 34f * d; val padR = 14f * d; val padT = 16f * d; val padB = 22f * d
        val w = width - padL - padR; val h = height - padT - padB
        if (w <= 0 || h <= 0) return

        // Axis extents: give a little headroom above the max boost + past the full-boost speed.
        val axisSpeed = (maxSpeed * 1.15f).coerceAtLeast(maxSpeed + 20f)
        val boostHeadroom = (maxBoost + 2).coerceAtLeast(2)
        fun sx(s: Float) = padL + (s / axisSpeed).coerceIn(0f, 1f) * w
        fun sy(b: Float) = padT + h - (b / boostHeadroom).coerceIn(0f, 1f) * h

        // Horizontal gridlines + boost labels (0 and max).
        listOf(0, maxBoost).distinct().forEach { b ->
            val y = sy(b.toFloat())
            canvas.drawLine(padL, y, padL + w, y, grid)
            canvas.drawText("+$b", 4f * d, y + 4f * d, label)
        }

        // Curve polyline + shaded fill under it.
        val path = Path(); val area = Path()
        val steps = 64
        for (i in 0..steps) {
            val s = axisSpeed * i / steps
            val b = SpeedVolumeMapper.targetBoostSteps(s, minSpeed, maxSpeed, maxBoost, curve)
            val x = sx(s); val y = sy(b.toFloat())
            if (i == 0) { path.moveTo(x, y); area.moveTo(x, sy(0f)); area.lineTo(x, y) }
            else { path.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo(sx(axisSpeed), sy(0f)); area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)

        // min/max speed guides.
        listOf(minSpeed, maxSpeed).forEach { s ->
            val x = sx(s.toFloat())
            canvas.drawLine(x, padT, x, padT + h, guide)
            canvas.drawText("${unit.fromKmh(s)}", x - 8f * d, height - 5f * d, label)
        }
        canvas.drawText(unit.label, padL + w - 30f * d, height - 5f * d, label)

        // Live marker: vertical line at current speed, a filled dot at the target boost, and a
        // smaller ring at what we're currently holding (so you see it slew toward the target).
        val sp = speed
        if (sp != null) {
            val x = sx(sp)
            canvas.drawLine(x, padT, x, padT + h, marker)
            canvas.drawCircle(x, sy(target.toFloat()), 6f * d, dotFill)
            canvas.drawCircle(x, sy(held.toFloat()), 4f * d, heldFill)
            canvas.drawText("+$held", x + 9f * d, sy(held.toFloat()) + 4f * d, bigLabel)
        }
    }
}
