package com.vpnauto.manager.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun run(onProgress: (String) -> Unit): SpeedTestResult? {
        if (!DirectVpnService.isRunning) return null
        return runInternal(onProgress)
    }

    private suspend fun runInternal(onProgress: (String) -> Unit): SpeedTestResult =
        withContext(Dispatchers.IO) {
            onProgress("Измеряем пинг...")
            val ping = measurePing()
            onProgress("Пинг: ${ping}ms. Тест загрузки...")
            val down = measureDownload(onProgress)
            onProgress("Загрузка: ${String.format("%.1f", down)} Мбит/с. Тест отдачи...")
            val up = measureUpload(onProgress)
            onProgress("Готово!")
            SpeedTestResult(down, up, ping)
        }

    private fun measurePing(): Long {
        return try {
            val start = System.currentTimeMillis()
            client.newCall(Request.Builder()
                .url("https://speed.cloudflare.com/cdn-cgi/trace")
                .head().build()).execute().close()
            System.currentTimeMillis() - start
        } catch (_: Exception) { -1L }
    }

    private fun measureDownload(onProgress: (String) -> Unit): Double {
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

    private fun measureUpload(onProgress: (String) -> Unit): Double {
        try {
            val data = ByteArray(1_000_000)  // 1 MB upload
            val body = data.toRequestBody("application/octet-stream".toMediaType())
            val req = Request.Builder()
                .url(UPLOAD_URLS[0])
                .post(body).build()
            val start = System.currentTimeMillis()
            client.newCall(req).execute()
            val sec = (System.currentTimeMillis() - start) / 1000.0
            return if (sec > 0) data.size * 8 / sec / 1_000_000 else 0.0
        } catch (_: Exception) { return 0.0 }
    }
}
