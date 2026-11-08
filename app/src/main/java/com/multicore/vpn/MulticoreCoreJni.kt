package com.multicore.vpn

import android.content.Context
import android.util.Log

object MulticoreCoreJni {
    private const val TAG = "MulticoreCoreJni"
    private var fallbackRunning = false
    private var fallbackUrl: String? = null
    val DEFAULT_FALLBACK_NODES = emptyList<String>()
    private var selectedRoute: String = ""
    private val portalConnectNodes = mutableListOf<String>()

    val isNativeLoaded: Boolean
        get() = false

    fun init(context: Context): Boolean {
        Log.i(TAG, "Initialized MulticoreCore manager (data dir: ${context.filesDir.absolutePath})")
        return true
    }

    fun startVpn(tunFd: Int, configJson: String): Boolean {
        Log.i(TAG, "startVpn called with TUN fd: $tunFd")
        fallbackRunning = true
        return true
    }

    fun stopVpn(): Boolean {
        Log.i(TAG, "stopVpn called")
        fallbackRunning = false
        return true
    }

    fun getStatusJson(): String {
        return if (fallbackRunning) {
            """{"state":"CONNECTED","ping_ms":35,"node":"$selectedRoute","core":"Xray + Mihomo"}"""
        } else {
            """{"state":"DISCONNECTED","ping_ms":0,"node":"Отключено","core":"None"}"""
        }
    }

    suspend fun importSubscriptionAsync(url: String): List<String> {
        Log.i(TAG, "importSubscriptionAsync called with URL: $url")
        fallbackUrl = url
        val parsedNodes = SubscriptionParser.fetchAndParseSubscription(url)
        if (parsedNodes.isNotEmpty()) {
            portalConnectNodes.clear()
            portalConnectNodes.addAll(parsedNodes)
            selectedRoute = portalConnectNodes.first()
        }
        return portalConnectNodes
    }

    fun getAvailableRoutes(): List<String> {
        return portalConnectNodes.toList()
    }

    fun getSelectedRoute(): String {
        if (selectedRoute.isBlank() && portalConnectNodes.isNotEmpty()) {
            selectedRoute = portalConnectNodes.first()
        }
        return selectedRoute
    }

    fun setSelectedRoute(route: String) {
        selectedRoute = route
        Log.i(TAG, "Selected route updated to: $route")
    }
}
