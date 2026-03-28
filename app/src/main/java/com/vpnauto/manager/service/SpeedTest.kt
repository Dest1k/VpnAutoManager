package com.vpnauto.manager.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class SpeedTestResult(
    val downloadMbps: Double,
    val uploadMbps: Double,
    val pingMs: Long
)

object SpeedTest {
    // Небольшой публичный файл для теста скорости
    private val DOWNLOAD_URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=5000000",  // 5MB через Cloudflare
        "https://httpbin.org/bytes/5000000",
        "http://speedtest.tele2.net/1MB.zip"
    )
    private val UPLOAD_URLS = listOf(
        "https://speed.cloudflare.com/__up",
        "https://httpbin.org/post"
    )

    /**
     * Создаём клиент с явным SOCKS5-прокси на xray-порт.
     * Приложение исключено из VPN через addDisallowedApplication, поэтому без явного прокси
     * весь трафик идёт напрямую в интернет, минуя тоннель.
     * С SOCKS5-прокси: SpeedTest → xray SOCKS5 → VLESS/VMess → VPN-сервер → интернет.
     */
    private fun buildClient(): OkHttpClient {
        val socksProxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress("127.0.0.1", DirectVpnService.socksPort)
        )
        return OkHttpClient.Builder()
            .proxy(socksProxy)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun run(onProgress: (String) -> Unit): SpeedTestResult? {
        if (!DirectVpnService.isRunning) return null
        return runInternal(buildClient(), onProgress)
    }

    // onProgress вызывается на том потоке, откуда вызывается runInternal (обычно Main).
    // Каждая блокирующая операция выполняется в Dispatchers.IO.
    private suspend fun runInternal(client: OkHttpClient, onProgress: (String) -> Unit): SpeedTestResult {
        onProgress("Измеряем пинг...")
        val ping = withContext(Dispatchers.IO) { measurePing(client) }
        currentCoroutineContext().ensureActive()

        onProgress("Пинг: ${ping}ms. Тест загрузки...")
        val down = withContext(Dispatchers.IO) { measureDownload(client) }
        currentCoroutineContext().ensureActive()

        onProgress("Загрузка: ${String.format("%.1f", down)} Мбит/с. Тест отдачи...")
        val up = withContext(Dispatchers.IO) { measureUpload(client) }
        currentCoroutineContext().ensureActive()

        onProgress("Готово!")
        return SpeedTestResult(down, up, ping)
    }

    private fun measurePing(client: OkHttpClient): Long {
        return try {
            val start = System.currentTimeMillis()
            client.newCall(Request.Builder()
                .url("https://speed.cloudflare.com/cdn-cgi/trace")
                .head().build()).execute().close()
            System.currentTimeMillis() - start
        } catch (_: Exception) { 0L }
    }

    private fun measureDownload(client: OkHttpClient): Double {
        for (url in DOWNLOAD_URLS) {
            try {
                val req = Request.Builder().url(url).build()
                val start = System.currentTimeMillis()
                val resp = client.newCall(req).execute()
                val bytes = resp.body?.bytes()?.size?.toLong() ?: continue
                val sec = (System.currentTimeMillis() - start) / 1000.0
                if (sec > 0) return bytes * 8 / sec / 1_000_000
            } catch (_: Exception) { continue }
        }
        return 0.0
    }

    private fun measureUpload(client: OkHttpClient): Double {
        // Измеряем только время отправки тела запроса (до получения ответа сервера),
        // иначе в результат входит и время обработки на сервере.
        try {
            val data = ByteArray(1_000_000)  // 1 MB upload
            val start = System.currentTimeMillis()
            val body = data.toRequestBody("application/octet-stream".toMediaType())
            val req = Request.Builder()
                .url(UPLOAD_URLS[0])
                .post(body).build()
            client.newCall(req).execute().use { /* consume to release connection */ }
            val sec = (System.currentTimeMillis() - start) / 1000.0
            return if (sec > 0) data.size * 8 / sec / 1_000_000 else 0.0
        } catch (_: Exception) { return 0.0 }
    }
}
