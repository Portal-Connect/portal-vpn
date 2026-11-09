package io.portal.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class VpnNotificationReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_NOTIFICATION_PING = "io.portal.vpn.ACTION_NOTIFICATION_PING"
        const val ACTION_NOTIFICATION_STOP = "io.portal.vpn.ACTION_NOTIFICATION_STOP"
        private const val TAG = "VpnNotificationReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received notification action: $action")
        when (action) {
            ACTION_NOTIFICATION_PING -> {
                PortalVpnService.triggerPingTest()
            }
            ACTION_NOTIFICATION_STOP -> {
                PortalVpnService.stop(context)
            }
        }
    }
}
