package io.portal.vpn.widget

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import io.portal.vpn.PortalVpnService
import io.portal.vpn.R

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
        
        // Use PendingIntent for robust background-to-foreground transition in Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ specific handling for Tiles
            val intent = android.content.Intent(this, PortalVpnService::class.java).apply {
                action = if (PortalVpnService.isRunning) PortalVpnService.ACTION_STOP_VPN else PortalVpnService.ACTION_START_VPN
            }
            val pendingIntent = android.app.PendingIntent.getService(
                this, 
                if (PortalVpnService.isRunning) 3 else 4, 
                intent, 
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            // Fallback for older versions via BroadcastReceiver
            val intent = android.content.Intent(this, VpnWidgetProvider::class.java).apply {
                action = "io.portal.vpn.widget.TOGGLE_VPN"
            }
            sendBroadcast(intent)
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
