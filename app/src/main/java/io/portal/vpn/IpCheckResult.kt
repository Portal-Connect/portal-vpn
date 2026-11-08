package io.portal.vpn

data class IpCheckResult(
    val ip: String = "—",
    val country: String = "Неизвестно",
    val countryCode: String = "",
    val city: String = "",
    val org: String = "",
    val flagEmoji: String = "🌐",
    val isProtected: Boolean = false
)
