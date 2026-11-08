package com.multicore.vpn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit

object IpCheckManager {
    private const val TAG = "IpCheckManager"

    private fun isProxyListening(): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", 7890), 300)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun createCleanClient(useProxy: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS)) // Zero connection pooling ensures fresh IP per node
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)

        if (useProxy) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7890)))
        }

        return builder.build()
    }

    suspend fun checkPublicIpAsync(): IpCheckResult = withContext(Dispatchers.IO) {
        val useProxy = PortalVpnService.isRunning || isProxyListening()
        val client = createCleanClient(useProxy)

        // Primary: ipwho.is
        try {
            val request = Request.Builder()
                .url("https://ipwho.is/?nocache=${System.currentTimeMillis()}&rnd=${(1000..9999).random()}")
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0)")
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    if (json.optBoolean("success", true)) {
                        val ip = json.optString("ip", "")
                        val country = json.optString("country", "Неизвестно")
                        val countryCode = json.optString("country_code", "")
                        val city = json.optString("city", "")
                        val connObj = json.optJSONObject("connection")
                        val org = connObj?.optString("org", "") ?: json.optString("org", "")
                        val flagEmoji = getFlagEmoji(countryCode)
                        val isProtected = countryCode.isNotBlank() && !countryCode.equals("RU", ignoreCase = true)
                        if (ip.isNotBlank()) {
                            Log.i(TAG, "IP check success via ipwho.is: $ip ($country, $city) [proxy=$useProxy]")
                            return@withContext IpCheckResult(
                                ip = ip,
                                country = country,
                                countryCode = countryCode,
                                city = city,
                                org = org,
                                flagEmoji = flagEmoji,
                                isProtected = isProtected
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "IP check primary (ipwho.is) failed: ${e.message}")
        }

        // Secondary fallback: api.ip.sb/geoip
        try {
            val request = Request.Builder()
                .url("https://api.ip.sb/geoip?_=${System.currentTimeMillis()}")
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0)")
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val ip = json.optString("ip", "")
                    val country = json.optString("country", "Защищённый узел")
                    val countryCode = json.optString("country_code", "")
                    val city = json.optString("city", "")
                    val org = json.optString("organization", "")
                    val flagEmoji = getFlagEmoji(countryCode)
                    val isProtected = countryCode.isNotBlank() && !countryCode.equals("RU", ignoreCase = true)
                    if (ip.isNotBlank()) {
                        Log.i(TAG, "IP check success via ip.sb: $ip ($country, $city) [proxy=$useProxy]")
                        return@withContext IpCheckResult(
                            ip = ip,
                            country = country,
                            countryCode = countryCode,
                            city = city,
                            org = org,
                            flagEmoji = flagEmoji,
                            isProtected = isProtected
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "IP check fallback (ip.sb) failed: ${e.message}")
        }

        // Tertiary fallback: ipify
        try {
            val request = Request.Builder()
                .url("https://api.ipify.org?format=json&_=${System.currentTimeMillis()}")
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val ip = json.optString("ip", "—")
                    return@withContext IpCheckResult(ip = ip, country = "Защищённый узел", flagEmoji = "🔒", isProtected = true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "IP check fallback (ipify) failed: ${e.message}")
        }

        IpCheckResult()
    }

    private fun getFlagEmoji(countryCode: String): String {
        if (countryCode.length != 2) return "🌐"
        val upper = countryCode.uppercase(Locale.ROOT)
        val firstChar = Character.codePointAt(upper, 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(upper, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }
}
