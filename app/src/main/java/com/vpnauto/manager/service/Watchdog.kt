package com.vpnauto.manager.service

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

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
    @Volatile private var failCount = 0

    companion object {
        private const val CHECK_INTERVAL_MS = 30_000L
        private const val MAX_FAILURES = 3
        private const val PROBE_TIMEOUT_MS = 3000
        private const val HTTP_CHECK_TIMEOUT_MS = 6000
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

    /**
     * Двухуровневая проверка:
     * 1. TCP-пинг SOCKS5-порта xray (быстро, для отображения latency).
     * 2. Если TCP-пинг прошёл — HTTP HEAD через SOCKS5, чтобы убедиться что
     *    VPN-тоннель до удалённого сервера живой (xray процесс может быть жив,
     *    но сам сервер может не отвечать → ERR_CONNECTION_CLOSED у пользователя).
     * Возвращает latency в мс или -1 при ошибке.
     */
    private fun probeProxy(): Long {
        val port = DirectVpnService.socksPort
        // 1. Быстрый TCP-пинг для latency
        val tcpLatency = try {
            val start = System.currentTimeMillis()
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS) }
            System.currentTimeMillis() - start
        } catch (_: Exception) { return -1L }

        // 2. Реальная проверка интернета через прокси
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val conn = (URL("https://connectivitycheck.gstatic.com/generate_204")
                .openConnection(proxy) as java.net.HttpURLConnection).apply {
                connectTimeout = HTTP_CHECK_TIMEOUT_MS
                readTimeout    = HTTP_CHECK_TIMEOUT_MS
                requestMethod  = "HEAD"
            }
            val code = conn.responseCode
            conn.disconnect()
            if (code == 204) tcpLatency else -1L
        } catch (_: Exception) { -1L }
    }
}
