package io.portal.vpn

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

object MihomoApiClient {
    private const val TAG = "MihomoApiClient"

    fun selectProxyNode(nodeName: String, context: Context? = null) {
        Executors.newSingleThreadExecutor().execute {
            var cachedAiNode: String? = null
            var cachedYtNode: String? = null
            val useByeDpi = context?.let { AppSettings(it).youtubeNoAdsBypass } ?: true
            for (attempt in 1..10) {
                var queriedGroups = false
                try {
                    val url = URL("http://127.0.0.1:9090/proxies")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    conn.setRequestProperty("Accept", "application/json")
                    if (conn.responseCode == 200) {
                        val respText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val json = JSONObject(respText)
                        val proxiesObj = json.optJSONObject("proxies")
                        if (proxiesObj != null) {
                            val nonProxyTypes = setOf("Selector", "URLTest", "Fallback", "LoadBalance", "Direct", "Reject", "Compatible")
                            val realNodes = mutableListOf<String>()
                            val pKeys = proxiesObj.keys()
                            while (pKeys.hasNext()) {
                                val pName = pKeys.next()
                                val pItem = proxiesObj.getJSONObject(pName)
                                val pType = pItem.optString("type")
                                if (pType !in nonProxyTypes && pName !in setOf("DIRECT", "REJECT", "COMPATIBLE", "GLOBAL")) {
                                    realNodes.add(pName)
                                }
                            }

                            if (cachedYtNode == null) {
                                cachedYtNode = resolveBestYouTubeNode(realNodes)
                            }
                            if (cachedAiNode == null) {
                                cachedAiNode = resolveBestAiNode(realNodes)
                            }

                            val keys = proxiesObj.keys()
                            while (keys.hasNext()) {
                                val groupKey = keys.next()
                                val groupItem = proxiesObj.getJSONObject(groupKey)
                                val type = groupItem.optString("type")
                                if (type.equals("Selector", ignoreCase = true)) {
                                    val allArray = groupItem.optJSONArray("all") ?: continue
                                    val allList = mutableListOf<String>()
                                    for (i in 0 until allArray.length()) {
                                        allList.add(allArray.getString(i))
                                    }

                                    val isYouTubeGroup = groupKey.equals("YouTube", ignoreCase = true)
                                    val isAiOrGoogleGroup = !isYouTubeGroup && (
                                                            groupKey.equals("AI", ignoreCase = true) ||
                                                            groupKey.equals("ChatGPT", ignoreCase = true) ||
                                                            groupKey.equals("OpenAI", ignoreCase = true) ||
                                                            groupKey.contains("Gemini", ignoreCase = true) ||
                                                            groupKey.contains("Google", ignoreCase = true))
                                    val isAdsGroup = groupKey.equals("ADS_BAN", ignoreCase = true)
                                    val isBypassGroup = groupKey.contains("VK", ignoreCase = true) || groupKey.contains("Sber", ignoreCase = true)

                                    val effectiveTarget = when {
                                        isAdsGroup -> if ("REJECT" in allList) "REJECT" else null
                                        isBypassGroup -> if ("DIRECT" in allList) "DIRECT" else null
                                        isYouTubeGroup -> {
                                            if (useByeDpi && allList.any { it.contains("ByeDPI") }) {
                                                allList.firstOrNull { it.contains("ByeDPI") }
                                            } else {
                                                cachedYtNode ?: allList.firstOrNull { it.contains("Германия") } ?: allList.firstOrNull { it.contains("Россия") }
                                            }
                                        }
                                        isAiOrGoogleGroup -> cachedAiNode ?: allList.firstOrNull { it.contains("Германия") }
                                        else -> {
                                            allList.firstOrNull {
                                                it.equals(nodeName, ignoreCase = true) ||
                                                it.contains(nodeName, ignoreCase = true)
                                            } ?: cachedAiNode ?: allList.firstOrNull()
                                        }
                                    }

                                    if (effectiveTarget != null && effectiveTarget in allList) {
                                        putProxySelection(groupKey, effectiveTarget)
                                    }
                                }
                            }
                            queriedGroups = true
                            Log.i(TAG, "Successfully updated Mihomo proxy groups on attempt $attempt for node: $nodeName (YouTube: $cachedYtNode, AI: $cachedAiNode)")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Dynamic Mihomo proxy group query attempt $attempt: ${e.message}")
                }

                if (!queriedGroups) {
                    val groupNames = listOf(
                        "VPN - Main", "Всё остальное", "Telegram", "YouTube", "Google / Youtube / Gemini",
                        "PROXY", "GLOBAL", "Proxy", "GLOBAL-PROXY", "Auto", "Select", "AI",
                        "WhatsApp / IG / FB", "Spotify", "Discord"
                    )
                    for (group in groupNames) {
                        val isYt = group.equals("YouTube", ignoreCase = true)
                        val isAi = group.equals("AI", ignoreCase = true) ||
                                   group.equals("ChatGPT", ignoreCase = true) ||
                                   group.contains("Gemini", ignoreCase = true) ||
                                   group.contains("Google", ignoreCase = true)
                        val target = when {
                            isYt -> if (useByeDpi && ByeDpiManager.isRunning) "ByeDPI (Anti-DPI / 0 Ads)" else "🇩🇪 Германия"
                            isAi -> "🇩🇪 Германия"
                            else -> nodeName
                        }
                        putProxySelection(group, target)
                    }
                }
                try {
                    Thread.sleep(400)
                } catch (_: Exception) {}
            }
        }
    }

    fun setYouTubeRouting(context: Context, useByeDpi: Boolean) {
        Executors.newSingleThreadExecutor().execute {
            try {
                if (useByeDpi && !ByeDpiManager.isRunning) {
                    ByeDpiManager.startByeDpi(context)
                    Thread.sleep(200)
                }
                val url = URL("http://127.0.0.1:9090/proxies/YouTube")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode == 200) {
                    val respText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val json = JSONObject(respText)
                    val allArray = json.optJSONArray("all")
                    val allList = mutableListOf<String>()
                    if (allArray != null) {
                        for (i in 0 until allArray.length()) {
                            allList.add(allArray.getString(i))
                        }
                    }
                    val target = if (useByeDpi && allList.any { it.contains("ByeDPI") }) {
                        allList.firstOrNull { it.contains("ByeDPI") }
                    } else {
                        resolveBestYouTubeNode(allList)
                            ?: allList.firstOrNull { it.contains("Германия") }
                            ?: allList.firstOrNull { it.contains("Польша") }
                            ?: allList.firstOrNull { !it.contains("ByeDPI") && it != "DIRECT" && it != "REJECT" }
                    }
                    if (target != null) {
                        putProxySelection("YouTube", target)
                        Log.i(TAG, "Switched YouTube routing (useByeDpi: $useByeDpi) to: $target")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to switch YouTube routing: ${e.message}")
            }
        }
    }

    fun putProxySelection(groupName: String, targetNode: String) {
        try {
            val encodedGroup = URLEncoder.encode(groupName, "UTF-8")
                .replace("+", "%20")
                .replace("%2F", "%2F")
            val url = URL("http://127.0.0.1:9090/proxies/$encodedGroup")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = JSONObject().put("name", targetNode).toString()
            conn.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code in 200..299) {
                Log.i(TAG, "Mihomo REST API selected node '$targetNode' in group '$groupName': code $code")
            } else {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                Log.w(TAG, "Mihomo REST selection warning ($code) for group '$groupName' (target: $targetNode): $err")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mihomo REST selection error for group $groupName: ${e.message}")
        }
    }

    private fun resolveBestYouTubeNode(candidatesPool: List<String>): String? {
        val pool = candidatesPool.filter { !it.contains("ByeDPI", ignoreCase = true) }
        val ytPreferences = listOf("Германия", "Польша", "Эстония", "Швейцария", "США", "Россия")
        val candidateNodes = mutableListOf<String>()
        for (country in ytPreferences) {
            val match = pool.firstOrNull { it.contains(country, ignoreCase = true) }
            if (match != null && !candidateNodes.contains(match)) {
                candidateNodes.add(match)
            }
        }
        for (node in candidateNodes) {
            if (isNodeAlive(node, testUrl = "https%3A%2F%2Fwww.youtube.com%2Fgenerate_204")) {
                Log.i(TAG, "Selected verified alive YouTube node: $node")
                return node
            }
        }
        for (node in candidateNodes) {
            if (isNodeAlive(node)) {
                return node
            }
        }
        return candidateNodes.firstOrNull() ?: pool.firstOrNull()
    }

    private fun resolveBestAiNode(candidatesPool: List<String>): String? {
        val countryPreferences = listOf("Германия", "Эстония", "Швейцария", "США", "Нидерланды", "Швеция")
        val candidateNodes = mutableListOf<String>()
        for (country in countryPreferences) {
            val match = candidatesPool.firstOrNull { it.contains(country, ignoreCase = true) }
            if (match != null && !candidateNodes.contains(match)) {
                candidateNodes.add(match)
            }
        }
        for (node in candidateNodes) {
            if (isNodeAlive(node, testUrl = "https%3A%2F%2Fwww.google.com%2Fgenerate_204")) {
                Log.i(TAG, "Selected verified alive AI node: $node")
                return node
            }
        }
        for (node in candidateNodes) {
            if (isNodeAlive(node)) {
                return node
            }
        }
        return candidateNodes.firstOrNull()
            ?: candidatesPool.firstOrNull()
    }

    fun getNodeDelayMs(nodeName: String, testUrl: String = "http%3A%2F%2Fcp.cloudflare.com%2Fgenerate_204"): Int {
        return try {
            val encoded = URLEncoder.encode(nodeName, "UTF-8").replace("+", "%20")
            val url = URL("http://127.0.0.1:9090/proxies/$encoded/delay?timeout=2500&url=$testUrl")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2800
            conn.readTimeout = 2800
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(resp)
                json.optInt("delay", -1)
            } else {
                -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    fun getActiveNode(groupName: String = "VPN - Main"): String? {
        return try {
            val encoded = URLEncoder.encode(groupName, "UTF-8").replace("+", "%20")
            val url = URL("http://127.0.0.1:9090/proxies/$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(resp)
                val now = json.optString("now")
                if (now.isNotBlank() && now != "DIRECT" && now != "REJECT") now else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isNodeAlive(nodeName: String, testUrl: String = "http%3A%2F%2Fcp.cloudflare.com%2Fgenerate_204"): Boolean {
        return getNodeDelayMs(nodeName, testUrl) > 0
    }
}
