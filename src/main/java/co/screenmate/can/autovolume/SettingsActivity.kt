package co.screenmate.can.autovolume

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import co.screenmate.can.autovolume.Ui.card
import co.screenmate.can.autovolume.Ui.dp
import co.screenmate.can.autovolume.Ui.pill
import co.screenmate.can.privileged.PrivilegedBroadcastClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Settings + live dashboard for speed-dependent volume. Code-only UI (no res XML, matching the SDK
 * apps) but given a proper dark, card-based, Tesla-appropriate look: a color-coded status header, a
 * live speed→boost curve ([CurveView]) that animates as you drive, grouped control cards with live
 * value labels, and a two-column layout in landscape (the car's orientation).
 *
 * Every control writes straight through to [VolumeSettings], which the running [VolumeControlService]
 * reads live per tick — and also refreshes the curve/status immediately so tuning is visual.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: VolumeSettings
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusJob: Job? = null
    private val density get() = resources.displayMetrics.density

    private lateinit var curve: CurveView
    private lateinit var statusDot: TextView
    private lateinit var statusHead: TextView
    private lateinit var statusDetail: TextView
    private lateinit var pauseChip: TextView
    private lateinit var scrollChip: TextView
    private lateinit var audioChip: TextView
    private lateinit var unitKmhChip: TextView
    private lateinit var unitMphChip: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = VolumeSettings.from(this)

        curve = CurveView(this)
        val overview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusCard())
            addView(curveCard())
        }
        val controls = ScrollView(this).apply { addView(controlsColumn()) }

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root: View = if (landscape) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Palette.BG)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(ScrollView(this@SettingsActivity).apply { addView(overview) },
                    LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = dp(8) })
                addView(controls, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(8) })
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Palette.BG)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(overview)
                addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
        }
        setContentView(root)

        if (settings.enabled) VolumeControlService.start(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        statusJob = scope.launch {
            val c = VolumeControlService.live ?: run { refresh(); return@launch }
            c.ticks.collect { refresh() }
        }
        refresh()
    }

    override fun onPause() { statusJob?.cancel(); statusJob = null; super.onPause() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    // ---------------------------------------------------------------- status ----

    private fun statusCard(): View {
        val cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card(18f, Palette.CARD, density)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = TextView(this).apply {
            text = "●"; textSize = 14f; setPadding(0, 0, dp(8), 0)
        }
        statusHead = TextView(this).apply {
            textSize = 20f; setTextColor(Palette.TEXT); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        pauseChip = chip("Pause") {
            VolumeControlService.sendControl(this, VolumeControlService.ACTION_TOGGLE_PAUSE)
            postRefresh()
        }
        headRow.addView(statusDot); headRow.addView(statusHead); headRow.addView(pauseChip)

        statusDetail = TextView(this).apply {
            textSize = 14f; setTextColor(Palette.TEXT_DIM); setPadding(0, dp(10), 0, 0)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        cardView.addView(headRow); cardView.addView(statusDetail)
        return wrapMargins(cardView, bottom = dp(12))
    }

    private fun curveCard(): View {
        val cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card(18f, Palette.CARD, density)
            setPadding(dp(14), dp(12), dp(14), dp(10))
            addView(cardTitle("Speed → volume boost"))
            addView(curve, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        return wrapMargins(cardView, bottom = dp(12))
    }

    private fun refresh() {
        val c = VolumeControlService.live
        val enabled = settings.enabled
        val paused = VolumeControlService.paused
        val running = c != null && enabled
        val speed = c?.takeIf { it.isSignalFresh(Signals.SPEED) }?.latestFloat(Signals.SPEED)
        val gear = c?.takeIf { it.isSignalFresh(Signals.GEAR) }?.latestInt(Signals.GEAR)
        val fanR = if (settings.noiseAwareEnabled) c?.let { fanRatio(it) } ?: 0f else 0f
        val winR = if (settings.noiseAwareEnabled) c?.let { windowRatio(it) } ?: 0f else 0f

        val (dotColor, head) = when {
            !enabled -> Palette.WAITING to "Off"
            paused -> Palette.PAUSED to "Paused"
            speed == null -> Palette.WAITING to "Waiting for the car"
            else -> Palette.RUNNING to "Active"
        }
        statusDot.setTextColor(dotColor)
        statusHead.text = head
        pauseChip.visibility = if (running) View.VISIBLE else View.GONE
        pauseChip.text = if (paused) "Resume" else "Pause"

        val target = if (speed != null) SpeedVolumeMapper.targetBoostSteps(
            speed, settings.minSpeedKmh, settings.maxSpeedKmh, settings.maxBoostSteps, settings.curve,
            fanR, settings.fanBoostSteps, winR, settings.windowBoostSteps,
        ) else 0
        val held = VolumeControlService.offset

        statusDetail.text = buildString {
            when {
                !enabled -> append("Turn on below to start adjusting volume with speed.")
                speed == null -> append("No vehicle speed yet — install/open the Injector app so the " +
                    "agent is running, then drive.")
                else -> {
                    val u = settings.speedUnit
                    val gearStr = if (gear == Signals.GEAR_DRIVE) "D" else gear?.toString() ?: "–"
                    append("%.0f %s   ·   gear %s\n".format(u.fromKmh(speed), u.label, gearStr))
                    append("boost  +%d steps held   (target +%d)\n".format(held, target))
                    val via = if (settings.backend == WriteBackend.SCROLL) "left scroll wheel" else "Android audio"
                    append("via $via")
                    VolumeControlService.lastTxResult?.lineSequence()?.firstOrNull()?.let {
                        append("   ·   ").append(it.take(48))
                    }
                }
            }
        }

        curve.set(settings.minSpeedKmh, settings.maxSpeedKmh, settings.maxBoostSteps, settings.curve,
            speed, target, held, settings.speedUnit)
    }

    private fun postRefresh() = curve.postDelayed({ refresh() }, 150)

    // -------------------------------------------------------------- controls ----

    private fun controlsColumn(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(group {
            addView(switchRow("Enabled", settings.enabled) { on ->
                settings.enabled = on
                if (on) VolumeControlService.start(this@SettingsActivity)
                else VolumeControlService.stop(this@SettingsActivity)
                refresh()
            })
            addView(rowLabel("Control method"))
            addView(backendSelector())
        })

        col.addView(group {
            addView(cardTitle("Boost & speed range"))
            addView(slider("Louder at top speed", settings.maxBoostSteps, 0, 30, "steps") {
                settings.maxBoostSteps = it; refresh()
            })
            addView(rowLabel("Speed units"))
            addView(unitSelector())
            addView(speedSlider("Boost starts at", settings.minSpeedKmh, 0, 160) {
                settings.minSpeedKmh = it; refresh()
            })
            addView(speedSlider("Full boost at", settings.maxSpeedKmh, 0, 200) {
                settings.maxSpeedKmh = it; refresh()
            })
        })

        col.addView(group {
            addView(cardTitle("Curve & smoothing"))
            addView(switchRow("Smooth (perceptual) curve",
                settings.curve == SpeedVolumeMapper.Curve.PERCEPTUAL) {
                settings.curve = if (it) SpeedVolumeMapper.Curve.PERCEPTUAL else SpeedVolumeMapper.Curve.LINEAR
                refresh()
            })
            addView(slider("Ramp time", settings.rampSeconds, 1, 30, "s") { settings.rampSeconds = it })
            addView(slider("Deadband (anti-hunt)", settings.deadbandSteps, 0, 10, "steps") {
                settings.deadbandSteps = it
            })
        })

        col.addView(group {
            addView(cardTitle("Noise-aware boost"))
            addView(switchRow("Boost for fan & open windows", settings.noiseAwareEnabled) {
                settings.noiseAwareEnabled = it; refresh()
            })
            addView(slider("Extra at full fan", settings.fanBoostSteps, 0, 15, "steps") {
                settings.fanBoostSteps = it; refresh()
            })
            addView(slider("Extra with windows open", settings.windowBoostSteps, 0, 15, "steps") {
                settings.windowBoostSteps = it; refresh()
            })
        })

        col.addView(group {
            addView(cardTitle("Drive & behaviour"))
            addView(switchRow("Only boost while in Drive", settings.onlyInDrive) { settings.onlyInDrive = it })
            addView(switchRow("Wind boost off when parked", settings.restoreOnPark) { settings.restoreOnPark = it })
            addView(switchRow("Only while media is playing", settings.onlyWhilePlaying) { settings.onlyWhilePlaying = it })
            addView(switchRow("Start automatically on boot", settings.startOnBoot) { settings.startOnBoot = it })
        })
        return col
    }

    private fun backendSelector(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        scrollChip = segChip("Scroll wheel", settings.backend == WriteBackend.SCROLL) {
            settings.backend = WriteBackend.SCROLL; updateBackendChips(); refresh()
        }
        audioChip = segChip("Android audio", settings.backend == WriteBackend.ANDROID_AUDIO) {
            settings.backend = WriteBackend.ANDROID_AUDIO; updateBackendChips(); refresh()
        }
        row.addView(scrollChip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(audioChip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        return row
    }

    private fun updateBackendChips() {
        styleSeg(scrollChip, settings.backend == WriteBackend.SCROLL)
        styleSeg(audioChip, settings.backend == WriteBackend.ANDROID_AUDIO)
    }

    private fun unitSelector(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        unitKmhChip = segChip("km/h", settings.speedUnit == SpeedUnit.KMH) { setUnit(SpeedUnit.KMH) }
        unitMphChip = segChip("mph", settings.speedUnit == SpeedUnit.MPH) { setUnit(SpeedUnit.MPH) }
        row.addView(unitKmhChip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(unitMphChip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        return row
    }

    /** Persist the chosen unit and rebuild the screen so sliders/labels/curve redraw in it. */
    private fun setUnit(u: SpeedUnit) {
        if (settings.speedUnit == u) return
        settings.speedUnit = u
        recreate()
    }

    /**
     * A speed slider that shows/edits in the user's chosen unit while storing canonical km/h. The
     * km/h bounds are converted for display, and the user's value is converted back on change.
     */
    private fun speedSlider(labelText: String, initialKmh: Int, minKmh: Int, maxKmh: Int, onChangeKmh: (Int) -> Unit): View {
        val u = settings.speedUnit
        return slider(labelText, u.fromKmh(initialKmh), u.fromKmh(minKmh), u.fromKmh(maxKmh), u.label) { shown ->
            onChangeKmh(u.toKmh(shown))
        }
    }

    // ----------------------------------------------------------- view helpers ----

    private inline fun group(build: LinearLayout.() -> Unit): View {
        val g = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card(18f, Palette.CARD, density)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            build()
        }
        return wrapMargins(g, bottom = dp(12))
    }

    private fun wrapMargins(v: View, bottom: Int = 0): View {
        v.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = bottom }
        return v
    }

    private fun cardTitle(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Palette.TEXT); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(4))
    }
    private fun rowLabel(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Palette.TEXT_DIM); setPadding(0, dp(8), 0, dp(2))
    }

    private fun switchRow(labelText: String, initial: Boolean, onChange: (Boolean) -> Unit): View =
        Switch(this).apply {
            text = labelText; textSize = 15f; setTextColor(Palette.TEXT); isChecked = initial
            setPadding(0, dp(10), 0, dp(10))
            thumbTintList = ColorStateList.valueOf(Palette.ACCENT)
            trackTintList = ColorStateList.valueOf(Palette.TRACK)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    private fun slider(labelText: String, initial: Int, min: Int, max: Int, unit: String, onChange: (Int) -> Unit): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(6)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val name = TextView(this).apply {
            text = labelText; textSize = 15f; setTextColor(Palette.TEXT)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val value = TextView(this).apply {
            text = "$initial $unit"; textSize = 15f; setTextColor(Palette.ACCENT)
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(name); header.addView(value)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (initial - min).coerceIn(0, this.max)
            progressTintList = ColorStateList.valueOf(Palette.ACCENT)
            thumbTintList = ColorStateList.valueOf(Palette.ACCENT)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val v = p + min
                value.text = "$v $unit"
                if (fromUser) onChange(v)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        container.addView(header); container.addView(bar)
        return container
    }

    /** A rounded accent "chip" button (used for Pause/Resume). */
    private fun chip(textLabel: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = textLabel; textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(8), dp(18), dp(8))
        background = pill(22f, Palette.ACCENT, Color.TRANSPARENT, 0f, density)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** A selectable segmented chip (backend selector). */
    private fun segChip(textLabel: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = textLabel; textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
            styleSeg(this, selected)
        }

    private fun styleSeg(t: TextView, selected: Boolean) {
        if (selected) {
            t.setTextColor(Color.WHITE); t.typeface = Typeface.DEFAULT_BOLD
            t.background = pill(12f, Palette.ACCENT, Color.TRANSPARENT, 0f, density)
        } else {
            t.setTextColor(Palette.TEXT_DIM); t.typeface = Typeface.DEFAULT
            t.background = pill(12f, Palette.CARD_HI, Palette.TRACK, 1f, density)
        }
    }

    private fun fanRatio(c: PrivilegedBroadcastClient): Float {
        if (!c.isSignalFresh(Signals.FAN_RPM)) return 0f
        return ((c.latestInt(Signals.FAN_RPM) ?: 0) / Signals.FAN_RPM_MAX).coerceIn(0f, 1f)
    }

    private fun windowRatio(c: PrivilegedBroadcastClient): Float {
        val open = Signals.WINDOWS.filter { c.isSignalFresh(it) }.mapNotNull { c.latestInt(it) }
            .maxOrNull() ?: return 0f
        return (open / Signals.WINDOW_RAW_MAX).coerceIn(0f, 1f)
    }
}
