package io.portal.vpn

import android.content.Context
import android.content.Intent
import android.util.Log
import io.portal.vpn.widget.VpnTileService
import io.portal.vpn.widget.VpnWidgetProvider
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
 * Periodically verifies network health through the currently active portal.
 * If the active portal becomes unresponsive or suffers from excessively high ping (> 380 ms),
 * it automatically scans available portals, identifies the fastest responsive backup portal,
 * and seamlessly switches to it without dropping the overall VPN session.
 */
object PortalFailoverWatchdog {
    private const val TAG = "PortalWatchdog"

    const val ACTION_PORTAL_FAILOVER = "io.portal.vpn.ACTION_PORTAL_FAILOVER"
    const val EXTRA_OLD_ROUTE = "old_route"
    const val EXTRA_NEW_ROUTE = "new_route"
    const val EXTRA_NEW_PING = "new_ping"

    // High ping threshold for autopilot: if ping exceeds 380ms, switch to a faster node
    const val HIGH_PING_THRESHOLD_MS = 380

    // 2 minutes between health checks during continuous connection
    private const val CHECK_INTERVAL_MS = 2 * 60 * 1000L

    // Initial grace period after connection start before first check (20 seconds)
    private const val INITIAL_DELAY_MS = 20 * 1000L

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
            Log.i(TAG, "Portal Failover Watchdog started (interval: 2 min)")
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

    suspend fun performHealthCheckAndFailover(
        context: Context,
        forceFailoverIfDown: Boolean = true,
        currentKnownPing: Int? = null
    ): Boolean {
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
        var ping = currentKnownPing ?: ServerPingManager.pingRoute(currentRoute, PingTargetService.YOUTUBE.testUrl)

        if (ping <= 0) {
            // Confirmation retry in 2 seconds to avoid false alarms during network transitions
            Log.w(TAG, "First ping failed for '$currentRoute'. Verifying with 2s confirmation retry...")
            delay(2000L)
            ping = ServerPingManager.pingRoute(currentRoute, PingTargetService.TELEGRAM.testUrl)
        }

        if (ping in 1..HIGH_PING_THRESHOLD_MS) {
            lastCheckStatus = "В норме (${ping} ms)"
            Log.i(TAG, "Active portal '$currentRoute' is healthy: ${ping}ms")
            return true
        }

        // Active portal is degraded (high ping) or dead/unreachable
        lastCheckStatus = if (ping <= 0) "Сбой связи на '$currentRoute'" else "Высокий пинг ($ping ms) на '$currentRoute'"
        Log.w(TAG, "Portal '$currentRoute' degraded ($ping ms)! Initiating automated failover sequence...")

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

        Log.i(TAG, "Pinging ${candidates.size} backup portals for autopilot failover...")
        val pings = ServerPingManager.pingAllAsync(candidates, PingTargetService.YOUTUBE)

        // Find best working candidate with lowest ping, excluding Russian exit nodes
        val workingCandidates = pings.filter { it.value > 0 }
        val excludedKeywords = listOf("Россия", "Обход")

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
        MihomoManager.selectProxyNode(newRoute, context)

        // 3. Switch SingBox / Xray outbound if running
        if (PortalVpnService.isRunning) {
            SingBoxManager.startSingBox(context, newRoute)
        }

        lastCheckStatus = "Переключен на '$newRoute' (${newPing} ms)"
        Log.i(TAG, "Failover completed successfully! New active portal: '$newRoute'")

        // 4. Update notification, tiles, and widget
        val settings = AppSettings(context)
        val isSingle = settings.appInterfaceMode != AppInterfaceMode.MULTI_PORTAL
        val displayTitle = if (isSingle) "В квантовом портале" else newRoute
        PortalVpnService.updateActiveNotification(displayTitle, "ping: $newPing ms")
        VpnTileService.requestTileUpdate(context)
        VpnWidgetProvider.notifyWidgetUpdate(context)
        HapticFeedbackManager.nodeSelected(context)

        // 5. Broadcast notification to UI
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
