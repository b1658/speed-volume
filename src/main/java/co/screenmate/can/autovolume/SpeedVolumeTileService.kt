package co.screenmate.can.autovolume

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: toggle the whole feature on/off without opening the app. On = the background
 * [VolumeControlService] runs and adjusts volume; Off = it stops. Reflects [VolumeSettings.enabled].
 */
class SpeedVolumeTileService : TileService() {

    override fun onStartListening() { refresh() }

    override fun onClick() {
        val settings = VolumeSettings.from(this)
        val now = !settings.enabled
        settings.enabled = now
        if (now) VolumeControlService.start(this) else VolumeControlService.stop(this)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = VolumeSettings.from(this).enabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Speed Volume"
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_lock_silent_mode_off)
        tile.updateTile()
    }
}
