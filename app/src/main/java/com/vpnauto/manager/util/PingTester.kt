package com.vpnauto.manager.util

import com.vpnauto.manager.model.ServerConfig
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

object PingTester {

    private const val CONNECT_TIMEOUT_MS = 3000
    private const val PING_ATTEMPTS      = 2
    // Размер одного чанка — сколько серверов пингуем одновременно.
    // Не блокируем треды — каждый чанк запускается через async и ждёт через awaitAll.
    private const val CHUNK_SIZE = 80

    /**
     * Тестирует ВСЕ серверы без какого-либо лимита количества.
     * Обрабатываются чанками по [CHUNK_SIZE] параллельных корутин.
     * Поддерживает отмену — достаточно отменить родительский Job.
     */
    suspend fun testServers(
        servers: List<ServerConfig>,
        onProgress: ((tested: Int, total: Int) -> Unit)? = null
    ): List<ServerConfig> = withContext(Dispatchers.IO) {
        val total   = servers.size
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        val results = ArrayList<ServerConfig>(total)

        for (chunk in servers.chunked(CHUNK_SIZE)) {
            if (!isActive) break                         // поддержка отмены
            val chunkResults = chunk.map { server ->
                async {
                    if (!isActive) return@async server
                    val ping = tcpPing(server.host, server.port)
                    val done = counter.incrementAndGet()
                    // onProgress всегда вызываем на Main — безопасно и для postValue, и для прямого обновления View
                    withContext(Dispatchers.Main) { onProgress?.invoke(done, total) }
                    server.copy(
                        pingMs      = if (ping < Long.MAX_VALUE) ping else -1L,
                        isReachable = ping < Long.MAX_VALUE
                    )
                }
            }.awaitAll()
            results.addAll(chunkResults)
        }

        results.sortedWith(
            compareByDescending<ServerConfig> { it.isReachable }.thenBy { it.pingMs }
        )
    }

    private fun tcpPing(host: String, port: Int): Long {
        var best = Long.MAX_VALUE
        repeat(PING_ATTEMPTS) {
            try {
                val t0 = System.currentTimeMillis()
                Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
                val elapsed = System.currentTimeMillis() - t0
                if (elapsed < best) best = elapsed
            } catch (_: Exception) { }
        }
        return best
    }

    suspend fun pingOne(server: ServerConfig): ServerConfig = withContext(Dispatchers.IO) {
        val ping = tcpPing(server.host, server.port)
        server.copy(
            pingMs      = if (ping < Long.MAX_VALUE) ping else -1L,
            isReachable = ping < Long.MAX_VALUE
        )
    }
}
