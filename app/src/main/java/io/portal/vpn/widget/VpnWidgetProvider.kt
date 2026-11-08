package io.portal.vpn.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import io.portal.vpn.MainActivity
import io.portal.vpn.PortalVpnService
import io.portal.vpn.R

class VpnWidgetProvider : AppWidgetProvider() {
    companion object {
        const val ACTION_TOGGLE_VPN = "io.portal.vpn.widget.TOGGLE_VPN"
        const val ACTION_UPDATE_WIDGET = "io.portal.vpn.widget.UPDATE_WIDGET"
        private const val TAG = "VpnWidgetProvider"

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.vpn_widget_layout)
            val isRunning = PortalVpnService.isRunning
            if (isRunning) {
                views.setTextViewText(R.id.widget_status, "В портале")
                views.setTextColor(R.id.widget_status, Color.parseColor("#00F5A0"))
                views.setTextViewText(R.id.widget_btn_toggle, "ВЫЙТИ")
            } else {
                views.setTextViewText(R.id.widget_status, "В реальном мире")
                views.setTextColor(R.id.widget_status, Color.parseColor("#EF4444"))
                views.setTextViewText(R.id.widget_btn_toggle, "ВОЙТИ")
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val appIntent = Intent(context, MainActivity::class.java)
            val appPendingIntent = PendingIntent.getActivity(context, 0, appIntent, flags)
            views.setOnClickPendingIntent(R.id.widget_root, appPendingIntent)

            val toggleIntent = Intent(context, VpnWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_VPN
            }
            val togglePendingIntent = PendingIntent.getBroadcast(context, 0, toggleIntent, flags)
            views.setOnClickPendingIntent(R.id.widget_btn_toggle, togglePendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun notifyWidgetUpdate(context: Context) {
            val intent = Intent(context, VpnWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WIDGET
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        Log.i(TAG, "VpnWidgetProvider onReceive: $action")
        when (action) {
            ACTION_TOGGLE_VPN -> {
                if (PortalVpnService.isRunning) {
                    PortalVpnService.stop(context)
                } else {
                    PortalVpnService.start(context)
                }
            }
            ACTION_UPDATE_WIDGET, Intent.ACTION_BOOT_COMPLETED -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, VpnWidgetProvider::class.java))
                for (id in ids) {
                    updateWidget(context, appWidgetManager, id)
                }
            }
        }
    }
}
