package com.vpnauto.manager.util

import android.util.Log
import com.vpnauto.manager.model.ConfigParser
import com.vpnauto.manager.model.ServerConfig
import com.vpnauto.manager.service.ConnectionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "SubFinder"

data class FoundSubscription(
    val url: String,
    val sourceName: String,
    val description: String,
    val serverCount: Int = 0,
    val isReachable: Boolean = false
)

/**
 * Ищет публичные VPN-подписки из известных открытых источников.
 * Все источники — публичные репозитории и агрегаторы бесплатных конфигов.
 */
object SubscriptionFinder {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ─── База известных публичных источников ─────────────────────
    // Только публичные репозитории с открытыми бесплатными конфигами
    private val KNOWN_SOURCES = listOf(
        // ── Проверенные репозитории ──────────────────────────────
        FoundSubscription(
            url = "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS_mobile.txt",
            sourceName = "igareck/vpn-configs-for-russia",
            description = "⚫ VLESS для РФ — проверенные, обновляются каждый час"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_SS%2BAll_RUS.txt",
            sourceName = "igareck/vpn-configs-for-russia",
            description = "⚫ SS+Hysteria2+VMess+Trojan для РФ"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            sourceName = "igareck/vpn-configs-for-russia",
            description = "⚪ VLESS белые списки для РФ"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge_base64.txt",
            sourceName = "mahdibland/V2RayAggregator",
            description = "Агрегатор: VLESS+VMess+Trojan+SS (авто-обновление)"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge_yaml.txt",
            sourceName = "mahdibland/V2RayAggregator",
            description = "Агрегатор: полная подписка YAML"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/freefq/free/master/v2",
            sourceName = "freefq/free",
            description = "Бесплатные V2Ray конфиги (авто-обновление)"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/aiboboxx/v2rayfree/main/v2",
            sourceName = "aiboboxx/v2rayfree",
            description = "Бесплатные V2Ray конфиги"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/mfuu/v2ray/master/v2ray",
            sourceName = "mfuu/v2ray",
            description = "Публичные V2Ray конфиги"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/snakem982/proxypool/main/source/clash-meta.yaml",
            sourceName = "snakem982/proxypool",
            description = "Публичный пул прокси (Clash Meta)"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list.txt",
            sourceName = "peasoft/NoMoreWalls",
            description = "Агрегатор публичных конфигов"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub",
            sourceName = "Pawdroid/Free-servers",
            description = "Бесплатные серверы (Base64)"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt",
            sourceName = "barry-far/V2ray-Configs",
            description = "Публичные V2ray конфиги #1"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub2.txt",
            sourceName = "barry-far/V2ray-Configs",
            description = "Публичные V2ray конфиги #2"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub3.txt",
            sourceName = "barry-far/V2ray-Configs",
            description = "Публичные V2ray конфиги #3"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/vveg26/chromego_merge/main/sub/merged_proxies_new.yaml",
            sourceName = "vveg26/chromego_merge",
            description = "Агрегатор Clash конфигов"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/ts-sf/fly/main/v2",
            sourceName = "ts-sf/fly",
            description = "Публичные конфиги"
        ),
        FoundSubscription(
            url = "https://raw.githubusercontent.com/a2470982985/getNode/main/v2ray.txt",
            sourceName = "a2470982985/getNode",
            description = "Агрегатор нод"
        ),
    )

    // ─── Зеркала для обхода блокировки raw.githubusercontent.com ─
    private fun mirrorUrl(url: String): List<String> {
        if (!url.contains("raw.githubusercontent.com")) return listOf(url)
        return listOf(
            url.replace("https://raw.githubusercontent.com",
                "https://cdn.jsdelivr.net/gh")
                .replace("/refs/heads/main/", "@main/")
                .replace("/master/", "@master/"),
            url.replace("https://raw.githubusercontent.com",
                "https://mirror.ghproxy.com/https://raw.githubusercontent.com"),
            url
        )
    }

    /**
     * Проверяет все известные источники — скачивает, считает серверы.
     * Возвращает список с результатами по мере проверки через [onResult].
     */
    suspend fun findAll(
        onProgress: ((checked: Int, total: Int, name: String) -> Unit)? = null,
        onResult: ((FoundSubscription) -> Unit)? = null
    ): List<FoundSubscription> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FoundSubscription>()
        val total = KNOWN_SOURCES.size

        for ((i, source) in KNOWN_SOURCES.withIndex()) {
            if (!isActive) break
            withContext(Dispatchers.Main) {
                onProgress?.invoke(i + 1, total, source.sourceName)
            }
            ConnectionLog.i("Проверяем: ${source.sourceName}")

            val checked = checkSource(source)
            results.add(checked)
            if (checked.isReachable) {
                ConnectionLog.ok("✓ ${source.sourceName}: ${checked.serverCount} серверов")
                withContext(Dispatchers.Main) { onResult?.invoke(checked) }
            } else {
                ConnectionLog.w("✗ ${source.sourceName}: недоступен")
            }
        }

        results.sortedByDescending { it.serverCount }
    }

    private fun checkSource(source: FoundSubscription): FoundSubscription {
        val urls = mirrorUrl(source.url)
        for (url in urls) {
            try {
                val resp = httpClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 VpnGuard/1.0")
                        .build()
                ).execute()

                if (!resp.isSuccessful) continue
                val body = resp.body?.string()?.trim() ?: continue
                if (body.length < 50) continue

                // Посчитать серверы
                val servers = ConfigParser.parseSubscription(body)
                if (servers.isNotEmpty()) {
                    return source.copy(
                        url = url, // используем рабочий URL (может быть зеркало)
                        serverCount = servers.size,
                        isReachable = true
                    )
                }
                // Если парсер ничего не нашёл — но ответ не пустой, всё равно считаем рабочим
                return source.copy(url = url, serverCount = 0, isReachable = true)

            } catch (e: Exception) {
                Log.w(TAG, "Error checking ${source.sourceName}: ${e.message}")
                continue
            }
        }
        return source.copy(isReachable = false)
    }

    /** Скачать конкретную подписку и вернуть список серверов */
    suspend fun fetchServers(source: FoundSubscription): List<ServerConfig> =
        withContext(Dispatchers.IO) {
            try {
                val resp = httpClient.newCall(
                    Request.Builder()
                        .url(source.url)
                        .header("User-Agent", "Mozilla/5.0 VpnGuard/1.0")
                        .build()
                ).execute()
                val body = resp.body?.string()?.trim() ?: return@withContext emptyList()
                ConfigParser.parseSubscription(body)
            } catch (e: Exception) {
                Log.w(TAG, "fetchServers failed: ${e.message}")
                emptyList()
            }
        }
}
