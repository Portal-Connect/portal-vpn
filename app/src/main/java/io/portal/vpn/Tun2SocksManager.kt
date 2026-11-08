package io.portal.vpn

import android.content.Context
import android.util.Log
import go.Seq
import incycore.Incycore
import org.json.JSONArray
import org.json.JSONObject

object Tun2SocksManager {
    private const val TAG = "Tun2SocksManager"
    private var isStarted = false

    val isRunning: Boolean
        get() = try {
            Incycore.isXrayRunning() || Incycore.isTun2SocksRunning()
        } catch (e: Throwable) {
            isStarted
        }

    fun start(context: Context, tunFd: Int, proxyAddress: String = "127.0.0.1:7890"): Boolean {
        stop()
        Seq.touch()
        Seq.setContext(context.applicationContext)

        // Attempt 1: startTun2Socks(long fd, String proxy)
        try {
            Log.i(TAG, "Attempting startTun2Socks with tunFd=$tunFd, proxy=$proxyAddress...")
            Incycore.startTun2Socks(tunFd.toLong(), proxyAddress)
            isStarted = true
            val running = Incycore.isTun2SocksRunning()
            Log.i(TAG, "startTun2Socks returned successfully! isTun2SocksRunning=$running")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "startTun2Socks failed: ${e.message}, falling back to startXrayWithTunFd...")
        }

        // Attempt 2: startXrayWithTunFd(configJson, tunFd)
        return try {
            val host = proxyAddress.substringBefore(':')
            val port = proxyAddress.substringAfter(':').toIntOrNull() ?: 7890

            val config = JSONObject().apply {
                put("log", JSONObject().apply {
                    put("loglevel", "warning")
                })
                put("inbounds", JSONArray().apply {
                    put(JSONObject().apply {
                        put("tag", "incy-native-tun")
                        put("protocol", "tun")
                        put("settings", JSONObject().apply {
                            put("name", "portal0")
                            put("mtu", 1500)
                            put("gateway", JSONArray().apply {
                                put("198.18.0.1/16")
                            })
                            put("address", "198.18.0.2")
                        })
                        put("sniffing", JSONObject().apply {
                            put("enabled", true)
                            put("destOverride", JSONArray().apply {
                                put("http")
                                put("tls")
                            })
                        })
                    })
                })
                put("outbounds", JSONArray().apply {
                    put(JSONObject().apply {
                        put("tag", "proxy")
                        put("protocol", "socks")
                        put("settings", JSONObject().apply {
                            put("servers", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("address", host)
                                    put("port", port)
                                })
                            })
                        })
                    })
                })
                put("routing", JSONObject().apply {
                    put("domainStrategy", "AsIs")
                    put("rules", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "field")
                            put("inboundTag", JSONArray().apply {
                                put("incy-native-tun")
                            })
                            put("outboundTag", "proxy")
                        })
                    })
                })
            }

            Log.i(TAG, "Starting Xray TUN bridge with tunFd=$tunFd -> SOCKS5 $host:$port")
            Incycore.startXrayWithTunFd(config.toString(), tunFd)
            isStarted = true
            val running = Incycore.isXrayRunning()
            Log.i(TAG, "Xray TUN bridge started! isXrayRunning=$running")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start Xray TUN bridge", e)
            false
        }
    }

    fun stop() {
        if (isStarted) {
            try {
                Log.i(TAG, "Stopping native TUN bridge...")
                try { Incycore.stopTun2Socks() } catch (_: Throwable) {}
                try { Incycore.stopXray() } catch (_: Throwable) {}
                Log.i(TAG, "Native TUN bridge stopped")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to stop native TUN bridge", e)
            } finally {
                isStarted = false
            }
        }
    }
}
