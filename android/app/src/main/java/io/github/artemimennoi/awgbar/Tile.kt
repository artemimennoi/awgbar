package io.github.artemimennoi.awgbar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.service.quicksettings.Tile as QsTile
import android.service.quicksettings.TileService

/** Quick settings tile: one tap connects the selected tunnel or disconnects. */
class Tile : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        if (Vpn.state.value.active == null && VpnService.prepare(this) != null) {
            // The VPN permission dialog needs an activity.
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            return
        }
        Vpn.toggle()
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val s = Vpn.state.value
        tile.state = if (s.active != null) QsTile.STATE_ACTIVE else if (Vpn.store.selected == null) QsTile.STATE_UNAVAILABLE else QsTile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = (s.active ?: Vpn.store.selected)?.let { Vpn.store.title(it) }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_status)
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) =
            requestListeningState(context, ComponentName(context, Tile::class.java))
    }
}
