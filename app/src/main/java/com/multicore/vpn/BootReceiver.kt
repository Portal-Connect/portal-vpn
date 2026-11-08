package com.multicore.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "BootReceiver onReceive action: $action")
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val settings = AppSettings(context)
            if (settings.autoConnectOnBoot) {
                Log.i(TAG, "AutoConnectOnBoot is enabled; launching PortalVpnService...")
                PortalVpnService.start(context)
            } else {
                Log.i(TAG, "AutoConnectOnBoot is disabled; skipping VPN auto-start")
            }
        }
    }
}
