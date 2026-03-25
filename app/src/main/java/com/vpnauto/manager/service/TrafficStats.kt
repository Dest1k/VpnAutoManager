package com.vpnauto.manager.service

import kotlinx.coroutines.*

data class TrafficSnapshot(
    val rxBytes: Long,
    val txBytes: Long,
    val rxSpeedBps: Long,
    val txSpeedBps: Long,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Отслеживает трафик через VPN и скорость в реальном времени.
 * Использует android.net.TrafficStats — работает без root.
 */
class TrafficMonitor(
    private val onUpdate: (TrafficSnapshot) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var startRx = 0L
    private var startTx = 0L
    private var prevRx = 0L
    private var prevTx = 0L
    private var prevTime = 0L

    var totalRx = 0L; private set
    var totalTx = 0L; private set
    var sessionStart = 0L; private set

    fun start(uid: Int = android.os.Process.myUid()) {
        stop()
        startRx = android.net.TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
        startTx = android.net.TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
        prevRx = startRx; prevTx = startTx
        prevTime = System.currentTimeMillis()
        sessionStart = prevTime
        totalRx = 0; totalTx = 0

        job = scope.launch {
            while (isActive) {
                delay(1000)
                val uid2 = android.os.Process.myUid()
                val rx = android.net.TrafficStats.getUidRxBytes(uid2).coerceAtLeast(0)
                val tx = android.net.TrafficStats.getUidTxBytes(uid2).coerceAtLeast(0)
                val now = System.currentTimeMillis()
                val dt = (now - prevTime).coerceAtLeast(1)

                val rxSpeed = ((rx - prevRx) * 1000 / dt).coerceAtLeast(0)
                val txSpeed = ((tx - prevTx) * 1000 / dt).coerceAtLeast(0)

                totalRx = (rx - startRx).coerceAtLeast(0)
                totalTx = (tx - startTx).coerceAtLeast(0)

                prevRx = rx; prevTx = tx; prevTime = now
                withContext(Dispatchers.Main) {
                    onUpdate(TrafficSnapshot(totalRx, totalTx, rxSpeed, txSpeed))
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes < 1024       -> "$bytes B"
            bytes < 1024*1024  -> "${bytes/1024} KB"
            else               -> String.format("%.1f MB", bytes/1024.0/1024.0)
        }
        fun formatSpeed(bps: Long): String = when {
            bps < 1024       -> "$bps B/s"
            bps < 1024*1024  -> "${bps/1024} KB/s"
            else             -> String.format("%.1f MB/s", bps/1024.0/1024.0)
        }
    }
}
