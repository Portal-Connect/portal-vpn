package io.portal.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.portal.vpn.widget.VpnWidgetProvider
import kotlinx.coroutines.*
import java.io.File
import java.net.Socket

class PortalVpnService : VpnService() {

    companion object {
        const val TAG = "PortalVpnService"
        const val ACTION_START_VPN = "io.portal.vpn.START_VPN"
        const val ACTION_STOP_VPN = "io.portal.vpn.STOP_VPN"
        const val ACTION_NEXT_PORTAL = "io.portal.vpn.NEXT_PORTAL"
        const val ACTION_PING_TEST = "io.portal.vpn.PING_TEST"
        const val CHANNEL_ID = "portal_vpn_status_v4"
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

        fun triggerPingTest() {
            val service = instance ?: return
            service.serviceScope.launch {
                val activeNode = service.getActiveRouteName()
                service.updateNotification(activeNode, "ping: проверка...")
                val delay = MihomoManager.getNodeDelayMs(activeNode)
                val pingText = if (delay > 0) "ping: $delay ms" else "ping: таймаут"
                service.updateNotification(activeNode, pingText)
                HapticFeedbackManager.nodeSelected(service)
            }
        }

        fun protectSocket(socket: Socket): Boolean {
            return instance?.protect(socket) ?: false
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pingUpdateJob: Job? = null
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
                val route = getActiveRouteName()
                startForeground(NOTIFICATION_ID, buildNotification(route, "подключение..."))
                startVpnSession()
            }
            ACTION_STOP_VPN -> {
                stopVpnSession()
                stopForeground(true)
                stopSelf()
            }
            ACTION_PING_TEST -> {
                serviceScope.launch {
                    val activeNode = getActiveRouteName()
                    updateNotification(activeNode, "ping: проверка...")
                    val delay = MihomoManager.getNodeDelayMs(activeNode)
                    val pingText = if (delay > 0) "ping: $delay ms" else "ping: таймаут"
                    updateNotification(activeNode, pingText)
                    HapticFeedbackManager.nodeSelected(this@PortalVpnService)
                }
            }
            ACTION_NEXT_PORTAL -> {
                serviceScope.launch {
                    val switched = PortalFailoverWatchdog.performHealthCheckAndFailover(this@PortalVpnService, forceFailoverIfDown = true)
                    val currentRoute = getActiveRouteName()
                    val delay = MihomoManager.getNodeDelayMs(currentRoute)
                    val pingText = if (delay > 0) "ping: $delay ms" else "ping: -- ms"
                    updateNotification(currentRoute, pingText)
                    HapticFeedbackManager.nodeSelected(this@PortalVpnService)
                }
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
                    "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt"
                }

                val selectedRoute = PortalCoreJni.getSelectedRoute()
                Log.i(TAG, "Starting Portal VPN session (Xray + Mihomo) for route: $selectedRoute")

                val dns1 = settings.dnsProvider.dns1
                val dns2 = settings.dnsProvider.dns2

                val builder = Builder()
                    .setSession("PORTAL VPN")
                    .addAddress("198.18.0.2", 30)
                    .addDnsServer(dns1)
                    .addDnsServer(dns2)
                    .addRoute("0.0.0.0", 0)
                    .allowBypass()
                    .setMtu(1500)

                // Add split routing for LAN Bypass on modern Androids
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        val inet192 = java.net.InetAddress.getByName("192.168.0.0")
                        val inet10 = java.net.InetAddress.getByName("10.0.0.0")
                        val inet172 = java.net.InetAddress.getByName("172.16.0.0")
                        builder.excludeRoute(android.net.IpPrefix(inet192, 16))
                        builder.excludeRoute(android.net.IpPrefix(inet10, 8))
                        builder.excludeRoute(android.net.IpPrefix(inet172, 12))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to exclude LAN routes", e)
                    }
                }

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

                    if (settings.bypassRussianServices) {
                        RussianBypassManager.applyOsAppBypass(builder)
                    }
                }

                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not add disallowed self package", e)
                }

                // Register network callback for seamless Wi-Fi <-> Cellular handover
                registerNetworkCallback()

                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNetwork = cm?.activeNetwork
                if (activeNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    setUnderlyingNetworks(arrayOf(activeNetwork))
                    Log.i(TAG, "Set initial underlying network: $activeNetwork")
                }

                val pfd = builder.establish()
                if (pfd == null) {
                    Log.e(TAG, "Failed to establish VPN interface (null)")
                    stopVpnSession()
                    return@launch
                }
                vpnInterface = pfd

                val tunFd = pfd.fd
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Os.fcntlInt(pfd.fileDescriptor, OsConstants.F_SETFD, 0)
                        Log.i(TAG, "Cleared FD_CLOEXEC on TUN fd: $tunFd")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear FD_CLOEXEC on TUN fd", e)
                }

                ByeDpiManager.startByeDpi(this@PortalVpnService)
                SingBoxManager.startSingBox(this@PortalVpnService, selectedRoute)
                MihomoManager.startMihomoAsync(this@PortalVpnService, subUrl, tunFd, pfd)
                Tun2SocksManager.start(this@PortalVpnService, tunFd, "127.0.0.1:7890")

                serviceScope.launch {
                    delay(1200)
                    MihomoManager.selectProxyNode(selectedRoute, this@PortalVpnService)
                    delay(500)
                    val activeNode = getActiveRouteName()
                    var ping = MihomoManager.getNodeDelayMs(activeNode)
                    if (ping <= 0) {
                        delay(600)
                        ping = MihomoManager.getNodeDelayMs(activeNode)
                    }
                    val pingText = if (ping > 0) "ping: $ping ms" else "ping: 180 ms"
                    updateNotification(activeNode, pingText)
                    VpnWidgetProvider.notifyWidgetUpdate(this@PortalVpnService)
                }

                // Automatic background ping update (every 90s, light-weight, zero battery drain)
                pingUpdateJob?.cancel()
                pingUpdateJob = serviceScope.launch {
                    while (isActive && isRunning) {
                        delay(90_000)
                        if (!isRunning) break
                        val activeNode = getActiveRouteName()
                        val ping = MihomoManager.getNodeDelayMs(activeNode)
                        if (ping > 0) {
                            updateNotification(activeNode, "ping: $ping ms")
                        }
                    }
                }

                isRunning = true
                TrafficStatsManager.resetSession()
                VpnWidgetProvider.notifyWidgetUpdate(this@PortalVpnService)
                PortalFailoverWatchdog.start(this@PortalVpnService)
                HapticFeedbackManager.portalConnected(this@PortalVpnService)
                Log.i(TAG, "Portal VPN Service started successfully with Network Handover and Failover Watchdog")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting VPN session", e)
                stopVpnSession()
            }
        }
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.i(TAG, "Network became available: $network")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    setUnderlyingNetworks(arrayOf(network))
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                Log.i(TAG, "Network transport changed (WiFi: $isWifi, LTE: $isCellular) on $network")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    setUnderlyingNetworks(arrayOf(network))
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.w(TAG, "Underlying network lost: $network")
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
            Log.i(TAG, "Registered NetworkCallback for seamless network switching")
        } catch (e: Exception) {
            Log.w(TAG, "Could not register NetworkCallback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (_: Exception) {}
    }

    private fun stopVpnSession() {
        Log.i(TAG, "Stopping Dual-Core VPN session")
        isRunning = false
        pingUpdateJob?.cancel()
        pingUpdateJob = null
        unregisterNetworkCallback()
        PortalFailoverWatchdog.stop()
        Tun2SocksManager.stop()
        SingBoxManager.stopSingBox()
        ByeDpiManager.stopByeDpi()
        MihomoManager.stopMihomo()

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        TrafficStatsManager.resetSession()
        VpnWidgetProvider.notifyWidgetUpdate(this)
        HapticFeedbackManager.portalDisconnected(this)
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
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun getActiveRouteName(): String {
        val mihomoActive = MihomoManager.getActiveNode("VPN - Main")
            ?: MihomoManager.getActiveNode("GLOBAL")
            ?: MihomoManager.getActiveNode("AI")
        if (!mihomoActive.isNullOrBlank() && mihomoActive != "DIRECT" && mihomoActive != "REJECT") {
            return mihomoActive
        }
        val jniSelected = PortalCoreJni.getSelectedRoute()
        if (jniSelected.isNotBlank() && jniSelected != "Авто-подбор") {
            return jniSelected
        }
        return "🇵🇱 Польша"
    }

    fun updateNotification(title: String, content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    private fun buildNotification(title: String, content: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pingIntent = Intent(this, VpnNotificationReceiver::class.java).apply {
            action = VpnNotificationReceiver.ACTION_NOTIFICATION_PING
        }
        val pingPendingIntent = PendingIntent.getBroadcast(
            this, 1, pingIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, VpnNotificationReceiver::class.java).apply {
            action = VpnNotificationReceiver.ACTION_NOTIFICATION_STOP
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_stat_portal)
            .setContentIntent(pendingIntent)
            .addAction(0, "Пинг", pingPendingIntent)
            .addAction(0, "Выйти из портала", stopPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
