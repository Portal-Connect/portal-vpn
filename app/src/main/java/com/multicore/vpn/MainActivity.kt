package com.multicore.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.multicore.vpn.ui.MainScreen
import com.multicore.vpn.ui.AppSplitTunnelingActivity
import com.multicore.vpn.ui.theme.MulticoreTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settings: AppSettings
    private var isConnected by mutableStateOf(false)
    private var selectedRoute by mutableStateOf(PortalCoreJni.getSelectedRoute())
    private var showRouteBottomSheet by mutableStateOf(false)
    private var isPinging by mutableStateOf(false)
    private var selectedPingTarget by mutableStateOf(PingTargetService.YOUTUBE)
    private var themeRecomposeTrigger by mutableIntStateOf(0)
    private var trafficData by mutableStateOf(TrafficStatsData())
    private val speedHistory = mutableStateListOf(10f, 25f, 40f, 65f, 80f, 120f, 90f, 110f, 150f)
    private var ipCheckResult by mutableStateOf(IpCheckResult())
    private val availableRoutes = mutableStateListOf<String>()
    private val pingsMap = mutableStateMapOf<String, Int>()
    private var radarResults by mutableStateOf<Map<String, ServiceCheckResult>>(emptyMap())
    private var isRadarScanning by mutableStateOf(false)

    // Update state
    private var updateInfo by mutableStateOf<UpdateInfo?>(null)
    private var isCheckingUpdate by mutableStateOf(false)
    private var isDownloadingUpdate by mutableStateOf(false)
    private var updateDownloadProgress by mutableIntStateOf(0)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "Разрешение на VPN отклонено", Toast.LENGTH_SHORT).show()
        }
    }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scannedUrl = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_RESULT)
            if (!scannedUrl.isNullOrBlank()) {
                handleImportSubscription(scannedUrl)
            }
        }
    }

    private val failoverReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PortalFailoverWatchdog.ACTION_PORTAL_FAILOVER) {
                val oldRoute = intent.getStringExtra(PortalFailoverWatchdog.EXTRA_OLD_ROUTE) ?: ""
                val newRoute = intent.getStringExtra(PortalFailoverWatchdog.EXTRA_NEW_ROUTE) ?: ""
                val ping = intent.getIntExtra(PortalFailoverWatchdog.EXTRA_NEW_PING, -1)
                Log.i("MainActivity", "Received portal failover: $oldRoute -> $newRoute ($ping ms)")
                if (newRoute.isNotBlank()) {
                    selectedRoute = newRoute
                    val pingText = if (ping > 0) " (${ping}ms)" else ""
                    Toast.makeText(
                        this@MainActivity,
                        "⚡️ Сбой на '$oldRoute': портал переключен на '$newRoute'$pingText",
                        Toast.LENGTH_LONG
                    ).show()
                    lifecycleScope.launch {
                        delay(1200)
                        triggerIpCheck()
                        triggerRadarScan()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        PortalCoreJni.init(this)

        val failoverFilter = android.content.IntentFilter(PortalFailoverWatchdog.ACTION_PORTAL_FAILOVER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(failoverReceiver, failoverFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(failoverReceiver, failoverFilter)
        }
        if (settings.lastSubscriptionUrl.isNotBlank()) {
            lifecycleScope.launch {
                PortalCoreJni.importSubscriptionAsync(settings.lastSubscriptionUrl)
                SubscriptionParser.saveLastConfigToFile(java.io.File(filesDir, "config.yaml"))
                refreshRoutes()
                selectedRoute = PortalCoreJni.getSelectedRoute()
                triggerPingAll()
            }
        }
        refreshRoutes()
        triggerPingAll()
        startTrafficMonitor()

        // Background check for update on app startup
        checkForUpdates(showToastIfLatest = false)

        setContent {
            themeRecomposeTrigger.let {}
            MulticoreTheme(
                themeMode = settings.themeMode,
                accentOption = settings.accentColor
            ) {
                MainScreen(
                    isConnected = isConnected,
                    selectedRoute = selectedRoute,
                    availableRoutes = availableRoutes,
                    pingsMap = pingsMap,
                    isPinging = isPinging,
                    selectedPingTarget = selectedPingTarget,
                    trafficData = trafficData,
                    speedHistory = speedHistory,
                    ipCheckResult = ipCheckResult,
                    settings = settings,
                    showRouteBottomSheet = showRouteBottomSheet,
                    radarResults = radarResults,
                    isRadarScanning = isRadarScanning,
                    updateInfo = updateInfo,
                    isDownloadingUpdate = isDownloadingUpdate,
                    updateDownloadProgress = updateDownloadProgress,
                    currentVersion = BuildConfig.VERSION_NAME,
                    isCheckingUpdate = isCheckingUpdate,
                    onCheckUpdate = {
                        checkForUpdates(showToastIfLatest = true)
                    },
                    onStartUpdateDownload = {
                        startUpdateDownload()
                    },
                    onDismissUpdateDialog = {
                        updateInfo = null
                    },
                    onToggleConnection = {
                        if (availableRoutes.isEmpty()) {
                            Toast.makeText(this, "Сначала добавьте сеть порталов", Toast.LENGTH_SHORT).show()
                            showRouteBottomSheet = true
                        } else if (isConnected) {
                            stopVpnService()
                        } else {
                            checkAndStartVpn()
                        }
                    },
                    onSelectRoute = { newRoute ->
                        PortalCoreJni.setSelectedRoute(newRoute)
                        selectedRoute = newRoute
                        ipCheckResult = IpCheckResult(ip = "Сканирование...", country = "Смена портала...", isProtected = true)
                        MihomoManager.selectProxyNode(newRoute)
                        if (PortalVpnService.isRunning) {
                            SingBoxManager.startSingBox(this, newRoute)
                        }
                        Toast.makeText(this, "Портал настроен: $newRoute", Toast.LENGTH_SHORT).show()
                        if (isConnected) {
                            ipCheckResult = IpCheckResult(ip = "", country = "Телепортация...")
                            lifecycleScope.launch {
                                delay(1500)
                                triggerIpCheck()
                                triggerRadarScan()
                            }
                        }
                    },
                    onImportSubscription = { url ->
                        handleImportSubscription(url)
                    },
                    onOpenQrScanner = {
                        val intent = Intent(this, QrScannerActivity::class.java)
                        qrScannerLauncher.launch(intent)
                    },
                    onDismissBottomSheet = {
                        showRouteBottomSheet = false
                    },
                    onOpenBottomSheet = {
                        showRouteBottomSheet = true
                    },
                    onPingAllServers = { target ->
                        selectedPingTarget = target
                        triggerPingAll(target)
                    },
                    onRefreshIpCheck = {
                        Toast.makeText(this, "Сканирование матрицы...", Toast.LENGTH_SHORT).show()
                        ipCheckResult = IpCheckResult(ip = "", country = "Сканирование координат...")
                        triggerIpCheck()
                    },
                    onSettingsChanged = {
                        themeRecomposeTrigger++
                    },
                    onOpenSplitTunneling = {
                        val intent = Intent(this, AppSplitTunnelingActivity::class.java)
                        startActivity(intent)
                    },
                    onToggleFavorite = { route ->
                        val currentFavs = settings.favoriteRoutes.toMutableSet()
                        if (currentFavs.contains(route)) {
                            currentFavs.remove(route)
                        } else {
                            currentFavs.add(route)
                        }
                        settings.favoriteRoutes = currentFavs
                        themeRecomposeTrigger++
                    },
                    onStartRadarScan = {
                        triggerRadarScan()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isConnected = PortalVpnService.isRunning
        refreshRoutes()
        selectedRoute = PortalCoreJni.getSelectedRoute()
        themeRecomposeTrigger++
        triggerPingAll()
        if (isConnected) {
            triggerIpCheck()
        }
    }

    private fun checkForUpdates(showToastIfLatest: Boolean) {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        lifecycleScope.launch {
            val result = PortalUpdateManager.checkForUpdates(BuildConfig.VERSION_NAME)
            isCheckingUpdate = false
            if (result.hasUpdate) {
                updateInfo = result
            } else if (showToastIfLatest) {
                Toast.makeText(this@MainActivity, "У вас установлена последняя версия (${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startUpdateDownload() {
        val info = updateInfo ?: return
        if (isDownloadingUpdate) return
        isDownloadingUpdate = true
        updateDownloadProgress = 0
        lifecycleScope.launch {
            val success = PortalUpdateManager.downloadAndInstallApk(this@MainActivity, info.downloadUrl) { progress ->
                updateDownloadProgress = progress
            }
            isDownloadingUpdate = false
            if (!success) {
                Toast.makeText(this@MainActivity, "Не удалось скачать обновление. Проверьте сеть", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startTrafficMonitor() {
        lifecycleScope.launch {
            while (isActive) {
                if (isConnected) {
                    val stats = TrafficStatsManager.getLiveStats()
                    trafficData = stats
                    val downKbps = stats.downloadSpeedBps / 1024f
                    speedHistory.add(downKbps)
                    if (speedHistory.size > 20) {
                        speedHistory.removeAt(0)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun triggerIpCheck() {
        lifecycleScope.launch {
            val result = IpCheckManager.checkPublicIpAsync()
            ipCheckResult = if (result.ip.isNotBlank() && result.ip != "—") {
                result
            } else {
                if (PortalVpnService.isRunning) {
                    IpCheckResult(ip = "Сбой узла", country = "Маршрут недоступен", isProtected = false)
                } else {
                    IpCheckResult()
                }
            }
        }
    }

    private fun handleImportSubscription(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        Toast.makeText(this, "Синхронизация сети порталов...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val routes = PortalCoreJni.importSubscriptionAsync(trimmed)
            SubscriptionParser.saveLastConfigToFile(java.io.File(filesDir, "config.yaml"))
            refreshRoutes()
            selectedRoute = PortalCoreJni.getSelectedRoute()
            val count = routes.size
            if (count > 0) {
                settings.lastSubscriptionUrl = trimmed
                Toast.makeText(this@MainActivity, "Загружено порталов: $count", Toast.LENGTH_SHORT).show()
                showRouteBottomSheet = true
            } else {
                Toast.makeText(this@MainActivity, "Не удалось загрузить порталы по ссылке", Toast.LENGTH_SHORT).show()
            }
            triggerPingAll()
        }
    }

    private fun refreshRoutes() {
        availableRoutes.clear()
        val routes = PortalCoreJni.getAvailableRoutes()
        availableRoutes.addAll(routes)
        if (routes.isNotEmpty() && selectedRoute.isBlank()) {
            selectedRoute = PortalCoreJni.getSelectedRoute()
        }
    }

    private fun triggerPingAll(target: PingTargetService = selectedPingTarget) {
        if (isPinging || availableRoutes.isEmpty()) return
        isPinging = true
        lifecycleScope.launch {
            val routesCopy = availableRoutes.toList()
            val results = ServerPingManager.pingAllAsync(routesCopy, target)
            pingsMap.putAll(results)
            isPinging = false
        }
    }

    private fun triggerRadarScan() {
        if (isRadarScanning) return
        isRadarScanning = true
        lifecycleScope.launch {
            radarResults = ServiceRadarManager.DEFAULT_SERVICES.associate {
                it.id to ServiceCheckResult(it, status = ServiceStatus.CHECKING)
            }
            val results = ServiceRadarManager.checkAllServices()
            radarResults = results
            isRadarScanning = false
        }
    }

    private fun checkAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        PortalVpnService.start(this)
        isConnected = true
        TrafficStatsManager.resetSession()
        ipCheckResult = IpCheckResult(ip = "", country = "Активация портала...")
        lifecycleScope.launch {
            delay(1800)
            triggerIpCheck()
            triggerRadarScan()
        }
    }

    private fun stopVpnService() {
        PortalVpnService.stop(this)
        isConnected = false
        ipCheckResult = IpCheckResult()
        lifecycleScope.launch {
            triggerRadarScan()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(failoverReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
