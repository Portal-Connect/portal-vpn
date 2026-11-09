package io.portal.vpn

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

enum class AppInterfaceMode(val title: String, val description: String) {
    UNSELECTED("Не выбран", "Выбор режима при первом запуске"),
    SINGLE_PORTAL("Один Портал", "Умный автопилот: всё работает в 1 нажатие, автоподбор лучших серверов"),
    MULTI_PORTAL("Множество Порталов", "Режим эксперта: ручной выбор стран, радар сервисов, детальные настройки")
}

enum class AutopilotPriority(val title: String, val icon: String, val description: String) {
    MIN_PING("Пинг", "🎮", "Минимальная задержка для звонков и игр"),
    MAX_SPEED("Скорость", "🎬", "Максимальная пропускная способность для 4K видео"),
    MAX_PRIVACY("Защита", "🛡️", "Максимальная маскировка и нейтральные юрисдикции")
}

class AppSettings(context: Context) {
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
                
            val encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                "portal_settings_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            // Migrate old unencrypted preferences if they exist
            val oldPrefs = context.getSharedPreferences("portal_settings", Context.MODE_PRIVATE)
            if (oldPrefs.all.isNotEmpty()) {
                val editor = encryptedPrefs.edit()
                for ((key, value) in oldPrefs.all) {
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Long -> editor.putLong(key, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            editor.putStringSet(key, value as Set<String>)
                        }
                    }
                }
                editor.apply()
                oldPrefs.edit().clear().apply()
            }
            encryptedPrefs
        } catch (e: Exception) {
            // Fallback for devices with broken Android Keystore
            context.getSharedPreferences("portal_settings", Context.MODE_PRIVATE)
        }
    }

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
        private const val KEY_BYPASS_RU_SERVICES = "bypass_ru_services"
        private const val KEY_APP_INTERFACE_MODE = "app_interface_mode"
    }

    var appInterfaceMode: AppInterfaceMode
        get() {
            val name = prefs.getString(KEY_APP_INTERFACE_MODE, AppInterfaceMode.UNSELECTED.name)
            return try {
                AppInterfaceMode.valueOf(name ?: AppInterfaceMode.UNSELECTED.name)
            } catch (e: Exception) {
                AppInterfaceMode.UNSELECTED
            }
        }
        set(value) = prefs.edit().putString(KEY_APP_INTERFACE_MODE, value.name).apply()

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

    var bypassRussianServices: Boolean
        get() = prefs.getBoolean(KEY_BYPASS_RU_SERVICES, true)
        set(value) = prefs.edit().putBoolean(KEY_BYPASS_RU_SERVICES, value).apply()

    var autopilotPriority: AutopilotPriority
        get() {
            val name = prefs.getString("autopilot_priority", AutopilotPriority.MIN_PING.name)
            return try {
                AutopilotPriority.valueOf(name ?: AutopilotPriority.MIN_PING.name)
            } catch (e: Exception) {
                AutopilotPriority.MIN_PING
            }
        }
        set(value) = prefs.edit().putString("autopilot_priority", value.name).apply()

    var hapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean("haptic_feedback", true)
        set(value) = prefs.edit().putBoolean("haptic_feedback", value).apply()

    var blockAds: Boolean
        get() = prefs.getBoolean("block_ads", true)
        set(value) = prefs.edit().putBoolean("block_ads", value).apply()

    var youtubeNoAdsBypass: Boolean
        get() = prefs.getBoolean("youtube_no_ads_bypass", true)
        set(value) = prefs.edit().putBoolean("youtube_no_ads_bypass", value).apply()
}
