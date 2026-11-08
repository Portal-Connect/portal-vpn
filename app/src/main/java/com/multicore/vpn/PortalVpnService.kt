package com.multicore.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.multicore.vpn.widget.VpnWidgetProvider
import kotlinx.coroutines.*
import java.io.File
import java.net.Socket

class PortalVpnService : VpnService() {

    companion object {
        const val TAG = "PortalVpnService"
        const val ACTION_START_VPN = "com.multicore.vpn.START_VPN"
        const val ACTION_STOP_VPN = "com.multicore.vpn.STOP_VPN"
        const val CHANNEL_ID = "portal_vpn_channel"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var instance: PortalVpnService? = null

        fun start(context: Context) {
            val intent = Intent(context, PortalVpnService::class.java).apply {
                action = ACTION_START_VPN
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PortalVpnService::class.java).apply {
                action = ACTION_STOP_VPN
            }
            context.startService(intent)
        }

        fun protectSocket(socket: Socket): Boolean {
            return instance?.protect(socket) ?: false
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_VPN
        Log.i(TAG, "onStartCommand received action: $action")

        when (action) {
            ACTION_START_VPN -> {
                startForeground(NOTIFICATION_ID, buildNotification("🌀 В ПОРТАЛЕ", "Квантовый щит активен • Трафик защищён"))
                startVpnSession()
            }
            ACTION_STOP_VPN -> {
                stopVpnSession()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startVpnSession() {
        if (isRunning) {
            Log.w(TAG, "VPN is already running")
            return
        }

        serviceScope.launch {
            try {
                val settings = AppSettings(this@PortalVpnService)
                val subUrl = settings.lastSubscriptionUrl.ifBlank {
                    // Fallback to demo/default if not set
                    "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt"
                }

                val selectedRoute = PortalCoreJni.getSelectedRoute()
                Log.i(TAG, "Starting Portal VPN session (Xray + Mihomo) for route: $selectedRoute")

                val builder = Builder()
                    .setSession("PORTAL VPN")
                    .addAddress("198.18.0.2", 30)
                    .addDnsServer("198.18.0.1")
                    .addDnsServer("77.88.8.8")
                    .addRoute("0.0.0.0", 0) // Route all IPv4 traffic through VPN
                    .setMtu(1500)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
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

                    // Apply automatic OS-level bypass for Russian banks, Gosuslugi, VK, Yandex, marketplaces
                    if (settings.bypassRussianServices) {
                        RussianBypassManager.applyOsAppBypass(builder)
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
                SingBoxManager.startSingBox(this@PortalVpnService, selectedRoute)
                MihomoManager.startMihomoAsync(this@PortalVpnService, subUrl, tunFd, pfd)

                // Start in-process native L3 tun2socks router for Telegram and all apps
                Tun2SocksManager.start(this@PortalVpnService, tunFd, "127.0.0.1:7890")

                serviceScope.launch {
                    delay(1200)
                    MihomoManager.selectProxyNode(selectedRoute)
                }

                isRunning = true
                TrafficStatsManager.resetSession()
                VpnWidgetProvider.notifyWidgetUpdate(this@PortalVpnService)
                PortalFailoverWatchdog.start(this@PortalVpnService)
                Log.i(TAG, "Portal VPN Service started successfully (Xray + Mihomo) with Failover Watchdog")
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

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        TrafficStatsManager.resetSession()
        VpnWidgetProvider.notifyWidgetUpdate(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpnSession()
        serviceScope.cancel()
        if (instance == this) {
            instance = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Portal VPN Status"
            val descriptionText = "Уведомления о состоянии квантового туннеля Portal VPN"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, PortalVpnService::class.java).apply {
            action = ACTION_STOP_VPN
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_stat_portal)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stat_portal, "Разорвать портал", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
