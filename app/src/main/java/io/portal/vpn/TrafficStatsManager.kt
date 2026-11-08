package io.portal.vpn

import android.net.TrafficStats
import android.util.Log

object TrafficStatsManager {
    private const val TAG = "TrafficStatsManager"
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var sessionRxBytes = 0L
    private var sessionTxBytes = 0L
    private var lastTimeMs = 0L

    fun resetSession() {
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        sessionRxBytes = 0L
        sessionTxBytes = 0L
        lastTimeMs = System.currentTimeMillis()
        Log.i(TAG, "TrafficStats session reset")
    }

    fun getLiveStats(): TrafficStatsData = updateStats()

    fun updateStats(): TrafficStatsData {
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        val currentTimeMs = System.currentTimeMillis()

        if (lastTimeMs <= 0 || lastRxBytes <= 0 || lastTxBytes <= 0) {
            lastRxBytes = currentRx
            lastTxBytes = currentTx
            lastTimeMs = currentTimeMs
            return TrafficStatsData()
        }

        val timeDiffSec = (currentTimeMs - lastTimeMs) / 1000.0
        if (timeDiffSec <= 0.0) {
            return TrafficStatsData()
        }

        val rxDiff = if (currentRx >= lastRxBytes) currentRx - lastRxBytes else 0L
        val txDiff = if (currentTx >= lastTxBytes) currentTx - lastTxBytes else 0L

        sessionRxBytes += rxDiff
        sessionTxBytes += txDiff
        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastTimeMs = currentTimeMs

        val rxSpeed = rxDiff / timeDiffSec
        val txSpeed = txDiff / timeDiffSec

        return TrafficStatsData(
            rxSpeedStr = formatSpeed(rxSpeed),
            txSpeedStr = formatSpeed(txSpeed),
            totalTrafficStr = formatBytes(sessionRxBytes + sessionTxBytes),
            downloadSpeedBps = rxSpeed.toLong(),
            uploadSpeedBps = txSpeed.toLong()
        )
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1048576.0 -> String.format("%.1f MB/s", bytesPerSec / 1048576.0)
            bytesPerSec >= 1024.0 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
            else -> String.format("%.0f B/s", bytesPerSec)
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / 1048576.0
        return if (mb >= 1024.0) {
            String.format("%.2f GB", mb / 1024.0)
        } else {
            String.format("%.1f MB", mb)
        }
    }
}
