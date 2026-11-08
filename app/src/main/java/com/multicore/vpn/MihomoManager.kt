package com.multicore.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.zip.ZipFile

object MihomoManager {
    private const val TAG = "MihomoManager"
    @Volatile
    private var mihomoProcess: Process? = null

    val isRunning: Boolean
        get() = mihomoProcess?.isAlive == true

    fun selectProxyNode(nodeName: String) {
        Executors.newSingleThreadExecutor().execute {
            var cachedAiNode: String? = null
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

                                    val target = if (nodeName.contains("Автовыбор") || nodeName.contains("Auto")) {
                                        allList.firstOrNull { it.contains("Auto") || it.contains("Авто") } ?: allList.firstOrNull()
                                    } else {
                                        allList.firstOrNull { 
                                            it.equals(nodeName, ignoreCase = true) || 
                                            it.contains(nodeName, ignoreCase = true) || 
                                            nodeName.contains(it, ignoreCase = true) 
                                        }
                                    }

                                    // Special AI & Gemini routing guard:
                                    // The subscription contains two groups: "AI" and "Google / Youtube / Gemini".
                                    // To make Gemini always work without country restrictions, both "AI" and "Google / Youtube / Gemini"
                                    // MUST route through a verified alive country where Gemini and Google AI are fully supported:
                                    // Germany (🇩🇪 Германия) -> Finland (🇫🇮 Финляндия) -> Netherlands (🇳🇱 Нидерланды) -> Switzerland (🇨🇭 Швейцария) -> USA (🇺🇸 США).
                                    val isAiOrGoogleGroup = groupKey.equals("AI", ignoreCase = true) || 
                                                            groupKey.equals("ChatGPT", ignoreCase = true) ||
                                                            groupKey.equals("OpenAI", ignoreCase = true) ||
                                                            groupKey.contains("Gemini", ignoreCase = true) ||
                                                            groupKey.contains("Google", ignoreCase = true)

                                    val effectiveTarget = if (isAiOrGoogleGroup) {
                                        if (cachedAiNode == null) {
                                            cachedAiNode = resolveBestAiNode(allList)
                                        }
                                        cachedAiNode ?: target
                                    } else {
                                        target
                                    }

                                    if (effectiveTarget != null) {
                                        putProxySelection(groupKey, effectiveTarget)
                                    }
                                }
                            }
                            queriedGroups = true
                            Log.i(TAG, "Successfully updated Mihomo proxy groups on attempt $attempt for node: $nodeName")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Dynamic Mihomo proxy group query attempt $attempt: ${e.message}")
                }

                if (!queriedGroups) {
                    val groupNames = listOf(
                        "VPN - Main", "Всё остальное", "Telegram", "Google / Youtube / Gemini",
                        "PROXY", "GLOBAL", "Proxy", "GLOBAL-PROXY", "Auto", "Select", "AI",
                        "WhatsApp / IG / FB", "Spotify", "Discord"
                    )
                    for (group in groupNames) {
                        val isAiOrGoogleGroup = group.equals("AI", ignoreCase = true) || 
                                                group.equals("ChatGPT", ignoreCase = true) ||
                                                group.contains("Gemini", ignoreCase = true) ||
                                                group.contains("Google", ignoreCase = true)
                        val target = if (isAiOrGoogleGroup) {
                            "🇩🇪 Германия"
                        } else {
                            nodeName
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

    private fun putProxySelection(groupName: String, targetNode: String) {
        try {
            val encodedGroup = URLEncoder.encode(groupName, "UTF-8")
                .replace("+", "%20")
                .replace("%2F", "%2F") // Keep %2F encoded!
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

    private fun resolveBestAiNode(candidatesPool: List<String>): String? {
        val countryPreferences = listOf("Германия", "Финляндия", "Нидерланды", "Швейцария", "США")
        val candidateNodes = mutableListOf<String>()
        for (country in countryPreferences) {
            val match = candidatesPool.firstOrNull { it.contains(country, ignoreCase = true) }
            if (match != null && !candidateNodes.contains(match)) {
                candidateNodes.add(match)
            }
        }
        for (node in candidateNodes) {
            if (isNodeAlive(node)) {
                Log.i(TAG, "Selected verified alive AI node: $node")
                return node
            }
        }
        return candidateNodes.firstOrNull() ?: candidatesPool.firstOrNull()
    }

    private fun isNodeAlive(nodeName: String): Boolean {
        return try {
            val encoded = URLEncoder.encode(nodeName, "UTF-8").replace("+", "%20")
            val url = URL("http://127.0.0.1:9090/proxies/$encoded/delay?timeout=1500&url=https%3A%2F%2Fwww.gstatic.com%2Fgenerate_204")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1800
            conn.readTimeout = 1800
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                resp.contains("delay")
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun startMihomoAsync(
        context: Context,
        subUrl: String,
        tunFd: Int = -1,
        pfd: ParcelFileDescriptor? = null
    ): Boolean =
        withContext(Dispatchers.IO) {
            stopMihomo()

            // Prepare mock packages.xml for non-root TUN initialization
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

            // Ensure rule-sets directories exist so Mihomo doesn't fail downloading rule-providers
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

            // Inject TUN file descriptor and essential settings
            val effectiveFd = if (pfd != null) 0 else tunFd
            if (effectiveFd >= 0) {
                injectTunConfig(context, configFile, effectiveFd)
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
                if (pfd != null) {
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
                }

                val origStdin = try {
                    Os.dup(FileDescriptor.`in`)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not dup stdin: ${e.message}")
                    null
                }

                val proc = try {
                    if (pfd != null) {
                        Os.dup2(pfd.fileDescriptor, 0)
                        try {
                            Os.fcntlInt(FileDescriptor.`in`, OsConstants.F_SETFD, 0)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not clear cloexec on fd 0: ${e.message}")
                        }
                    }
                    pb.start()
                } finally {
                    if (origStdin != null) {
                        try {
                            Os.dup2(origStdin, 0)
                            Os.close(origStdin)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not restore stdin: ${e.message}")
                        }
                    }
                }
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

    private fun injectTunConfig(context: Context, configFile: File, tunFd: Int) {
        try {
            var content = configFile.readText(Charsets.UTF_8).replace("\r\n", "\n")

            // Detect active network interface (e.g. wlan0, rmnet_data1)
            val activeIface = try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val activeNet = cm?.activeNetwork
                val linkProps = cm?.getLinkProperties(activeNet)
                linkProps?.interfaceName ?: "wlan0"
            } catch (e: Exception) {
                "wlan0"
            }
            Log.i(TAG, "Active physical network interface detected: $activeIface")

            // Extract proxy server hostnames dynamically
            val domainRegex = Regex("""(?:server|servername|sni|host):\s*['"]?([a-zA-Z0-9.-]+)['"]?""")
            val ipRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
            val proxyDomains = domainRegex.findAll(content)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() && !ipRegex.matches(it) && !it.startsWith("127.") && it.contains(".") }
                .distinct()
                .toList()

            // Strip existing sections and top-level keys to prevent YAML duplicate key errors and network dependencies
            val stripKeys = setOf(
                "tun", "dns", "sniffer", "hosts", "find-process-mode",
                "external-controller", "secret", "mixed-port", "port", "socks-port",
                "mode", "log-level", "ipv6", "allow-lan", "lan-allowed-ips",
                "bind-address", "tcp-concurrent", "enable-process", "rule-providers",
                "interface-name"
            )
            val filteredLines = mutableListOf<String>()
            var skipping = false
            for (line in content.lines()) {
                val isTopLevel = line.isNotEmpty() && !line[0].isWhitespace()
                if (isTopLevel) {
                    val key = line.substringBefore(':').trim()
                    skipping = stripKeys.contains(key)
                }
                if (!skipping) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("- GEOIP,") && !trimmed.startsWith("- RULE-SET,")) {
                        if ((trimmed.startsWith("- IP-CIDR,") || trimmed.startsWith("- IP-CIDR6,")) && !trimmed.contains("no-resolve")) {
                            filteredLines.add("${line.trimEnd()},no-resolve")
                        } else {
                            filteredLines.add(line)
                        }
                    }
                }
            }
            content = filteredLines.joinToString("\n")

            val fakeIpFilters = StringBuilder()
            fakeIpFilters.append("    - \"*.lan\"\n")
            fakeIpFilters.append("    - \"*.local\"\n")
            for (domain in proxyDomains) {
                fakeIpFilters.append("    - \"+.$domain\"\n")
                fakeIpFilters.append("    - \"$domain\"\n")
            }

            val tunBlock = """
interface-name: $activeIface
mixed-port: 7890
external-controller: 127.0.0.1:9090
secret: ""
mode: rule
log-level: debug
ipv6: false
allow-lan: true
find-process-mode: off

sniffer:
  enable: false

tun:
  enable: false

dns:
  enable: true
  listen: 0.0.0.0:1053
  enhanced-mode: fake-ip
  fake-ip-range: 198.18.0.1/16
  respect-rules: true
  default-nameserver:
    - 77.88.8.8
    - 223.5.5.5
  proxy-server-nameserver:
    - 77.88.8.8
    - 223.5.5.5
  direct-nameserver:
    - 77.88.8.8
    - 223.5.5.5
  nameserver:
    - https://1.1.1.1/dns-query
    - https://dns.google/dns-query
    - 77.88.8.8
  fake-ip-filter:
$fakeIpFilters
"""
            var newContent = tunBlock + content

            // Inject Dual-Core loopback bridge to Xray / Sing-box if not present
            if (newContent.contains("proxies:") && !newContent.contains("2080")) {
                val bridgeProxy = """proxies:
  - name: "Xray Core (Sing-box Bridge)"
    type: socks5
    server: 127.0.0.1
    port: 2080
"""
                newContent = newContent.replaceFirst("proxies:\n", bridgeProxy)
            }

            // Prepend private LAN bypass and proxy server DIRECT rules to prevent infinite loop
            if (newContent.contains("rules:")) {
                val directRulesBuilder = StringBuilder()
                directRulesBuilder.append("rules:\n")
                directRulesBuilder.append("  - DST-PORT,853,REJECT\n")
                directRulesBuilder.append("  - AND,((NETWORK,udp),(DST-PORT,443)),REJECT\n")
                directRulesBuilder.append("  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve\n")
                for (domain in proxyDomains) {
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,$domain,DIRECT\n")
                }

                // Explicit AI rules to ensure Gemini and OpenAI always route through AI / Google group
                val aiTarget = if (content.contains("name: AI")) "AI" else if (content.contains("name: Google / Youtube / Gemini")) "Google / Youtube / Gemini" else "PROXY"
                directRulesBuilder.append("  - DOMAIN-SUFFIX,gemini.google.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,bard.google.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,generativelanguage.googleapis.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,alkalimakersuite-pa.clients6.google.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,chatgpt.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,openai.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,oaistatic.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,oaiusercontent.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-KEYWORD,gemini,$aiTarget\n")

                val settings = AppSettings(context)
                if (settings.bypassRussianServices) {
                    // Route Telegram through proxy group first, before general RU bypass rules
                    val tgTarget = if (content.contains("name: Telegram")) "Telegram" else "PROXY"
                    directRulesBuilder.append("  - GEOSITE,telegram,$tgTarget\n")
                    directRulesBuilder.append("  - GEOIP,telegram,$tgTarget\n")
                    directRulesBuilder.append("  - IP-CIDR,91.108.4.0/22,$tgTarget,no-resolve\n")
                    directRulesBuilder.append("  - IP-CIDR,91.108.8.0/22,$tgTarget,no-resolve\n")
                    directRulesBuilder.append("  - IP-CIDR,91.108.12.0/22,$tgTarget,no-resolve\n")
                    directRulesBuilder.append("  - IP-CIDR,91.108.16.0/22,$tgTarget,no-resolve\n")
                    directRulesBuilder.append("  - IP-CIDR,91.108.20.0/22,$tgTarget,no-resolve\n")
                    directRulesBuilder.append("  - IP-CIDR,91.108.56.0/22,$tgTarget,no-resolve\n")
                    directRulesBuilder.append("  - IP-CIDR,149.154.160.0/20,$tgTarget,no-resolve\n")
                    directRulesBuilder.append("  - IP-CIDR,149.154.164.0/22,$tgTarget,no-resolve\n")
                    directRulesBuilder.append("  - IP-CIDR,149.154.168.0/22,$tgTarget,no-resolve\n")
                    directRulesBuilder.append("  - IP-CIDR,149.154.172.0/22,$tgTarget,no-resolve\n")
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,t.me,$tgTarget\n")
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,telegram.org,$tgTarget\n")
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,telegram.me,$tgTarget\n")
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,tdesktop.com,$tgTarget\n")

                    for (rule in RussianBypassManager.getMihomoDirectRules()) {
                        directRulesBuilder.append("  - $rule\n")
                    }
                }
                newContent = newContent.replaceFirst("rules:\n", directRulesBuilder.toString())
            }

            configFile.writeText(newContent, Charsets.UTF_8)
            Log.i(TAG, "Injected TUN, DNS, and sniffer block (iface: $activeIface) with fd=$tunFd into config.yaml")
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting TUN config", e)
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
