package com.multicore.vpn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

enum class PingTargetService(
    val id: String,
    val title: String,
    val iconEmoji: String,
    val testUrl: String
) {
    YOUTUBE("youtube", "YouTube", "▶️", "https://www.youtube.com/generate_204"),
    TELEGRAM("telegram", "Telegram", "✈️", "https://t.me"),
    CHATGPT("chatgpt", "ChatGPT", "🤖", "https://chatgpt.com"),
    INSTAGRAM("instagram", "Instagram", "📸", "https://www.instagram.com"),
    GOOGLE("google", "Google Gemini", "✨", "https://gemini.google.com/")
}

object ServerPingManager {
    private const val TAG = "ServerPingManager"

    // Default test URL (Google / YouTube backbone CDN returning HTTP 204 No Content)
    const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

    suspend fun pingAllAsync(
        routes: List<String>,
        target: PingTargetService = PingTargetService.YOUTUBE
    ): Map<String, Int> = withContext(Dispatchers.IO) {
        val safeRoutes = routes.toList()
        val deferredList = safeRoutes.map { route ->
            async {
                val ping = pingRoute(route, target.testUrl)
                route to ping
            }
        }
        deferredList.awaitAll().toMap()
    }

    suspend fun pingRoute(route: String, testUrl: String = DEFAULT_TEST_URL): Int = withContext(Dispatchers.IO) {
        if (route.isBlank()) return@withContext -1

        // 1. If Mihomo core is running, test real end-to-end proxy delay through the encrypted tunnel to the target service
        val targetNodeName = if (route == "⚡️ Автовыбор сервера") "GLOBAL" else route
        val mihomoDelay = pingViaMihomo(targetNodeName, testUrl)
        if (mihomoDelay > 0) {
            Log.d(TAG, "Mihomo proxy delay ($testUrl) for $route: ${mihomoDelay}ms")
            return@withContext mihomoDelay
        }

        // 2. Fallback: direct TCP socket handshake to node's server and port
        val (host, port) = getHostAndPortForRoute(route)
        if (host.isEmpty() || port <= 0) return@withContext -1

        val startTime = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                MulticoreVpnService.protectSocket(socket)
                socket.connect(InetSocketAddress(host, port), 2500)
                val latency = (System.currentTimeMillis() - startTime).toInt()
                Log.d(TAG, "TCP socket fallback ping for $route ($host:$port): ${latency}ms")
                latency
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ping failed for $route ($host:$port): ${e.message}")
            -1
        }
    }

    private fun pingViaMihomo(nodeName: String, testUrl: String): Int {
        return try {
            val encodedName = URLEncoder.encode(nodeName, "UTF-8").replace("+", "%20")
            val encodedUrl = URLEncoder.encode(testUrl, "UTF-8")
            val url = URL("http://127.0.0.1:9090/proxies/$encodedName/delay?timeout=3500&url=$encodedUrl")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3800
            conn.readTimeout = 3800
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(resp)
                val delay = json.optInt("delay", -1)
                if (delay > 0) delay else -1
            } else {
                -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    fun getHostAndPortForRoute(route: String): Pair<String, Int> {
        val node = SubscriptionParser.getNodeByName(route)
        if (node != null && node.server.isNotBlank()) {
            return Pair(node.server, node.port)
        }
        return Pair("", 0)
    }
}
