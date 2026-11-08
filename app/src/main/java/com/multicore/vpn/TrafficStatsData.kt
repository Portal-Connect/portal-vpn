package com.multicore.vpn

data class TrafficStatsData(
    val rxSpeedStr: String = "0 KB/s",
    val txSpeedStr: String = "0 KB/s",
    val totalTrafficStr: String = "0 MB",
    val downloadSpeedBps: Long = 0L,
    val uploadSpeedBps: Long = 0L
) {
    val formattedDownloadSpeed: String get() = rxSpeedStr
    val formattedUploadSpeed: String get() = txSpeedStr
    val formattedTotalTraffic: String get() = totalTrafficStr
}
