package io.portal.vpn

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipFile

object MihomoManager {
    private const val TAG = "MihomoManager"
    @Volatile
    private var mihomoProcess: Process? = null

    val isRunning: Boolean
        get() = mihomoProcess?.isAlive == true

    fun selectProxyNode(nodeName: String, context: Context? = null) {
        MihomoApiClient.selectProxyNode(nodeName, context)
    }

    fun setYouTubeRouting(context: Context, useByeDpi: Boolean) {
        MihomoApiClient.setYouTubeRouting(context, useByeDpi)
    }

    fun getNodeDelayMs(nodeName: String, testUrl: String = "http%3A%2F%2Fcp.cloudflare.com%2Fgenerate_204"): Int {
        return MihomoApiClient.getNodeDelayMs(nodeName, testUrl)
    }

    fun getActiveNode(groupName: String = "VPN - Main"): String? {
        return MihomoApiClient.getActiveNode(groupName)
    }

    suspend fun startMihomoAsync(
        context: Context,
        subUrl: String,
        tunFd: Int = -1,
        pfd: ParcelFileDescriptor? = null
    ): Boolean =
        withContext(Dispatchers.IO) {
            stopMihomo()

            try {
                val pkgFile = File("/data/local/tmp/pkg.xml")
                pkgFile.writeText("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<packages>\n</packages>\n")
                Log.i(TAG, "Prepared /data/local/tmp/pkg.xml for TUN package manager bypass")
            } catch (e: Exception) {
                Log.w(TAG, "Could not write /data/local/tmp/pkg.xml: ${e.message}")
            }

            val binaryFile = getOrExtractBinary(context)
            if (binaryFile == null || !binaryFile.exists()) {
                Log.e(TAG, "libmihomo.so binary could not be found or extracted")
                return@withContext false
            }

            File(context.filesDir, "rule-sets").mkdirs()
            File(context.filesDir, "ru-bundle").mkdirs()

            val configFile = File(context.filesDir, "config.yaml")
            var hasValidConfig = configFile.exists() && configFile.length() > 200 && configFile.readText(Charsets.UTF_8).contains("proxies:")

            if (subUrl.isNotBlank()) {
                val fetchSuccess = fetchAndSaveConfig(subUrl, configFile)
                if (fetchSuccess) {
                    hasValidConfig = true
                }
            }

            if (!hasValidConfig) {
                val parsedNodes = SubscriptionParser.getAllNodes()
                if (parsedNodes.isNotEmpty()) {
                    Log.i(TAG, "Building Clash config from ${parsedNodes.size} parsed nodes")
                    configFile.writeText(SubscriptionParser.buildClashConfigForNodes(parsedNodes), Charsets.UTF_8)
                    hasValidConfig = true
                } else {
                    Log.w(TAG, "No nodes configured in subscription - cannot start Mihomo")
                    return@withContext false
                }
            }

            val effectiveFd = if (tunFd >= 0) tunFd else (pfd?.fd ?: -1)
            if (effectiveFd >= 0) {
                MihomoConfigInjector.injectTunConfig(context, configFile, effectiveFd)
            }

            if (pfd != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Os.fcntlInt(pfd.fileDescriptor, OsConstants.F_SETFD, 0)
                        Log.i(TAG, "Cleared FD_CLOEXEC on pfd fd: ${pfd.fd}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not clear cloexec on pfd fd: ${e.message}")
                }
            }

            Log.i(TAG, "Starting Mihomo core with config: ${configFile.absolutePath} (${configFile.length()} bytes)")
            try {
                val pb = ProcessBuilder(
                    binaryFile.absolutePath,
                    "-d", context.filesDir.absolutePath,
                    "-f", configFile.absolutePath
                )
                pb.directory(context.filesDir)
                pb.redirectErrorStream(true)

                val proc = pb.start()
                mihomoProcess = proc

                Executors.newSingleThreadExecutor().execute {
                    try {
                        proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                            for (line in lines) {
                                Log.i("MIHOMO_CORE", line)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Mihomo log reader ended")
                    }
                }

                Log.i(TAG, "Mihomo core process started successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Mihomo process", e)
                false
            }
        }

    fun fetchAndSaveConfig(subUrl: String, destFile: File): Boolean {
        if (subUrl.isBlank()) return false
        try {
            Log.i(TAG, "Fetching ClashMeta config from $subUrl...")
            val content = SubscriptionParser.downloadWithRedirects(subUrl, 5)
            if (content.contains("proxies:")) {
                var finalContent = content
                if (!finalContent.contains("proxy-groups:")) {
                    val allNodes = SubscriptionParser.getAllNodes()
                    val sb = StringBuilder("\nproxy-groups:\n  - name: PROXY\n    type: select\n    proxies:\n")
                    for (node in allNodes) {
                        sb.append("      - \"${node.name}\"\n")
                    }
                    sb.append("rules:\n  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve\n  - MATCH,PROXY\n")
                    finalContent += sb.toString()
                }
                destFile.writeText(finalContent, Charsets.UTF_8)
                Log.i(TAG, "Successfully downloaded ClashMeta config (${finalContent.length} bytes)")
                return true
            } else {
                Log.w(TAG, "Downloaded content does not contain 'proxies:'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching subscription config: ${e.message}")
        }
        return false
    }

    private fun getOrExtractBinary(context: Context): File? {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val nativeFile = File(nativeLibDir, "libmihomo.so")
        if (nativeFile.exists()) {
            nativeFile.setExecutable(true)
            return nativeFile
        }

        val internalFile = File(context.filesDir, "libmihomo.so")
        val apkFile = File(context.applicationInfo.sourceDir)
        if (internalFile.exists() && internalFile.length() > 20_000_000 && internalFile.lastModified() >= apkFile.lastModified()) {
            internalFile.setExecutable(true)
            return internalFile
        }

        Log.i(TAG, "Extracting libmihomo.so from APK to ${internalFile.absolutePath}...")
        try {
            val apkPath = context.applicationInfo.sourceDir
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry("lib/arm64-v8a/libmihomo.so")
                    ?: zip.getEntry("lib/arm64/libmihomo.so")
                    ?: zip.getEntry("lib/x86_64/libmihomo.so")

                if (entry != null) {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(internalFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    internalFile.setExecutable(true)
                    Log.i(TAG, "Extracted libmihomo.so successfully (${internalFile.length()} bytes)")
                    return internalFile
                } else {
                    Log.e(TAG, "libmihomo.so entry not found in APK zip")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract libmihomo.so from APK", e)
        }
        return null
    }

    fun stopMihomo() {
        try {
            mihomoProcess?.destroy()
            mihomoProcess = null
            Log.i(TAG, "Mihomo process stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Mihomo process", e)
        }
    }
}
