package io.portal.vpn

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object SingBoxManager {
    private const val TAG = "SingBoxManager"
    private var singBoxProcess: Process? = null

    val isRunning: Boolean
        get() = singBoxProcess?.isAlive == true

    fun startSingBox(context: Context, routeName: String): Boolean {
        stopSingBox()
        val binaryFile = getOrExtractBinary(context)
        if (binaryFile == null || !binaryFile.exists()) {
            Log.e(TAG, "libsingbox.so binary could not be found or extracted")
            return false
        }

        val configFile = File(context.filesDir, "singbox.json")
        val configJson = generateSingBoxConfig(routeName)
        if (configJson.isBlank()) {
            Log.w(TAG, "No valid proxy node configuration for route: $routeName")
            return false
        }
        configFile.writeText(configJson)
        logConfigDump("singbox.json", configJson)

        Log.i(TAG, "Starting sing-box with config: ${configFile.absolutePath} for route: $routeName")
        return try {
            val pb = ProcessBuilder(binaryFile.absolutePath, "run", "-c", configFile.absolutePath)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            singBoxProcess = proc

            java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                try {
                    proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            if (line.contains("error", ignoreCase = true) || line.contains("fatal", ignoreCase = true)) {
                                Log.e("SingBoxCore", line)
                            } else if (line.contains("warn", ignoreCase = true)) {
                                Log.w("SingBoxCore", line)
                            } else {
                                Log.i("SingBoxCore", line)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SingBox Core output stream closed: ${e.message}")
                }
            }

            var healthy = false
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 10_000) {
                if (!proc.isAlive) {
                    val exitCode = try { proc.exitValue() } catch (_: Exception) { -1 }
                    Log.e(TAG, "sing-box process exited prematurely with exit code: $exitCode")
                    return false
                }
                try {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", 2080), 500)
                    socket.close()
                    healthy = true
                    Log.i(TAG, "sing-box bridge (127.0.0.1:2080) responded successfully after ${System.currentTimeMillis() - startTime}ms")
                    break
                } catch (_: Exception) {
                    Thread.sleep(300)
                }
            }

            if (!healthy) {
                Log.e(TAG, "sing-box bridge (127.0.0.1:2080) failed to respond within 10 seconds")
                stopSingBox()
                return false
            }

            Log.i(TAG, "sing-box process started successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sing-box process", e)
            false
        }
    }

    private fun getOrExtractBinary(context: Context): File? {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val nativeFile = File(nativeLibDir, "libsingbox.so")
        if (nativeFile.exists()) {
            nativeFile.setExecutable(true)
            return nativeFile
        }

        val internalFile = File(context.filesDir, "libsingbox.so")
        if (internalFile.exists() && internalFile.length() > 10_000_000) {
            internalFile.setExecutable(true)
            return internalFile
        }

        Log.i(TAG, "Extracting libsingbox.so from APK to ${internalFile.absolutePath}...")
        try {
            val apkPath = context.applicationInfo.sourceDir
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry("lib/arm64-v8a/libsingbox.so")
                    ?: zip.getEntry("lib/arm64/libsingbox.so")
                    ?: zip.getEntry("lib/x86_64/libsingbox.so")
                    ?: zip.getEntry("lib/armeabi-v7a/libsingbox.so")

                if (entry != null) {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(internalFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    internalFile.setExecutable(true)
                    Log.i(TAG, "Extracted libsingbox.so successfully (${internalFile.length()} bytes)")
                    return internalFile
                } else {
                    Log.e(TAG, "libsingbox.so entry not found in APK zip")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract libsingbox.so from APK", e)
        }
        return null
    }

    fun stopSingBox() {
        try {
            singBoxProcess?.destroy()
            singBoxProcess = null
            Log.i(TAG, "sing-box process stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping sing-box process", e)
        }
    }

    private fun logConfigDump(label: String, content: String) {
        Log.i("VpnConfigDump", "=== START $label (${content.length} bytes) ===")
        content.chunked(1000).forEach { chunk ->
            Log.i("VpnConfigDump", chunk)
        }
        Log.i("VpnConfigDump", "=== END $label ===")
    }

    private fun generateSingBoxConfig(routeName: String): String {
        val node = SubscriptionParser.getNodeByName(routeName) ?: return ""
        if (node.server.isBlank()) return ""

        val server = node.server
        val port = node.port
        val uuid = node.uuid
        val host = if (node.host.isNotBlank()) node.host else server
        val path = node.path
        val isReality = node.isReality
        val publicKey = node.publicKey
        val shortId = node.shortId
        val flow = node.flow

        val tlsBlock = if (isReality) {
            """
            "tls": {
              "enabled": true,
              "server_name": "$host",
              "reality": {
                "enabled": true,
                "public_key": "$publicKey",
                "short_id": "$shortId"
              },
              "utls": {
                "enabled": true,
                "fingerprint": "chrome"
              }
            }
            """.trimIndent()
        } else {
            """
            "tls": {
              "enabled": true,
              "server_name": "$host",
              "utls": {
                "enabled": true,
                "fingerprint": "chrome"
              }
            }
            """.trimIndent()
        }

        val transportBlock = if (path.isNotEmpty()) {
            """
            ,"transport": {
              "type": "http",
              "path": "$path",
              "host": ["$host"]
            }
            """.trimIndent()
        } else ""

        val flowVal = flow.ifEmpty { "xtls-rprx-vision" }

        return """
        {
          "log": {
            "level": "info"
          },
          "inbounds": [
            {
              "type": "mixed",
              "tag": "mixed-in",
              "listen": "127.0.0.1",
              "listen_port": 2080
            }
          ],
          "outbounds": [
            {
              "type": "vless",
              "tag": "proxy",
              "server": "$server",
              "server_port": $port,
              "uuid": "$uuid",
              "flow": "$flowVal",
              $tlsBlock
              $transportBlock
            }
          ]
        }
        """.trimIndent()
    }
}
