package co.screenmate.can.autovolume

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import co.screenmate.can.privileged.PrivilegedBroadcastClient
import co.screenmate.can.tx.client.CanTx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Background host that maps live vehicle speed (and cabin noise) onto the media volume, BMW-style.
 * Owns a [PrivilegedBroadcastClient] narrowed to [Signals.ALL]; on every accepted batch it decides
 * how much of a volume boost to hold and nudges the volume toward it.
 *
 * ## Relative control (no read-back)
 * The volume is driven by the steering-wheel LEFT scroll ([CanTx.volumeDetents]) — the native Tesla
 * control that reaches the amp — which is a *relative* input, and there is no volume signal on CAN to
 * read back. So the app never targets an absolute level: it tracks its own [appliedOffset] (net
 * detents it has added) and moves it toward a speed/noise-derived target. Your own volume is the
 * baseline; the app only adds on top and winds back off as you slow — so it inherently respects
 * manual changes and needs no absolute feedback. [WriteBackend.ANDROID_AUDIO] is a fallback that
 * applies the same detents as AudioManager steps when TX is unavailable.
 *
 * A foreground service so it runs unattended. Scroll TX is blocking adb I/O, so every volume write is
 * serialized onto a private [applyThread], never the main thread.
 *
 * Niceties: perceptual curve, a deadband + time-based slew, drive-state gating ([Signals.GEAR]) with
 * wind-off on park, noise-aware boost (fan RPM + window position), and a notification / QS-tile pause.
 */
class VolumeControlService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var jobs = mutableListOf<Job>()
    private var client: PrivilegedBroadcastClient? = null
    private lateinit var audio: AudioManager
    private lateinit var settings: VolumeSettings
    private var canTx: CanTx? = null

    private val applyThread = HandlerThread("smcan-volume-apply").apply { start() }
    private val applyHandler = Handler(applyThread.looper)

    // --- state touched only on [apply] ---
    private var appliedOffset: Int = 0     // net volume detents we've added on top of the driver's
    private var lastUpdateMs: Long = 0
    private var wasInDrive: Boolean = false

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        settings = VolumeSettings.from(this)
        startForegroundCompat()

        val c = PrivilegedBroadcastClient(applicationContext, requestedSignals = Signals.ALL)
        client = c
        c.start()
        live = c

        jobs += scope.launch { c.ticks.collect { applyHandler.post { evaluate() } } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_TOGGLE_PAUSE -> setPaused(!paused)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        // Best-effort: wind our added boost back off so we don't leave the volume raised.
        applyHandler.post { if (appliedOffset != 0) { step(-appliedOffset); appliedOffset = 0 } }
        applyHandler.post {
            client?.stop(); client = null; live = null
            applyThread.quitSafely()
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
    }

    /** Runs on [apply]. Decide the target offset and slew [appliedOffset] toward it. */
    private fun evaluate() {
        val c = client ?: return
        if (!settings.enabled || paused) return
        if (!c.isSignalFresh(Signals.SPEED)) return
        val speed = c.latestFloat(Signals.SPEED) ?: return

        var desired = SpeedVolumeMapper.targetBoostSteps(
            speed = speed,
            minSpeed = settings.minSpeedKmh,
            maxSpeed = settings.maxSpeedKmh,
            maxBoostSteps = settings.maxBoostSteps,
            curve = settings.curve,
            fanRatio = if (settings.noiseAwareEnabled) fanRatio(c) else 0f,
            fanBoostSteps = settings.fanBoostSteps,
            windowRatio = if (settings.noiseAwareEnabled) windowRatio(c) else 0f,
            windowBoostSteps = settings.windowBoostSteps,
        )

        // Drive-state gating: parked -> wind boost off (or hold, if restore is disabled).
        if (settings.onlyInDrive) {
            val inDrive = if (c.isSignalFresh(Signals.GEAR))
                c.latestInt(Signals.GEAR) == Signals.GEAR_DRIVE else true
            wasInDrive = inDrive
            if (!inDrive) desired = if (settings.restoreOnPark) 0 else appliedOffset
        }

        // Don't move the volume when nothing is playing, if the user opted for that.
        if (settings.onlyWhilePlaying && !audio.isMusicActive) desired = appliedOffset

        val diff = desired - appliedOffset
        // Hysteresis: ignore sub-deadband changes, but never let it block a full wind-off to 0.
        if (desired != 0 && abs(diff) <= settings.deadbandSteps) {
            lastUpdateMs = SystemClock.elapsedRealtime(); return
        }

        // Time-based slew: at most (maxBoost / rampSeconds) detents per second, >= 1 per update.
        val now = SystemClock.elapsedRealtime()
        val dtMs = if (lastUpdateMs == 0L) settings.rampSeconds * 1000L else (now - lastUpdateMs)
        val stepsPerSec = settings.maxBoostSteps.toFloat() / settings.rampSeconds
        val maxSteps = max(1, Math.round(stepsPerSec * (dtMs / 1000f)))
        val move = diff.coerceIn(-maxSteps, maxSteps)

        if (move != 0) {
            step(move)
            appliedOffset += move
        }
        lastUpdateMs = now
        offset = appliedOffset
    }

    /** Apply a signed volume nudge of [detents] via the selected backend. Runs on [apply]. */
    private fun step(detents: Int) {
        if (detents == 0) return
        when (settings.backend) {
            WriteBackend.SCROLL -> {
                val tx = canTx ?: CanTx(applicationContext).also { canTx = it }
                lastTxResult = tx.volumeDetents(detents)
            }
            WriteBackend.ANDROID_AUDIO -> {
                val dir = if (detents > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                repeat(abs(detents)) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) }
                lastTxResult = "audio ${if (detents > 0) "+" else ""}$detents"
            }
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

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Speed volume", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, VolumeControlService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(if (paused) "Speed volume paused" else "Speed volume active")
            .setContentText(
                if (paused) "Not adjusting media volume"
                else "Adjusting media volume with vehicle speed"
            )
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null, if (paused) "Resume" else "Pause", toggle,
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "speed-volume"
        private const val NOTIF_ID = 0x5C12

        const val ACTION_PAUSE = "co.screenmate.can.autovolume.PAUSE"
        const val ACTION_RESUME = "co.screenmate.can.autovolume.RESUME"
        const val ACTION_TOGGLE_PAUSE = "co.screenmate.can.autovolume.TOGGLE_PAUSE"

        /** Transient pause (keeps the service resident but idle). Reset on process restart. */
        @Volatile var paused: Boolean = false
            private set
        /** Current net volume boost the app is holding, in detents (for the UI). */
        @Volatile var offset: Int = 0
            private set
        /** Last TX/audio result line (audit output or error), for the UI. */
        @Volatile var lastTxResult: String? = null
            private set

        /** The client owned by the running service, or null when stopped. Exposed for the UI. */
        @Volatile var live: PrivilegedBroadcastClient? = null
            private set

        fun start(context: Context) {
            val i = Intent(context, VolumeControlService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            paused = false
            context.stopService(Intent(context, VolumeControlService::class.java))
        }

        fun sendControl(context: Context, action: String) {
            context.startService(Intent(context, VolumeControlService::class.java).setAction(action))
        }
    }
}
