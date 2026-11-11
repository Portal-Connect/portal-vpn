package io.portal.vpn

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.GZIPInputStream

object SubscriptionParser {
    private const val TAG = "SubscriptionParser"
    private val parsedNodesMap = LinkedHashMap<String, VpnNode>()
    var lastRawConfigContent: String = ""
        private set

    private var cachedHwid: String? = null

    fun getHwid(context: Context? = null): String {
        cachedHwid?.let { return it }
        if (context != null) {
            try {
                val androidId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
                if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                    cachedHwid = androidId
                    return androidId
                }
            } catch (_: Exception) {}
        }
        val fallback = UUID.nameUUIDFromBytes(
            (android.os.Build.BOARD + android.os.Build.BRAND + android.os.Build.DEVICE + android.os.Build.MODEL).toByteArray()
        ).toString().replace("-", "").take(16)
        cachedHwid = fallback
        return fallback
    }

    fun getNodeByName(name: String): VpnNode? {
        return parsedNodesMap[name]
    }

    fun getAllNodes(): List<VpnNode> {
        return parsedNodesMap.values.toList()
    }

    fun loadSavedConfig(configFile: File, context: Context? = null): List<String> {
        if (!configFile.exists() || configFile.length() < 100) return emptyList()
        return try {
            val content = configFile.readText(StandardCharsets.UTF_8)
            lastRawConfigContent = content
            val routes = parseSubscriptionContent(content)
            if (routes.isNotEmpty()) {
                PortalCoreJni.setRoutes(routes)
            }
            Log.i(TAG, "Loaded ${routes.size} routes from local saved config: ${configFile.absolutePath}")
            routes
        } catch (e: Exception) {
            Log.e(TAG, "Error loading saved config file", e)
            emptyList()
        }
    }

    fun saveLastConfigToFile(destFile: File): Boolean {
        return try {
            if (lastRawConfigContent.isNotBlank() && lastRawConfigContent.contains("proxies:")) {
                destFile.writeText(lastRawConfigContent, StandardCharsets.UTF_8)
                Log.i(TAG, "Saved raw subscription config to ${destFile.absolutePath} (${destFile.length()} bytes)")
                true
            } else if (parsedNodesMap.isNotEmpty()) {
                val generatedClashYaml = buildClashConfigForNodes(getAllNodes())
                destFile.writeText(generatedClashYaml, StandardCharsets.UTF_8)
                Log.i(TAG, "Built and saved Clash config for ${parsedNodesMap.size} nodes to ${destFile.absolutePath}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving subscription config to file", e)
            false
        }
    }

    suspend fun fetchAndParseSubscription(urlStr: String, context: Context? = null): List<String> = withContext(Dispatchers.IO) {
        val trimmed = urlStr.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        if (trimmed.startsWith("vless://") || trimmed.startsWith("vmess://") ||
            trimmed.startsWith("trojan://") || trimmed.startsWith("ss://") ||
            trimmed.startsWith("hysteria2://") || trimmed.startsWith("hy2://")) {
            val routes = parseSubscriptionContent(trimmed)
            if (routes.isNotEmpty()) {
                PortalCoreJni.setRoutes(routes)
            }
            return@withContext routes
        }

        try {
            Log.i(TAG, "Fetching subscription from $trimmed...")
            val content = downloadWithRedirects(trimmed, 5, context)
            lastRawConfigContent = content
            val routes = parseSubscriptionContent(content)
            if (routes.isNotEmpty()) {
                PortalCoreJni.setRoutes(routes)
            }
            Log.i(TAG, "Fetched and parsed ${routes.size} routes from subscription")
            routes
        } catch (e: Exception) {
            Log.w(TAG, "Subscription fetch error: ${e.message}")
            emptyList()
        }
    }

    fun downloadWithRedirects(urlString: String, maxRedirects: Int = 5, context: Context? = null): String {
        var currentUrl = urlString
        var redirects = 0
        val hwid = getHwid(context)

        while (redirects < maxRedirects) {
            val url = URL(currentUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Shadowrocket/1982")
                setRequestProperty("X-HWID", hwid)
                setRequestProperty("x-hwid", hwid)
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

        // 1. Check if response is a Shadowrocket JSON array of Xray configs
        if (decodedContent.startsWith("[") && (decodedContent.contains("remarks") || decodedContent.contains("outbounds"))) {
            parseShadowrocketJson(decodedContent, nodeNames)
        }

        // 2. Try parsing as Clash Meta YAML if "proxies:" is present
        if (nodeNames.isEmpty() && decodedContent.contains("proxies:")) {
            parseClashYaml(decodedContent, nodeNames)
        }

        // 3. Try parsing line-by-line URI links (vless://, vmess://, trojan://, ss://, hysteria2://)
        val lines = decodedContent.lines()
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("vless://") || trimmedLine.startsWith("trojan://") ||
                trimmedLine.startsWith("ss://") || trimmedLine.startsWith("vmess://") ||
                trimmedLine.startsWith("hysteria2://") || trimmedLine.startsWith("hy2://")) {
                val parsedNode = parseNodeUri(trimmedLine)
                if (parsedNode != null &&
                    parsedNode.server != "0.0.0.0" &&
                    !parsedNode.name.contains("App not supported", ignoreCase = true) &&
                    !parsedNode.name.contains("not supported", ignoreCase = true) &&
                    !nodeNames.contains(parsedNode.name)) {
                    nodeNames.add(parsedNode.name)
                    parsedNodesMap[parsedNode.name] = parsedNode
                }
            }
        }

        if (nodeNames.isEmpty() && decodedContent != trimmedContent) {
            if (trimmedContent.startsWith("[") && (trimmedContent.contains("remarks") || trimmedContent.contains("outbounds"))) {
                parseShadowrocketJson(trimmedContent, nodeNames)
            }
            if (nodeNames.isEmpty() && trimmedContent.contains("proxies:")) {
                parseClashYaml(trimmedContent, nodeNames)
            }
            for (line in trimmedContent.lines()) {
                val trimmedLine = line.trim()
                if (trimmedLine.startsWith("vless://") || trimmedLine.startsWith("trojan://") ||
                    trimmedLine.startsWith("ss://") || trimmedLine.startsWith("vmess://") ||
                    trimmedLine.startsWith("hysteria2://") || trimmedLine.startsWith("hy2://")) {
                    val parsedNode = parseNodeUri(trimmedLine)
                    if (parsedNode != null &&
                        parsedNode.server != "0.0.0.0" &&
                        !parsedNode.name.contains("App not supported", ignoreCase = true) &&
                        !parsedNode.name.contains("not supported", ignoreCase = true) &&
                        !nodeNames.contains(parsedNode.name)) {
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

    private fun parseShadowrocketJson(jsonStr: String, nodeNames: MutableList<String>) {
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val remarks = item.optString("remarks", item.optString("name", "")).trim()
                if (remarks.isBlank()) continue

                val outbounds = item.optJSONArray("outbounds") ?: continue
                var proxyObj: JSONObject? = null
                for (j in 0 until outbounds.length()) {
                    val ob = outbounds.getJSONObject(j)
                    if (ob.optString("tag") == "proxy" || j == 0) {
                        proxyObj = ob
                        if (ob.optString("tag") == "proxy") break
                    }
                }
                if (proxyObj == null) continue

                val protocol = proxyObj.optString("protocol", "vless").lowercase()
                val settings = proxyObj.optJSONObject("settings")
                val streamSettings = proxyObj.optJSONObject("streamSettings")

                var server = ""
                var port = 443
                var ports = ""
                var uuid = ""
                var flow = ""

                if (settings != null) {
                    val vnext = settings.optJSONArray("vnext")
                    if (vnext != null && vnext.length() > 0) {
                        val vn0 = vnext.getJSONObject(0)
                        server = vn0.optString("address", "")
                        port = vn0.optInt("port", 443)
                        val users = vn0.optJSONArray("users")
                        if (users != null && users.length() > 0) {
                            val u0 = users.getJSONObject(0)
                            uuid = u0.optString("id", u0.optString("password", ""))
                            flow = u0.optString("flow", "")
                        }
                    } else if (settings.has("address")) {
                        server = settings.optString("address", "")
                        port = settings.optInt("port", 443)
                        uuid = settings.optString("auth", settings.optString("password", ""))
                    }
                }

                var network = "tcp"
                var host = server
                var path = ""
                var isReality = false
                var publicKey = ""
                var shortId = ""
                var fingerprint = "chrome"

                if (streamSettings != null) {
                    network = streamSettings.optString("network", "tcp")
                    val security = streamSettings.optString("security", "")
                    if (security.equals("reality", ignoreCase = true)) {
                        isReality = true
                        val realitySettings = streamSettings.optJSONObject("realitySettings")
                        if (realitySettings != null) {
                            host = realitySettings.optString("serverName", server)
                            publicKey = realitySettings.optString("publicKey", "")
                            shortId = realitySettings.optString("shortId", "")
                            val fp = realitySettings.optString("fingerprint", "chrome")
                            fingerprint = if (fp.equals("firefox", ignoreCase = true)) "chrome" else fp
                        }
                    } else if (security.equals("tls", ignoreCase = true)) {
                        val tlsSettings = streamSettings.optJSONObject("tlsSettings")
                        if (tlsSettings != null) {
                            host = tlsSettings.optString("serverName", server)
                            val fp = tlsSettings.optString("fingerprint", "chrome")
                            fingerprint = if (fp.equals("firefox", ignoreCase = true)) "chrome" else fp
                        }
                    }

                    val xhttpSettings = streamSettings.optJSONObject("xhttpSettings")
                    if (xhttpSettings != null) {
                        path = xhttpSettings.optString("path", "")
                        host = xhttpSettings.optString("host", host)
                    } else {
                        val wsSettings = streamSettings.optJSONObject("wsSettings")
                        if (wsSettings != null) {
                            path = wsSettings.optString("path", "")
                            val headers = wsSettings.optJSONObject("headers")
                            if (headers != null && headers.has("Host")) {
                                host = headers.optString("Host", host)
                            }
                        }
                    }

                    val hysteriaSettings = streamSettings.optJSONObject("hysteriaSettings")
                    if (hysteriaSettings != null && uuid.isBlank()) {
                        uuid = hysteriaSettings.optString("auth", "")
                    }

                    val finalmask = streamSettings.optJSONObject("finalmask")
                    if (finalmask != null) {
                        val quicParams = finalmask.optJSONObject("quicParams")
                        if (quicParams != null) {
                            val udpHop = quicParams.optJSONObject("udpHop")
                            if (udpHop != null) {
                                ports = udpHop.optString("ports", "")
                            }
                        }
                    }
                }

                if (server.isNotBlank() && server != "0.0.0.0" && !remarks.contains("App not supported", ignoreCase = true)) {
                    val nodeType = if (protocol == "hysteria") "hysteria2" else protocol
                    val parsedNode = VpnNode(
                        name = remarks,
                        server = server,
                        port = port,
                        ports = ports,
                        uuid = uuid,
                        host = host,
                        path = path,
                        isReality = isReality,
                        publicKey = publicKey,
                        shortId = shortId,
                        flow = flow,
                        network = network,
                        fingerprint = fingerprint,
                        isGroup = false,
                        type = nodeType
                    )
                    if (!nodeNames.contains(remarks)) {
                        nodeNames.add(remarks)
                        parsedNodesMap[remarks] = parsedNode
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Shadowrocket JSON: ${e.message}", e)
        }
    }

    private fun tryDecodeBase64(raw: String): String {
        val clean = raw.replace("\n", "").replace("\r", "").replace(" ", "").trim()
        val decoders = listOf(
            { Base64.decode(clean, Base64.DEFAULT) },
            { Base64.decode(clean, Base64.URL_SAFE) },
            { Base64.decode(clean, Base64.NO_WRAP) },
            { Base64.decode(clean, Base64.URL_SAFE or Base64.NO_WRAP) }
        )
        for (decoder in decoders) {
            try {
                val bytes = decoder()
                val decoded = String(bytes, StandardCharsets.UTF_8)
                if (decoded.contains("://") || decoded.contains("proxies:") || decoded.contains("- name:") || decoded.startsWith("[")) {
                    return decoded
                }
            } catch (_: Exception) {}
        }
        return raw
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
            if (inProxies &&
                currentServer != "0.0.0.0" &&
                !name.contains("App not supported", ignoreCase = true) &&
                !name.contains("not supported", ignoreCase = true) &&
                !nodeNames.contains(name)) {
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
                    isGroup = false
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
            val scheme = uriStr.substringBefore("://").lowercase()

            if (scheme == "vmess") {
                val b64Part = uriStr.substringAfter("vmess://").trim()
                val decodedJsonStr = tryDecodeBase64(b64Part)
                if (decodedJsonStr.startsWith("{")) {
                    val jsonObj = JSONObject(decodedJsonStr)
                    val ps = jsonObj.optString("ps", "VMess Server")
                    val add = jsonObj.optString("add")
                    val port = jsonObj.optInt("port", 443)
                    val id = jsonObj.optString("id")
                    val net = jsonObj.optString("net", "tcp")
                    val host = jsonObj.optString("host", jsonObj.optString("sni", add))
                    val path = jsonObj.optString("path", "")
                    if (add.isNotBlank() && id.isNotBlank()) {
                        return VpnNode(
                            name = ps,
                            server = add,
                            port = port,
                            uuid = id,
                            host = host,
                            path = path,
                            isReality = false,
                            publicKey = "",
                            shortId = "",
                            flow = "",
                            network = net,
                            fingerprint = "chrome"
                        )
                    }
                }
            }

            val uri = Uri.parse(uriStr)
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
                fingerprint = fp,
                type = scheme
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
            if (node.type == "hysteria2" || node.type == "hysteria") {
                sb.append("    type: hysteria2\n")
                sb.append("    server: ${node.server}\n")
                sb.append("    port: ${node.port}\n")
                if (node.ports.isNotBlank()) {
                    sb.append("    ports: ${node.ports}\n")
                }
                sb.append("    password: ${node.uuid}\n")
                if (node.host.isNotBlank()) {
                    sb.append("    sni: ${node.host}\n")
                }
                sb.append("    alpn:\n")
                sb.append("      - h3\n")
                sb.append("    client-fingerprint: $fp\n")
            } else {
                sb.append("    type: ${node.type.ifBlank { "vless" }}\n")
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
