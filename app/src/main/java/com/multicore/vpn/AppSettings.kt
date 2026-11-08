package com.multicore.vpn

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val displayName: String) {
    DARK("Тёмная"),
    LIGHT("Светлая"),
    SYSTEM("Системная")
}

enum class AccentColorOption(val title: String, val color: Color) {
    CYAN("Портал (Неон)", Color(0xFF00E5FF)),
    PURPLE("Вортекс (Фиолетовый)", Color(0xFFA855F7)),
    BLUE("Квант (Синий)", Color(0xFF3B82F6)),
    EMERALD("Изумруд (Зелёный)", Color(0xFF10B981)),
    AMBER("Плазма (Оранжевый)", Color(0xFFF59E0B))
}

enum class DnsProvider(val displayName: String, val dns1: String, val dns2: String) {
    CLOUDFLARE("Cloudflare (1.1.1.1)", "1.1.1.1", "1.0.0.1"),
    GOOGLE("Google (8.8.8.8)", "8.8.8.8", "8.8.4.4"),
    ADGUARD("AdGuard AdBlock", "94.140.14.14", "94.140.15.15"),
    YANDEX("Yandex (77.88.8.8)", "77.88.8.8", "77.88.8.1")
}

class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("multicore_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_DNS_PROVIDER = "dns_provider"
        private const val KEY_AUTO_CONNECT_BOOT = "auto_connect_boot"
        private const val KEY_KILL_SWITCH = "kill_switch"
        private const val KEY_SORT_BY_PING = "sort_by_ping"
        private const val KEY_SPLIT_MODE = "split_mode"
        private const val KEY_SELECTED_PACKAGES = "selected_packages"
        private const val KEY_FAVORITES = "favorite_routes"
        private const val KEY_SUB_URL = "sub_url"
        private const val KEY_AUTO_FAILOVER = "auto_failover"
    }

    var themeMode: ThemeMode
        get() {
            val name = prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.name)
            return try {
                ThemeMode.valueOf(name ?: ThemeMode.DARK.name)
            } catch (e: Exception) {
                ThemeMode.DARK
            }
        }
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    var accentColor: AccentColorOption
        get() {
            val name = prefs.getString(KEY_ACCENT_COLOR, AccentColorOption.CYAN.name)
            return try {
                AccentColorOption.valueOf(name ?: AccentColorOption.CYAN.name)
            } catch (e: Exception) {
                AccentColorOption.CYAN
            }
        }
        set(value) = prefs.edit().putString(KEY_ACCENT_COLOR, value.name).apply()

    var dnsProvider: DnsProvider
        get() {
            val name = prefs.getString(KEY_DNS_PROVIDER, DnsProvider.CLOUDFLARE.name)
            return try {
                DnsProvider.valueOf(name ?: DnsProvider.CLOUDFLARE.name)
            } catch (e: Exception) {
                DnsProvider.CLOUDFLARE
            }
        }
        set(value) = prefs.edit().putString(KEY_DNS_PROVIDER, value.name).apply()

    var autoConnectOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONNECT_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONNECT_BOOT, value).apply()

    var killSwitchEnabled: Boolean
        get() = prefs.getBoolean(KEY_KILL_SWITCH, false)
        set(value) = prefs.edit().putBoolean(KEY_KILL_SWITCH, value).apply()

    var killSwitch: Boolean
        get() = killSwitchEnabled
        set(value) {
            killSwitchEnabled = value
        }

    var sortByPingEnabled: Boolean
        get() = prefs.getBoolean(KEY_SORT_BY_PING, false)
        set(value) = prefs.edit().putBoolean(KEY_SORT_BY_PING, value).apply()

    var splitTunnelingMode: String
        get() = prefs.getString(KEY_SPLIT_MODE, "BYPASS") ?: "BYPASS"
        set(value) = prefs.edit().putString(KEY_SPLIT_MODE, value).apply()

    var selectedAppPackages: Set<String>
        get() = prefs.getStringSet(KEY_SELECTED_PACKAGES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SELECTED_PACKAGES, value).apply()

    var favoriteRoutes: Set<String>
        get() = prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FAVORITES, value).apply()

    var lastSubscriptionUrl: String
        get() = prefs.getString(KEY_SUB_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUB_URL, value).apply()

    var autoFailoverEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FAILOVER, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FAILOVER, value).apply()
}
