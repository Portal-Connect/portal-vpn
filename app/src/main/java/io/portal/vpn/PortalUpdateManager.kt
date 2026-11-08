package io.portal.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val downloadUrl: String,
    val changelog: String,
    val apkSizeMb: Double = 0.0
)

object PortalUpdateManager {
    private const val TAG = "PortalUpdateManager"
    private const val GITHUB_REPO = "Portal-Connect/portal-vpn"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    suspend fun checkForUpdates(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "PortalVPN-AndroidApp")
            }

            if (connection.responseCode != 200) {
                Log.w(TAG, "GitHub API returned code ${connection.responseCode}")
                return@withContext UpdateInfo(false, currentVersion, "", "")
            }

            val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonText)
            val tagName = json.optString("tag_name", "").removePrefix("v")
            val changelog = json.optString("body", "Новая версия Portal VPN")
            val assets = json.optJSONArray("assets")

            var downloadUrl = ""
            var apkSizeMb = 0.0

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.contains("release", ignoreCase = true) && name.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        val sizeBytes = asset.optLong("size", 0L)
                        apkSizeMb = sizeBytes / (1024.0 * 1024.0)
                        break
                    }
                }
                // fallback to any apk if release not found specifically
                if (downloadUrl.isBlank()) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            val sizeBytes = asset.optLong("size", 0L)
                            apkSizeMb = sizeBytes / (1024.0 * 1024.0)
                            break
                        }
                    }
                }
            }

            val isNewer = isVersionNewer(tagName, currentVersion)
            Log.i(TAG, "Latest version: $tagName, current: $currentVersion, isNewer: $isNewer, url: $downloadUrl")

            UpdateInfo(
                hasUpdate = isNewer && downloadUrl.isNotBlank(),
                latestVersion = tagName,
                downloadUrl = downloadUrl,
                changelog = changelog,
                apkSizeMb = apkSizeMb
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}", e)
            UpdateInfo(false, currentVersion, "", "")
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        if (latest.isBlank() || current.isBlank()) return false
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()
            val apkFile = File(updatesDir, "portal-vpn-update.apk")
            if (apkFile.exists()) apkFile.delete()

            val url = URL(downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PortalVPN-AndroidApp")
            }

            val totalLength = connection.contentLength
            var downloaded = 0L

            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalLength > 0) {
                            val progress = ((downloaded * 100) / totalLength).toInt()
                            onProgress(progress)
                        }
                    }
                    output.flush()
                }
            }

            Log.i(TAG, "APK downloaded successfully (${apkFile.length()} bytes)")
            installApk(context, apkFile)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download update APK: ${e.message}", e)
            false
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
        }
    }
}
