package com.vpnauto.manager.service

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "Watchdog"

/**
 * Watchdog — проверяет живость VPN каждые 30 секунд.
 * Если 3 проверки подряд провалились — автоматически переподключается.
 */
class Watchdog(
    private val onReconnect: () -> Unit,
    private val onStatusUpdate: (latencyMs: Long) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var failCount = 0

    companion object {
        private const val CHECK_INTERVAL_MS = 30_000L
        private const val MAX_FAILURES = 3
        private const val SOCKS_PORT = 10808
        private const val PROBE_TIMEOUT_MS = 3000
    }

    fun start() {
        stop()
        failCount = 0
        ConnectionLog.i("Watchdog запущен (проверка каждые 30с)")
        job = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                if (!DirectVpnService.isRunning) break

                val latency = probeProxy()
                if (latency >= 0) {
                    failCount = 0
                    onStatusUpdate(latency)
                    ConnectionLog.i("Watchdog: VPN жив ($latency ms)")
                } else {
                    failCount++
                    ConnectionLog.w("Watchdog: проверка провалилась ($failCount/$MAX_FAILURES)")
                    if (failCount >= MAX_FAILURES) {
                        if (DirectVpnService.isConnecting) {
                            ConnectionLog.i("Watchdog: уже идёт подключение, пропускаем")
                        } else {
                            ConnectionLog.e("Watchdog: VPN недоступен — переподключаемся...")
                            failCount = 0
                            withContext(Dispatchers.Main) { onReconnect() }
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun probeProxy(): Long {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), PROBE_TIMEOUT_MS) }
            System.currentTimeMillis() - start
        } catch (_: Exception) { -1L }
    }
}
