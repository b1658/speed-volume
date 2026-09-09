package co.screenmate.can.autovolume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the background volume service after a reboot / car power-on, if the user allowed it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = VolumeSettings.from(context)
        if (settings.enabled && settings.startOnBoot) {
            VolumeControlService.start(context.applicationContext)
        }
    }
}
