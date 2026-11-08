package com.multicore.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.multicore.vpn.widget.VpnWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MulticoreVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.multicore.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.multicore.vpn.ACTION_DISCONNECT"
        private const val CHANNEL_ID = "multicore_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "MulticoreVpnService"

        var isRunning: Boolean = false
            private set

        @Volatile
        var instance: MulticoreVpnService? = null
            private set

        fun protectSocket(socket: java.net.Socket): Boolean {
            return instance?.protect(socket) ?: false
        }

        fun start(context: Context) {
            val intent = Intent(context, MulticoreVpnService::class.java).apply {
                action = ACTION_CONNECT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MulticoreVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand action: $action")
        when (action) {
            ACTION_CONNECT -> startVpnSession()
            ACTION_DISCONNECT -> stopVpnSession()
        }
        return START_STICKY
    }

    private fun startVpnSession() {
        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            try {
                val settings = AppSettings(this@MulticoreVpnService)
                val subUrl = settings.lastSubscriptionUrl
                val selectedRoute = MulticoreCoreJni.getSelectedRoute()
                Log.i(TAG, "Starting Dual-Core VPN session (Xray + Mihomo) for route: $selectedRoute")

                val builder = Builder()
                    .setSession("PORTAL VPN")
                    .addAddress("198.18.0.2", 30)
                    .addDnsServer("198.18.0.1")
                    .addRoute("0.0.0.0", 0) // Route all IPv4 traffic through VPN
                    .setMtu(1500)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                    builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", 7890))
                }

                val selectedApps = settings.selectedAppPackages
                val splitMode = settings.splitTunnelingMode

                if (splitMode == "PROXY" && selectedApps.isNotEmpty()) {
                    for (pkg in selectedApps) {
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not add allowed app: $pkg", e)
                        }
                    }
                } else {
                    for (pkg in selectedApps) {
                        try {
                            builder.addDisallowedApplication(pkg)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not add disallowed app: $pkg", e)
                        }
                    }
                }

                // Disallow self to prevent proxy loop
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not add disallowed self package", e)
                }

                // Set underlying physical network for VPN routing
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val activeNetwork = cm?.activeNetwork
                if (activeNetwork != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        setUnderlyingNetworks(arrayOf(activeNetwork))
                        Log.i(TAG, "Set VPN underlying network: $activeNetwork")
                    }
                }

                val pfd = builder.establish()
                if (pfd == null) {
                    Log.e(TAG, "Failed to establish VPN interface (null)")
                    stopVpnSession()
                    return@launch
                }
                vpnInterface = pfd

                // Clear FD_CLOEXEC so child process libmihomo can inherit this TUN fd
                val tunFd = pfd.fd
                try {
                    Os.fcntlInt(pfd.fileDescriptor, OsConstants.F_SETFD, 0)
                    Log.i(TAG, "Cleared FD_CLOEXEC on TUN fd: $tunFd")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear FD_CLOEXEC on TUN fd", e)
                }

                // Start proxy engines
                SingBoxManager.startSingBox(this@MulticoreVpnService, selectedRoute)
                MihomoManager.startMihomoAsync(this@MulticoreVpnService, subUrl, tunFd, pfd)

                // Start in-process native L3 tun2socks router for Telegram and all apps
                Tun2SocksManager.start(this@MulticoreVpnService, tunFd, "127.0.0.1:7890")

                serviceScope.launch {
                    delay(1200)
                    MihomoManager.selectProxyNode(selectedRoute)
                }

                isRunning = true
                TrafficStatsManager.resetSession()
                VpnWidgetProvider.notifyWidgetUpdate(this@MulticoreVpnService)
                PortalFailoverWatchdog.start(this@MulticoreVpnService)
                Log.i(TAG, "MULTICORE Dual-Core VPN Service started successfully (Xray + Mihomo) with Failover Watchdog")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting VPN session", e)
                stopVpnSession()
            }
        }
    }

    private fun stopVpnSession() {
        Log.i(TAG, "Stopping Dual-Core VPN session")
        isRunning = false
        PortalFailoverWatchdog.stop()
        Tun2SocksManager.stop()
        SingBoxManager.stopSingBox()
        MihomoManager.stopMihomo()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            setUnderlyingNetworks(null)
        }
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        VpnWidgetProvider.notifyWidgetUpdate(this)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val appIntent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, appIntent, flags)

        val stopIntent = Intent(this, MulticoreVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, flags)

        val largeIcon = try {
            BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        } catch (e: Exception) {
            null
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Portal VPN")
            .setContentText(getString(R.string.connected))
            .setSmallIcon(R.drawable.ic_stat_portal)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Выйти из портала", stopPendingIntent)
            .setOngoing(true)

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        return builder.build()
    }

    override fun onDestroy() {
        stopVpnSession()
        if (instance == this) instance = null
        super.onDestroy()
    }
}
