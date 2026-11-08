package io.portal.vpn

data class VpnNode(
    val name: String,
    val server: String = "",
    val port: Int = 443,
    val uuid: String = "",
    val host: String = "",
    val path: String = "",
    val isReality: Boolean = false,
    val publicKey: String = "",
    val shortId: String = "",
    val flow: String = "",
    val network: String = "tcp",
    val fingerprint: String = "chrome",
    val isGroup: Boolean = false
)
