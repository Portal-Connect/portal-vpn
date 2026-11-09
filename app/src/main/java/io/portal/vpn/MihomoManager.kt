package io.portal.vpn

import android.content.Context
import android.os.Build
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
                            // Extract real outbound proxy nodes
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
                                    val isAiOrGoogleGroup = groupKey.equals("AI", ignoreCase = true) || 
                                                            groupKey.equals("ChatGPT", ignoreCase = true) ||
                                                            groupKey.equals("OpenAI", ignoreCase = true) ||
                                                            groupKey.contains("Gemini", ignoreCase = true) ||
                                                            groupKey.contains("Google", ignoreCase = true)
                                    val isAdsGroup = groupKey.equals("ADS_BAN", ignoreCase = true)
                                    val isBypassGroup = groupKey.contains("VK", ignoreCase = true) || groupKey.contains("Sber", ignoreCase = true)

                                    val effectiveTarget = when {
                                        isAdsGroup -> if ("REJECT" in allList) "REJECT" else null
                                        isBypassGroup -> if ("DIRECT" in allList) "DIRECT" else null
                                        isYouTubeGroup -> {
                                            if (useByeDpi && ByeDpiManager.isRunning && allList.any { it.contains("ByeDPI") }) {
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
                    val target = if (useByeDpi && ByeDpiManager.isRunning && allList.any { it.contains("ByeDPI") }) {
                        allList.firstOrNull { it.contains("ByeDPI") }
                    } else {
                        resolveBestYouTubeNode(allList)
                            ?: allList.firstOrNull { it.contains("Германия") }
                            ?: allList.firstOrNull { it.contains("Польша") }
                            ?: allList.firstOrNull { !it.contains("ByeDPI") && it != "DIRECT" && it != "REJECT" }
                    }
                    if (target != null) {
                        putProxySelection("YouTube", target)
                        putProxySelection("Google / Youtube / Gemini", target)
                        Log.i(TAG, "Switched YouTube routing (useByeDpi: $useByeDpi) to: $target")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to switch YouTube routing: ${e.message}")
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
        // Priority 1: Germany (clean Frankfurt IP, 100% Gemini support)
        // Priority 2: Estonia (clean Tallinn IP)
        // Priority 3: Switzerland (clean Zurich IP)
        // Priority 4: USA
        // Note: Finland node in current subscription times out on Google, and Poland is misgeocoded to Brazil
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
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Os.fcntlInt(FileDescriptor.`in`, OsConstants.F_SETFD, 0)
                            }
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

            // Read DNS settings dynamically
            val settings = AppSettings(context)
            val dnsProvider = settings.dnsProvider
            val dns1 = dnsProvider.dns1
            val dns2 = dnsProvider.dns2
            val dnsUrls = when(dnsProvider) {
                DnsProvider.CLOUDFLARE -> listOf("https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query")
                DnsProvider.GOOGLE -> listOf("https://dns.google/dns-query")
                DnsProvider.ADGUARD -> listOf("https://dns.adguard-dns.com/dns-query")
                DnsProvider.YANDEX -> listOf("https://dns.yandex.ru/dns-query")
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
  enable: true
  force-dns-mapping: true
  parse-pure-ip: true
  override-destination: false
  sniff:
    TLS:
      ports: [443, 8443]
    HTTP:
      ports: [80, 8080-8880]
      override-destination: true

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
    - 1.1.1.1
    - 8.8.8.8
  proxy-server-nameserver:
    - 77.88.8.8
    - 1.1.1.1
    - 8.8.8.8
  direct-nameserver:
    - 77.88.8.8
    - 1.1.1.1
    - 8.8.8.8
    - $dns1
    - $dns2
  nameserver:
    - ${dnsUrls.joinToString("\n    - ")}
    - $dns1
    - $dns2
  fake-ip-filter:
$fakeIpFilters
"""
            var newContent = tunBlock + content

            // Inject Dual-Core loopback bridge to Xray / Sing-box and ByeDPI if not present
            if (newContent.contains("proxies:") && !newContent.contains("1082")) {
                val bridgeProxy = """proxies:
  - name: "ByeDPI (Anti-DPI / 0 Ads)"
    type: socks5
    server: 127.0.0.1
    port: 1082
  - name: "Xray Core (Sing-box Bridge)"
    type: socks5
    server: 127.0.0.1
    port: 2080
"""
                newContent = newContent.replaceFirst("proxies:\n", bridgeProxy)
            }

            // Inject dedicated YouTube proxy group (ByeDPI priority for 0 ads & full speed, EU fallback)
            if (newContent.contains("proxy-groups:") && !newContent.contains("name: YouTube\n") && !newContent.contains("name: \"YouTube\"")) {
                val allNodeNames = mutableListOf<String>()
                val nameRegex = Regex("""^\s*-\s+name:\s*['"]?([^'"\n\r]+)['"]?""")
                var inProxies = false
                for (line in content.lines()) {
                    if (line.startsWith("proxies:")) {
                        inProxies = true
                        continue
                    }
                    if (inProxies && line.isNotEmpty() && !line[0].isWhitespace()) {
                        inProxies = false
                    }
                    if (inProxies) {
                        val m = nameRegex.find(line)
                        if (m != null) {
                            val n = m.groupValues[1].trim()
                            if (n.isNotEmpty() && !allNodeNames.contains(n)) {
                                allNodeNames.add(n)
                            }
                        }
                    }
                }
                val ytSb = StringBuilder("proxy-groups:\n  - name: YouTube\n    icon: https://cdn.jsdelivr.net/gh/Koolson/Qure@master/IconSet/Color/YouTube.png\n    type: select\n    proxies:\n")
                ytSb.append("      - \"ByeDPI (Anti-DPI / 0 Ads)\"\n")
                val preferred = listOf("Германия", "Польша", "Эстония", "Швейцария", "США", "Россия")
                val added = mutableSetOf<String>()
                added.add("ByeDPI (Anti-DPI / 0 Ads)")
                for (p in preferred) {
                    val match = allNodeNames.firstOrNull { it.contains(p, ignoreCase = true) }
                    if (match != null && added.add(match)) {
                        ytSb.append("      - \"$match\"\n")
                    }
                }
                for (n in allNodeNames) {
                    if (added.add(n)) {
                        ytSb.append("      - \"$n\"\n")
                    }
                }
                ytSb.append("      - DIRECT\n")
                newContent = newContent.replaceFirst("proxy-groups:\n", ytSb.toString())
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
                
                // Allow ISP-provided or reliable public DNS to resolve proxy servers directly
                directRulesBuilder.append("  - IP-CIDR,77.88.8.8/32,DIRECT,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,1.1.1.1/32,DIRECT,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,8.8.8.8/32,DIRECT,no-resolve\n")

                for (domain in proxyDomains) {
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,$domain,DIRECT\n")
                }

                // Determine best AI target group (Germany/clean EU priority for 100% Gemini support)
                val aiTarget = when {
                    content.contains("name: AI") -> "AI"
                    content.contains("name: Google / Youtube / Gemini") -> "Google / Youtube / Gemini"
                    content.contains("name: 🇩🇪 Германия") || content.contains("Германия") -> "🇩🇪 Германия"
                    content.contains("name: 🇵🇱 Польша") || content.contains("Польша") -> "🇵🇱 Польша"
                    content.contains("name: 🇨🇭 Швейцария") || content.contains("Швейцария") -> "🇨🇭 Швейцария"
                    content.contains("name: 🇺🇸 США") || content.contains("США") -> "🇺🇸 США"
                    content.contains("name: 🇳🇱 Нидерланды") || content.contains("Нидерланды") -> "🇳🇱 Нидерланды"
                    content.contains("name: 🇸🇪 Швеция") || content.contains("Швеция") -> "🇸🇪 Швеция"
                    content.contains("name: 🇫🇮 Финляндия") || content.contains("Финляндия") -> "🇫🇮 Финляндия"
                    else -> "PROXY"
                }

                val ytTarget = if (newContent.contains("name: YouTube")) "YouTube" else "Google / Youtube / Gemini"

                val settings = AppSettings(context)

                // Built-in AdBlock: inject REJECT rules for ads, popups, and trackers
                if (settings.blockAds) {
                    for (rule in AdBlockManager.getMihomoAdBlockRules()) {
                        directRulesBuilder.append("  - $rule\n")
                    }
                }

                // Explicit Gemini, Google AI & OpenAI rules with full API coverage
                directRulesBuilder.append("  - DOMAIN-SUFFIX,gemini.google.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,gemini.google,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,bard.google.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,proactivebackend-pa.googleapis.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,waa-pa.clients6.google.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,alkalimakersuite-pa.clients6.google.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,alkalicore-pa.clients6.google.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,geller-pa.googleapis.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,generativelanguage.googleapis.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,generativeai.google,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,deepmind.google,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,deepmind.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,aistudio.google.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,makersuite.google.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,assistant.google.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,clients6.google.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-KEYWORD,gemini,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-KEYWORD,colab,$aiTarget\n")
                
                // OpenAI / ChatGPT
                directRulesBuilder.append("  - DOMAIN-SUFFIX,chatgpt.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,openai.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,oaistatic.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,oaiusercontent.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,chat.openai.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-KEYWORD,openai,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-KEYWORD,chatgpt,$aiTarget\n")
                
                // YouTube: zero-ads via ByeDPI with European fallback
                // Force TCP for YouTube so ByeDPI can desynchronize TLS handshakes (evade TSPPU throttling)
                directRulesBuilder.append("  - AND,((NETWORK,UDP),(DST-PORT,443),(DOMAIN-SUFFIX,googlevideo.com)),REJECT\n")
                directRulesBuilder.append("  - AND,((NETWORK,UDP),(DST-PORT,443),(DOMAIN-SUFFIX,youtube.com)),REJECT\n")
                directRulesBuilder.append("  - AND,((NETWORK,UDP),(DST-PORT,443),(DOMAIN-KEYWORD,youtube)),REJECT\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,youtube.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,youtube-nocookie.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,googlevideo.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,ytimg.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,ggpht.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,youtu.be,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,youtubei.googleapis.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-KEYWORD,youtube,$ytTarget\n")
                if (settings.bypassRussianServices) {
                    // Route Telegram through proxy group first, before general RU bypass rules
                    val tgTarget = if (content.contains("name: Telegram")) "Telegram" else "PROXY"
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
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,telegra.ph,$tgTarget\n")
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,telesco.pe,$tgTarget\n")
                    directRulesBuilder.append("  - DOMAIN-KEYWORD,telegram,$tgTarget\n")

                    // Route ALL Google domains (including google.ru) through proxy to prevent Russian IP detection by Gemini
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,google.ru,$aiTarget\n")
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,google.com.ru,$aiTarget\n")
                    directRulesBuilder.append("  - DOMAIN-KEYWORD,google,$aiTarget\n")
                    directRulesBuilder.append("  - DOMAIN-KEYWORD,googleapis,$aiTarget\n")
                    directRulesBuilder.append("  - DOMAIN-KEYWORD,gstatic,$aiTarget\n")
                    directRulesBuilder.append("  - DOMAIN-KEYWORD,googleusercontent,$aiTarget\n")
                    directRulesBuilder.append("  - DOMAIN-KEYWORD,notebooklm,$aiTarget\n")
                    directRulesBuilder.append("  - DOMAIN,chrome.cloudflare-dns.com,$aiTarget\n")
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,cloudflare-dns.com,$aiTarget\n")
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,dns.google,$aiTarget\n")
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,1e100.net,$aiTarget\n")
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,g.co,$aiTarget\n")
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,goo.gl,$aiTarget\n")

                    for (rule in RussianBypassManager.getMihomoDirectRules()) {
                        directRulesBuilder.append("  - $rule\n")
                    }
                }
                newContent = newContent.replaceFirst("rules:\n", directRulesBuilder.toString())
            }

            // Ensure old provider Google/YouTube combined group does not misroute Google services to Russia
            if (newContent.contains("Google / Youtube / Gemini")) {
                val aiTarget = if (newContent.contains("name: AI")) "AI" else "Google / Youtube / Gemini"
                newContent = newContent.replace(",Google / Youtube / Gemini", ",$aiTarget")
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
