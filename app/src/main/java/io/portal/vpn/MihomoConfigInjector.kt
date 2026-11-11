package io.portal.vpn

import android.content.Context
import android.util.Log
import java.io.File

object MihomoConfigInjector {
    private const val TAG = "MihomoConfigInjector"

    fun injectTunConfig(context: Context, configFile: File, tunFd: Int) {
        try {
            var content = configFile.readText(Charsets.UTF_8).replace("\r\n", "\n")

            val activeIface = try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val activeNet = cm?.activeNetwork
                val linkProps = cm?.getLinkProperties(activeNet)
                linkProps?.interfaceName ?: "wlan0"
            } catch (e: Exception) {
                "wlan0"
            }
            Log.i(TAG, "Active physical network interface detected: $activeIface")

            val domainRegex = Regex("""(?:server|servername|sni|host):\s*['"]?([a-zA-Z0-9.-]+)['"]?""")
            val ipRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
            val proxyDomains = domainRegex.findAll(content)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() && !ipRegex.matches(it) && !it.startsWith("127.") && it.contains(".") }
                .distinct()
                .toList()

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

            if (newContent.contains("rules:")) {
                val directRulesBuilder = StringBuilder()
                directRulesBuilder.append("rules:\n")
                directRulesBuilder.append("  - DST-PORT,853,REJECT\n")
                directRulesBuilder.append("  - AND,((NETWORK,udp),(DST-PORT,443)),REJECT\n")
                directRulesBuilder.append("  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve\n")

                directRulesBuilder.append("  - IP-CIDR,77.88.8.8/32,DIRECT,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,1.1.1.1/32,DIRECT,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,8.8.8.8/32,DIRECT,no-resolve\n")

                for (domain in proxyDomains) {
                    directRulesBuilder.append("  - DOMAIN-SUFFIX,$domain,DIRECT\n")
                }

                val aiTarget = when {
                    content.contains("name: AI") -> "AI"
                    content.contains("name: Google / Youtube / Gemini") -> "Google / Youtube / Gemini"
                    content.contains("name: 🇫🇷 Франция") || content.contains("Франция") -> "🇫🇷 Франция"
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

                if (settings.blockAds) {
                    for (rule in AdBlockManager.getMihomoAdBlockRules()) {
                        directRulesBuilder.append("  - $rule\n")
                    }
                }

                // Force TCP (TLS) for Gemini, OpenAI & YouTube to prevent QUIC IP/location leaks on Android
                directRulesBuilder.append("  - AND,((NETWORK,UDP),(DST-PORT,443),(DOMAIN-SUFFIX,googlevideo.com)),REJECT\n")
                directRulesBuilder.append("  - AND,((NETWORK,UDP),(DST-PORT,443),(DOMAIN-SUFFIX,youtube.com)),REJECT\n")
                directRulesBuilder.append("  - AND,((NETWORK,UDP),(DST-PORT,443),(DOMAIN-KEYWORD,youtube)),REJECT\n")
                directRulesBuilder.append("  - AND,((NETWORK,UDP),(DST-PORT,443),(DOMAIN-SUFFIX,gemini.google.com)),REJECT\n")
                directRulesBuilder.append("  - AND,((NETWORK,UDP),(DST-PORT,443),(DOMAIN-SUFFIX,gemini.google)),REJECT\n")
                directRulesBuilder.append("  - AND,((NETWORK,UDP),(DST-PORT,443),(DOMAIN-KEYWORD,gemini)),REJECT\n")
                directRulesBuilder.append("  - AND,((NETWORK,UDP),(DST-PORT,443),(DOMAIN-SUFFIX,generativelanguage.googleapis.com)),REJECT\n")
                directRulesBuilder.append("  - AND,((NETWORK,UDP),(DST-PORT,443),(DOMAIN-SUFFIX,aistudio.google.com)),REJECT\n")
                directRulesBuilder.append("  - AND,((NETWORK,UDP),(DST-PORT,443),(DOMAIN-SUFFIX,chatgpt.com)),REJECT\n")
                directRulesBuilder.append("  - AND,((NETWORK,UDP),(DST-PORT,443),(DOMAIN-SUFFIX,openai.com)),REJECT\n")

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

                directRulesBuilder.append("  - DOMAIN-SUFFIX,chatgpt.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,openai.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,oaistatic.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,oaiusercontent.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,chat.openai.com,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-KEYWORD,openai,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-KEYWORD,chatgpt,$aiTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,youtube.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,youtube-nocookie.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,googlevideo.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,ytimg.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,ggpht.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,youtu.be,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,youtubei.googleapis.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,yt3.googleusercontent.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,yt4.googleusercontent.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,jnn-pa.googleapis.com,$ytTarget\n")
                directRulesBuilder.append("  - DOMAIN-KEYWORD,youtube,$ytTarget\n")
                // Always route Telegram through proxy to bypass TSPU blocks on Telegram IP ranges
                val tgTarget = if (newContent.contains("name: Telegram") || content.contains("name: Telegram")) "Telegram" else "PROXY"
                directRulesBuilder.append("  - IP-CIDR,91.108.0.0/16,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,91.108.4.0/22,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,91.108.8.0/22,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,91.108.12.0/22,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,91.108.16.0/22,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,91.108.20.0/22,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,91.108.56.0/22,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,91.105.192.0/23,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,149.154.160.0/20,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,149.154.164.0/22,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,149.154.168.0/22,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,149.154.172.0/22,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - IP-CIDR,185.76.151.0/24,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - GEOIP,telegram,$tgTarget,no-resolve\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,t.me,$tgTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,telegram.org,$tgTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,telegram.me,$tgTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,tdesktop.com,$tgTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,telegra.ph,$tgTarget\n")
                directRulesBuilder.append("  - DOMAIN-SUFFIX,telesco.pe,$tgTarget\n")
                directRulesBuilder.append("  - DOMAIN-KEYWORD,telegram,$tgTarget\n")

                if (settings.bypassRussianServices) {
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
}
