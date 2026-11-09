package io.portal.vpn.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import io.portal.vpn.AppInterfaceMode
import io.portal.vpn.AppSettings
import io.portal.vpn.MainActivity
import io.portal.vpn.PortalCoreJni
import io.portal.vpn.PortalVpnService
import io.portal.vpn.R

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {
    companion object {
        private const val TAG = "VpnTileService"

        fun requestTileUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestListeningState(
                        context,
                        ComponentName(context, VpnTileService::class.java)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not request tile update: ${e.message}")
                }
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = PortalVpnService.isRunning
        Log.i(TAG, "Quick Settings Tile clicked. Current isRunning=$isRunning")

        if (isRunning) {
            try {
                PortalVpnService.stop(this)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop VPN from tile", e)
            }
            updateTileState(forceRunning = false)
        } else {
            val prepareIntent = android.net.VpnService.prepare(this)
            if (prepareIntent == null) {
                try {
                    PortalVpnService.start(this)
                    updateTileState(forceRunning = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start VPN directly from tile, falling back to activity", e)
                    openApp()
                }
            } else {
                openApp()
            }
        }
    }

    private fun openApp() {
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, appIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(appIntent)
        }
    }

    private fun updateTileState(forceRunning: Boolean? = null) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_portal)
        val running = forceRunning ?: PortalVpnService.isRunning
        val settings = AppSettings(this)
        val isSinglePortal = (settings.appInterfaceMode != AppInterfaceMode.MULTI_PORTAL)

        if (running) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = if (isSinglePortal) "В портале" else PortalCoreJni.getSelectedRoute().ifBlank { "В портале" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Активен"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Войти в портал"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Отключен"
            }
        }
        tile.updateTile()
    }
}
