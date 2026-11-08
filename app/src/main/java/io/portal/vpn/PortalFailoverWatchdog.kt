package io.portal.vpn

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background health watchdog for active VPN portals.
 *
 * Periodically verifies network health to Telegram (or chosen service) through the currently active portal.
 * If the active portal becomes unresponsive (ping fails twice consecutively), it automatically scans available
 * portals, identifies the fastest responsive backup portal, and seamlessly switches to it without interrupting
 * the overall VPN session.
 */
object PortalFailoverWatchdog {
    private const val TAG = "PortalWatchdog"

    const val ACTION_PORTAL_FAILOVER = "io.portal.vpn.ACTION_PORTAL_FAILOVER"
    const val EXTRA_OLD_ROUTE = "old_route"
    const val EXTRA_NEW_ROUTE = "new_route"
    const val EXTRA_NEW_PING = "new_ping"

    // 10 minutes between health checks as requested by user
    private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L

    // Initial grace period after connection start before first check (30 seconds)
    private const val INITIAL_DELAY_MS = 30 * 1000L

    private val watchdogScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null

    @Volatile
    var lastHealthCheckTime: Long = 0L
        private set

    @Volatile
    var lastCheckStatus: String = "Ожидание"
        private set

    fun start(context: Context) {
        stop()
        watchdogJob = watchdogScope.launch {
            Log.i(TAG, "Portal Failover Watchdog started (interval: 10 min, target: Telegram)")
            lastCheckStatus = "Запуск мониторинга..."
            delay(INITIAL_DELAY_MS)

            while (isActive && PortalVpnService.isRunning) {
                performHealthCheckAndFailover(context)
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        lastCheckStatus = "Остановлен"
        Log.i(TAG, "Portal Failover Watchdog stopped")
    }

    suspend fun performHealthCheckAndFailover(context: Context, forceFailoverIfDown: Boolean = true): Boolean {
        val settings = AppSettings(context)
        if (!settings.autoFailoverEnabled) {
            Log.d(TAG, "Auto-failover disabled in settings, skipping check")
            lastCheckStatus = "Отключено в настройках"
            return true
        }

        val currentRoute = PortalCoreJni.getSelectedRoute()
        if (currentRoute.isBlank()) {
            lastCheckStatus = "Портал не выбран"
            return true
        }

        val availableRoutes = PortalCoreJni.getAvailableRoutes()
        if (availableRoutes.size <= 1) {
            Log.d(TAG, "Only 1 portal available, failover not possible")
            lastCheckStatus = "Единственный портал"
            return true
        }

        lastHealthCheckTime = System.currentTimeMillis()
        Log.i(TAG, "Checking health of active portal '$currentRoute' to Telegram...")

        // Primary health check
        var ping = ServerPingManager.pingRoute(currentRoute, PingTargetService.TELEGRAM.testUrl)

        if (ping <= 0) {
            // Transient jitter check: retry in 4 seconds to prevent false alarms during Wi-Fi / LTE handoffs
            Log.w(TAG, "First ping failed for '$currentRoute'. Verifying with 4s confirmation retry...")
            delay(4000L)
            ping = ServerPingManager.pingRoute(currentRoute, PingTargetService.TELEGRAM.testUrl)
        }

        if (ping > 0) {
            lastCheckStatus = "В норме (${ping} ms к Telegram)"
            Log.i(TAG, "Active portal '$currentRoute' is healthy: ${ping}ms to Telegram")
            return true
        }

        // Active portal is confirmed dead/unreachable
        lastCheckStatus = "Сбой связи на '$currentRoute'"
        Log.e(TAG, "Portal '$currentRoute' unreachable! Initiating automated failover sequence...")

        if (!forceFailoverIfDown) {
            return false
        }

        return executeFailover(context, currentRoute, availableRoutes)
    }

    private suspend fun executeFailover(
        context: Context,
        failedRoute: String,
        allRoutes: List<String>
    ): Boolean {
        // Filter out the failed node and any auto-select dummy names
        val candidates = allRoutes.filter { it != failedRoute && it != "⚡️ Автовыбор сервера" }
        if (candidates.isEmpty()) {
            Log.w(TAG, "No candidate backup portals found for failover")
            lastCheckStatus = "Нет резервных порталов"
            return false
        }

        Log.i(TAG, "Pinging ${candidates.size} backup portals for Telegram failover...")
        val pings = ServerPingManager.pingAllAsync(candidates, PingTargetService.TELEGRAM)

        // Find best working candidate with lowest ping, excluding restricted nodes
        val workingCandidates = pings.filter { it.value > 0 }
        val excludedKeywords = listOf("Россия", "Обход", "Эстония")

        val bestRouteEntry = workingCandidates.filter { entry ->
            excludedKeywords.none { entry.key.contains(it, ignoreCase = true) }
        }.minByOrNull { it.value }
            ?: workingCandidates.minByOrNull { it.value }

        if (bestRouteEntry == null) {
            Log.e(TAG, "Failover scan completed: all backup portals unreachable")
            lastCheckStatus = "Все резервы недоступны"
            return false
        }

        val newRoute = bestRouteEntry.key
        val newPing = bestRouteEntry.value
        Log.i(TAG, "Failover target identified: '$newRoute' (${newPing}ms). Switching cores...")

        // 1. Update global selected route in JNI
        PortalCoreJni.setSelectedRoute(newRoute)

        // 2. Switch Mihomo proxy node
        MihomoManager.selectProxyNode(newRoute)

        // 3. Switch SingBox / Xray outbound if running
        if (PortalVpnService.isRunning) {
            SingBoxManager.startSingBox(context, newRoute)
        }

        lastCheckStatus = "Переключен на '$newRoute' (${newPing} ms)"
        Log.i(TAG, "Failover completed successfully! New active portal: '$newRoute'")

        // 4. Broadcast notification to UI
        val failoverIntent = Intent(ACTION_PORTAL_FAILOVER).apply {
            putExtra(EXTRA_OLD_ROUTE, failedRoute)
            putExtra(EXTRA_NEW_ROUTE, newRoute)
            putExtra(EXTRA_NEW_PING, newPing)
            setPackage(context.packageName)
        }
        context.sendBroadcast(failoverIntent)
        return true
    }
}
