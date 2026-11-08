package com.multicore.vpn.widget

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.multicore.vpn.PortalVpnService
import com.multicore.vpn.R

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {
    companion object {
        private const val TAG = "VpnTileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        Log.i(TAG, "Quick Settings Tile clicked")
        if (PortalVpnService.isRunning) {
            PortalVpnService.stop(this)
        } else {
            PortalVpnService.start(this)
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_portal)
        if (PortalVpnService.isRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "В портале"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Войти в портал"
        }
        tile.updateTile()
    }
}
