package io.portal.vpn

import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

object SubscriptionParser {
    private const val TAG = "SubscriptionParser"
    private val parsedNodesMap = LinkedHashMap<String, VpnNode>()
    var lastRawConfigContent: String = ""
        private set

    fun getNodeByName(name: String): VpnNode? {
        return parsedNodesMap[name]
    }

    fun getAllNodes(): List<VpnNode> {
        return parsedNodesMap.values.toList()
    }

    fun saveLastConfigToFile(destFile: java.io.File): Boolean {
        return try {
            if (lastRawConfigContent.isNotBlank() && lastRawConfigContent.contains("proxies:")) {
                destFile.writeText(lastRawConfigContent, StandardCharsets.UTF_8)
                Log.i(TAG, "Saved raw subscription config to ${destFile.absolutePath} (${destFile.length()} bytes)")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving raw subscription config to file", e)
            false
        }
    }

    suspend fun fetchAndParseSubscription(urlStr: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = urlStr.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        // If the user pasted a single node link (vless://, vmess://, trojan://, ss://)
        if (trimmed.startsWith("vless://") || trimmed.startsWith("vmess://") ||
            trimmed.startsWith("trojan://") || trimmed.startsWith("ss://")) {
            return@withContext parseSubscriptionContent(trimmed)
        }

        try {
            Log.i(TAG, "Fetching subscription from $trimmed...")
            val content = downloadWithRedirects(trimmed, 5)
            lastRawConfigContent = content
            val routes = parseSubscriptionContent(content)
            Log.i(TAG, "Fetched and parsed ${routes.size} routes from subscription")
            routes
        } catch (e: Exception) {
            Log.w(TAG, "Subscription fetch error: ${e.message}")
            emptyList()
        }
    }

    fun downloadWithRedirects(urlString: String, maxRedirects: Int = 5): String {
        var currentUrl = urlString
        var redirects = 0

        while (redirects < maxRedirects) {
            val url = URL(currentUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "ClashMeta/1.19.0 (portal-android)")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml,text/plain,*/*")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
            }

            val status = connection.responseCode
            if (status in 300..399) {
                val newUrl = connection.getHeaderField("Location")
                if (newUrl != null && newUrl.isNotBlank()) {
                    currentUrl = if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                        newUrl
                    } else {
                        URL(url, newUrl).toString()
                    }
                    redirects++
                    continue
                }
            }

            val isGzip = "gzip".equals(connection.contentEncoding, ignoreCase = true)
            val inputStream: InputStream = if (isGzip) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }

            return inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).readText()
            }
        }
        throw IllegalStateException("Too many redirects: $urlString")
    }

    fun parseSubscriptionContent(content: String): List<String> {
        val trimmedContent = content.trim()
        if (trimmedContent.isBlank()) return emptyList()

        val decodedContent = tryDecodeBase64(trimmedContent)
        val nodeNames = mutableListOf<String>()

        // 1. Try parsing as Clash Meta YAML if "proxies:" is present
        if (decodedContent.contains("proxies:")) {
            parseClashYaml(decodedContent, nodeNames)
        }

        // 2. Try parsing line-by-line URI links (vless://, vmess://, trojan://, ss://)
        val lines = decodedContent.lines()
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("vless://") || trimmedLine.startsWith("trojan://") ||
                trimmedLine.startsWith("ss://") || trimmedLine.startsWith("vmess://")) {
                val parsedNode = parseNodeUri(trimmedLine)
                if (parsedNode != null && !nodeNames.contains(parsedNode.name)) {
                    nodeNames.add(parsedNode.name)
                    parsedNodesMap[parsedNode.name] = parsedNode
                }
            }
        }

        // If nothing was parsed from decodedContent, try the raw content
        if (nodeNames.isEmpty() && decodedContent != trimmedContent) {
            if (trimmedContent.contains("proxies:")) {
                parseClashYaml(trimmedContent, nodeNames)
            }
            for (line in trimmedContent.lines()) {
                val trimmedLine = line.trim()
                if (trimmedLine.startsWith("vless://") || trimmedLine.startsWith("trojan://") ||
                    trimmedLine.startsWith("ss://") || trimmedLine.startsWith("vmess://")) {
                    val parsedNode = parseNodeUri(trimmedLine)
                    if (parsedNode != null && !nodeNames.contains(parsedNode.name)) {
                        nodeNames.add(parsedNode.name)
                        parsedNodesMap[parsedNode.name] = parsedNode
                    }
                }
            }
        }

        if (nodeNames.isNotEmpty()) {
            if (!nodeNames.contains("⚡️ Автовыбор сервера") && !nodeNames.any { it.contains("Auto") || it.contains("Авто") }) {
                nodeNames.add(0, "⚡️ Автовыбор сервера")
            }
        }

        return nodeNames
    }

    private fun tryDecodeBase64(raw: String): String {
        val clean = raw.replace("\n", "").replace("\r", "").replace(" ", "").trim()
        return try {
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            val decoded = String(bytes, StandardCharsets.UTF_8)
            // If the decoded string contains readable proxy indicators, use it
            if (decoded.contains("://") || decoded.contains("proxies:") || decoded.contains("- name:")) {
                decoded
            } else {
                raw
            }
        } catch (e: Exception) {
            raw
        }
    }

    private fun parseClashYaml(yaml: String, nodeNames: MutableList<String>) {
        val lines = yaml.lines()
        var inProxies = false
        var inProxyGroups = false
        var currentName: String? = null
        var currentServer = ""
        var currentPort = 443
        var currentUuid = ""
        var currentHost = ""
        var currentPath = ""
        var currentNetwork = "tcp"
        var currentReality = false
        var currentPublicKey = ""
        var currentShortId = ""
        var currentFlow = ""
        var currentFingerprint = "chrome"

        fun commitNode() {
            val name = currentName ?: return
            if (!nodeNames.contains(name)) {
                nodeNames.add(name)
                parsedNodesMap[name] = VpnNode(
                    name = name,
                    server = currentServer,
                    port = currentPort,
                    uuid = currentUuid,
                    host = if (currentHost.isNotBlank()) currentHost else currentServer,
                    path = currentPath,
                    isReality = currentReality,
                    publicKey = currentPublicKey,
                    shortId = currentShortId,
                    flow = currentFlow,
                    network = currentNetwork,
                    fingerprint = if (currentFingerprint.isNotBlank()) currentFingerprint else "chrome",
                    isGroup = inProxyGroups
                )
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("proxies:")) {
                commitNode()
                inProxies = true
                inProxyGroups = false
                continue
            }
            if (trimmed.startsWith("proxy-groups:")) {
                commitNode()
                inProxies = false
                inProxyGroups = true
                continue
            }
            if (trimmed.startsWith("rules:") || trimmed.startsWith("rule-providers:")) {
                commitNode()
                inProxies = false
                inProxyGroups = false
                break
            }
            if (inProxies || inProxyGroups) {
                if (trimmed.startsWith("- name:")) {
                    commitNode()
                    val rawName = trimmed.removePrefix("- name:").trim().trim('\'', '"')
                    currentName = rawName
                    currentServer = ""
                    currentPort = 443
                    currentUuid = ""
                    currentHost = ""
                    currentPath = ""
                    currentNetwork = "tcp"
                    currentReality = false
                    currentPublicKey = ""
                    currentShortId = ""
                    currentFlow = ""
                    currentFingerprint = "chrome"
                } else if (currentName != null && inProxies) {
                    when {
                        trimmed.startsWith("server:") -> currentServer = trimmed.removePrefix("server:").trim().trim('\'', '"')
                        trimmed.startsWith("port:") -> currentPort = trimmed.removePrefix("port:").trim().toIntOrNull() ?: 443
                        trimmed.startsWith("uuid:") -> currentUuid = trimmed.removePrefix("uuid:").trim().trim('\'', '"')
                        trimmed.startsWith("password:") -> currentUuid = trimmed.removePrefix("password:").trim().trim('\'', '"')
                        trimmed.startsWith("servername:") -> currentHost = trimmed.removePrefix("servername:").trim().trim('\'', '"')
                        trimmed.startsWith("sni:") -> currentHost = trimmed.removePrefix("sni:").trim().trim('\'', '"')
                        trimmed.startsWith("path:") -> currentPath = trimmed.removePrefix("path:").trim().trim('\'', '"')
                        trimmed.startsWith("network:") -> currentNetwork = trimmed.removePrefix("network:").trim().trim('\'', '"')
                        trimmed.startsWith("flow:") -> currentFlow = trimmed.removePrefix("flow:").trim().trim('\'', '"')
                        trimmed.startsWith("client-fingerprint:") -> currentFingerprint = trimmed.removePrefix("client-fingerprint:").trim().trim('\'', '"')
                        trimmed.startsWith("public-key:") -> {
                            currentReality = true
                            currentPublicKey = trimmed.removePrefix("public-key:").trim().trim('\'', '"')
                        }
                        trimmed.startsWith("short-id:") -> currentShortId = trimmed.removePrefix("short-id:").trim().trim('\'', '"')
                    }
                }
            }
        }
        commitNode()
    }

    private fun parseNodeUri(uriStr: String): VpnNode? {
        return try {
            val uri = Uri.parse(uriStr)
            val scheme = uri.scheme?.lowercase() ?: return null
            val rawName = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") }
            val server = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443
            val uuid = uri.userInfo ?: ""
            val name = if (!rawName.isNullOrBlank()) rawName else "$scheme $server:$port"

            val network = uri.getQueryParameter("type") ?: uri.getQueryParameter("network") ?: "tcp"
            val path = uri.getQueryParameter("path") ?: ""
            val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: server
            val security = uri.getQueryParameter("security") ?: ""
            val isReality = security.equals("reality", ignoreCase = true) || uri.getQueryParameter("pbk") != null
            val publicKey = uri.getQueryParameter("pbk") ?: ""
            val shortId = uri.getQueryParameter("sid") ?: ""
            val flow = uri.getQueryParameter("flow") ?: ""
            val rawFp = uri.getQueryParameter("fp") ?: uri.getQueryParameter("fingerprint") ?: "chrome"
            // Ensure if someone passed outdated "firefox" in URL, we map it to "chrome" for Xray Reality post-quantum compatibility
            val fp = if (rawFp.equals("firefox", ignoreCase = true)) "chrome" else rawFp

            VpnNode(
                name = name,
                server = server,
                port = port,
                uuid = uuid,
                host = sni,
                path = path,
                isReality = isReality,
                publicKey = publicKey,
                shortId = shortId,
                flow = flow,
                network = network,
                fingerprint = fp
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing URI $uriStr: ${e.message}")
            null
        }
    }

    fun buildClashConfigForNodes(nodes: List<VpnNode>, tunFd: Int = 0): String {
        val sb = StringBuilder()
        sb.append("mixed-port: 7890\n")
        sb.append("external-controller: 127.0.0.1:9090\n")
        sb.append("allow-lan: true\n")
        sb.append("mode: rule\n")
        sb.append("log-level: info\n")
        sb.append("ipv6: false\n")
        sb.append("find-process-mode: off\n")

        sb.append("sniffer:\n")
        sb.append("  enable: true\n")
        sb.append("  force-dns-mapping: true\n")
        sb.append("  parse-pure-ip: true\n")
        sb.append("  sniff:\n")
        sb.append("    HTTP:\n")
        sb.append("      ports: [80, 8080-8880]\n")
        sb.append("      override-destination: true\n")
        sb.append("    TLS:\n")
        sb.append("      ports: [443, 8443]\n")
        sb.append("    QUIC:\n")
        sb.append("      ports: [443, 8443]\n")

        sb.append("dns:\n")
        sb.append("  enable: true\n")
        sb.append("  enhanced-mode: fake-ip\n")
        sb.append("  fake-ip-range: 198.18.0.1/16\n")
        sb.append("  default-nameserver:\n")
        sb.append("    - 77.88.8.8\n")
        sb.append("    - 8.8.8.8\n")
        sb.append("  proxy-server-nameserver:\n")
        sb.append("    - 77.88.8.8\n")
        sb.append("    - 8.8.8.8\n")
        sb.append("  nameserver:\n")
        sb.append("    - 77.88.8.8\n")
        sb.append("    - 8.8.8.8\n")
        sb.append("    - 1.1.1.1\n")

        sb.append("tun:\n")
        sb.append("  enable: true\n")
        sb.append("  stack: gvisor\n")
        sb.append("  device: tun0\n")
        sb.append("  inet4-address:\n")
        sb.append("    - 172.19.0.1/30\n")
        if (tunFd > 0) {
            sb.append("  file-descriptor: $tunFd\n")
        }
        sb.append("  auto-route: false\n")
        sb.append("  auto-detect-interface: false\n")
        sb.append("  dns-hijack:\n")
        sb.append("    - any:53\n")
        sb.append("    - tcp://any:53\n")

        sb.append("proxies:\n")
        for (node in nodes) {
            val fp = if (node.fingerprint.isNotBlank() && !node.fingerprint.equals("firefox", ignoreCase = true)) node.fingerprint else "chrome"
            sb.append("  - name: \"${node.name}\"\n")
            sb.append("    type: vless\n")
            sb.append("    server: ${node.server}\n")
            sb.append("    port: ${node.port}\n")
            sb.append("    uuid: ${node.uuid}\n")
            sb.append("    network: ${node.network}\n")
            sb.append("    udp: true\n")
            sb.append("    tls: true\n")
            sb.append("    servername: ${node.host}\n")
            sb.append("    client-fingerprint: $fp\n")

            if (node.path.isNotBlank()) {
                if (node.network == "xhttp") {
                    sb.append("    xhttp-opts:\n")
                    sb.append("      path: ${node.path}\n")
                    sb.append("      host: ${node.host}\n")
                } else if (node.network == "ws") {
                    sb.append("    ws-opts:\n")
                    sb.append("      path: ${node.path}\n")
                    sb.append("      headers:\n")
                    sb.append("        Host: ${node.host}\n")
                }
            }

            if (node.isReality) {
                sb.append("    reality-opts:\n")
                sb.append("      public-key: ${node.publicKey}\n")
                if (node.shortId.isNotBlank()) {
                    sb.append("      short-id: ${node.shortId}\n")
                }
                sb.append("      support-x25519mlkem768: true\n")
            }
            if (node.flow.isNotBlank()) {
                sb.append("    flow: ${node.flow}\n")
            }
        }

        sb.append("proxy-groups:\n")
        sb.append("  - name: \"⚡️ Автовыбор сервера\"\n")
        sb.append("    type: url-test\n")
        sb.append("    url: http://cp.cloudflare.com/generate_204\n")
        sb.append("    interval: 300\n")
        sb.append("    tolerance: 50\n")
        sb.append("    proxies:\n")
        for (node in nodes) {
            sb.append("      - \"${node.name}\"\n")
        }
        sb.append("  - name: PROXY\n")
        sb.append("    type: select\n")
        sb.append("    proxies:\n")
        sb.append("      - \"⚡️ Автовыбор сервера\"\n")
        for (node in nodes) {
            sb.append("      - \"${node.name}\"\n")
        }
        sb.append("  - name: GLOBAL\n")
        sb.append("    type: select\n")
        sb.append("    proxies:\n")
        sb.append("      - PROXY\n")
        sb.append("      - \"⚡️ Автовыбор сервера\"\n")
        for (node in nodes) {
            sb.append("      - \"${node.name}\"\n")
        }

        sb.append("rules:\n")
        sb.append("  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve\n")
        sb.append("  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve\n")
        sb.append("  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve\n")
        sb.append("  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve\n")
        for (rule in RussianBypassManager.getMihomoDirectRules()) {
            sb.append("  - $rule\n")
        }
        sb.append("  - IP-CIDR,198.18.0.0/15,PROXY,no-resolve\n")
        sb.append("  - IP-CIDR,91.108.4.0/22,PROXY,no-resolve\n")
        sb.append("  - IP-CIDR,91.108.8.0/22,PROXY,no-resolve\n")
        sb.append("  - IP-CIDR,91.108.12.0/22,PROXY,no-resolve\n")
        sb.append("  - IP-CIDR,91.108.16.0/22,PROXY,no-resolve\n")
        sb.append("  - IP-CIDR,91.108.20.0/22,PROXY,no-resolve\n")
        sb.append("  - IP-CIDR,91.108.56.0/22,PROXY,no-resolve\n")
        sb.append("  - IP-CIDR,149.154.160.0/20,PROXY,no-resolve\n")
        sb.append("  - IP-CIDR,185.76.151.0/24,PROXY,no-resolve\n")
        sb.append("  - MATCH,PROXY\n")

        return sb.toString()
    }
}
