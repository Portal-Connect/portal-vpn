package com.multicore.vpn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

data class ServiceItem(
    val id: String,
    val name: String,
    val domain: String,
    val testUrl: String,
    val category: String,
    val iconEmoji: String,
    val brandColorHex: Long
)

enum class ServiceStatus {
    IDLE,
    CHECKING,
    AVAILABLE,
    BLOCKED
}

data class ServiceCheckResult(
    val service: ServiceItem,
    val status: ServiceStatus = ServiceStatus.IDLE,
    val pingMs: Int = -1,
    val httpCode: Int = 0,
    val message: String = "Ожидание сканирования"
)

object ServiceRadarManager {
    private const val TAG = "ServiceRadarManager"

    val DEFAULT_SERVICES = listOf(
        ServiceItem(
            id = "telegram",
            name = "Telegram",
            domain = "telegram.org",
            testUrl = "https://api.telegram.org",
            category = "Мессенджер",
            iconEmoji = "✈️",
            brandColorHex = 0xFF229ED9
        ),
        ServiceItem(
            id = "youtube",
            name = "YouTube",
            domain = "youtube.com",
            testUrl = "https://www.youtube.com/generate_204",
            category = "Видеохостинг",
            iconEmoji = "▶️",
            brandColorHex = 0xFFFF0000
        ),
        ServiceItem(
            id = "instagram",
            name = "Instagram",
            domain = "instagram.com",
            testUrl = "https://www.instagram.com",
            category = "Соцсеть",
            iconEmoji = "📸",
            brandColorHex = 0xFFE1306C
        ),
        ServiceItem(
            id = "gemini",
            name = "Google Gemini",
            domain = "gemini.google.com",
            testUrl = "https://gemini.google.com",
            category = "Нейросеть Google",
            iconEmoji = "✨",
            brandColorHex = 0xFF4E75F8
        ),
        ServiceItem(
            id = "chatgpt",
            name = "ChatGPT",
            domain = "chatgpt.com",
            testUrl = "https://chatgpt.com",
            category = "Нейросеть OpenAI",
            iconEmoji = "🤖",
            brandColorHex = 0xFF10A37F
        ),
        ServiceItem(
            id = "codex_github",
            name = "Codex / GitHub",
            domain = "github.com",
            testUrl = "https://github.com",
            category = "Разработка & AI Код",
            iconEmoji = "💻",
            brandColorHex = 0xFF8B949E
        ),
        ServiceItem(
            id = "twitter_x",
            name = "X (Twitter)",
            domain = "x.com",
            testUrl = "https://x.com",
            category = "Соцсеть",
            iconEmoji = "🐦",
            brandColorHex = 0xFF1DA1F2
        ),
        ServiceItem(
            id = "spotify",
            name = "Spotify",
            domain = "spotify.com",
            testUrl = "https://www.spotify.com",
            category = "Стриминг музыки",
            iconEmoji = "🎵",
            brandColorHex = 0xFF1DB954
        )
    )

    data class SpeedTestResult(
        val speedMbps: Double = 0.0,
        val durationMs: Long = 0,
        val bytesDownloaded: Long = 0,
        val isSuccess: Boolean = false,
        val errorMessage: String = ""
    )

    /**
     * Measures real download speed (Mbps) through the active portal tunnel
     * by streaming a 5MB payload from Cloudflare CDN.
     */
    suspend fun measureDownloadSpeedMbps(
        useProxyOverride: Boolean? = null,
        onProgress: ((Double) -> Unit)? = null
    ): SpeedTestResult = withContext(Dispatchers.IO) {
        val useProxy = useProxyOverride ?: (PortalVpnService.isRunning || isProxyListening())
        val client = buildClient(useProxy)
        // 5 megabytes CDN payload
        val testUrl = "https://speed.cloudflare.com/__down?bytes=5000000"
        val startTime = System.currentTimeMillis()
        var totalBytesRead = 0L

        try {
            val request = Request.Builder()
                .url(testUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0)")
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SpeedTestResult(
                        isSuccess = false,
                        errorMessage = "HTTP ${response.code}"
                    )
                }

                val body = response.body ?: return@withContext SpeedTestResult(
                    isSuccess = false,
                    errorMessage = "Пустое тело ответа"
                )

                val buffer = ByteArray(32768)
                val inputStream = body.byteStream()
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    totalBytesRead += read
                    val currentElapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                    val currentMbps = (totalBytesRead * 8.0) / (currentElapsed / 1000.0) / 1_000_000.0
                    onProgress?.invoke(String.format(java.util.Locale.US, "%.1f", currentMbps).toDoubleOrNull() ?: currentMbps)
                }
            }

            val totalDuration = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
            val mbps = (totalBytesRead * 8.0) / (totalDuration / 1000.0) / 1_000_000.0
            val formattedMbps = (Math.round(mbps * 10.0) / 10.0)

            SpeedTestResult(
                speedMbps = formattedMbps,
                durationMs = totalDuration,
                bytesDownloaded = totalBytesRead,
                isSuccess = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "Speedtest failed: ${e.message}")
            SpeedTestResult(
                isSuccess = false,
                errorMessage = e.message ?: "Ошибка замера скорости"
            )
        }
    }

    private fun isProxyListening(): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", 7890), 250)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildClient(useProxy: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)

        if (useProxy) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7890)))
            builder.dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    return try {
                        okhttp3.Dns.SYSTEM.lookup(hostname)
                    } catch (_: Exception) {
                        // Fallback to synthetic address so OkHttp sends HTTP CONNECT to Mihomo proxy
                        listOf(java.net.InetAddress.getByAddress(hostname, byteArrayOf(198.toByte(), 18, 0, 1)))
                    }
                }
            })
        }

        return builder.build()
    }

    suspend fun checkService(service: ServiceItem, client: OkHttpClient): ServiceCheckResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("${service.testUrl}?_t=${System.currentTimeMillis()}")
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0)")
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                val duration = (System.currentTimeMillis() - startTime).toInt().coerceAtLeast(1)
                val code = response.code
                // Any valid HTTP response indicates connection reached the real target server
                if (code in 200..499) {
                    ServiceCheckResult(
                        service = service,
                        status = ServiceStatus.AVAILABLE,
                        pingMs = duration,
                        httpCode = code,
                        message = "Доступен через портал"
                    )
                } else {
                    ServiceCheckResult(
                        service = service,
                        status = ServiceStatus.BLOCKED,
                        pingMs = duration,
                        httpCode = code,
                        message = "Ошибка ответа HTTP $code"
                    )
                }
            }
        } catch (e: Exception) {
            val duration = (System.currentTimeMillis() - startTime).toInt().coerceAtLeast(1)
            Log.w(TAG, "Radar check failed for ${service.name}: ${e.message}")
            ServiceCheckResult(
                service = service,
                status = ServiceStatus.BLOCKED,
                pingMs = -1,
                httpCode = 0,
                message = "Заблокирован в РФ / Недоступен"
            )
        }
    }

    suspend fun checkAllServices(useProxyOverride: Boolean? = null): Map<String, ServiceCheckResult> = withContext(Dispatchers.IO) {
        val useProxy = useProxyOverride ?: (PortalVpnService.isRunning || isProxyListening())
        val client = buildClient(useProxy)

        val deferredResults = DEFAULT_SERVICES.map { service ->
            async {
                service.id to checkService(service, client)
            }
        }

        deferredResults.awaitAll().toMap()
    }
}
