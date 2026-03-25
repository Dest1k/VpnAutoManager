package com.vpnauto.manager.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vpnauto.manager.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "SubRepo"

class SubscriptionRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vpn_auto_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private const val REPO   = "igareck/vpn-configs-for-russia"
        private const val BRANCH = "main"

        /**
         * Зеркала для обхода блокировки raw.githubusercontent.com в РФ.
         * Порядок: сначала самые надёжные CDN.
         */
        fun mirrors(file: String): List<String> = listOf(
            // jsDelivr — глобальный CDN, стабильно работает в РФ
            "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/$file",
            // ghproxy.net — популярное зеркало GitHub
            "https://ghproxy.net/https://raw.githubusercontent.com/$REPO/refs/heads/$BRANCH/$file",
            // ghproxy.com — альтернативное зеркало
            "https://ghproxy.com/https://raw.githubusercontent.com/$REPO/refs/heads/$BRANCH/$file",
            // fastgit — ещё одно зеркало
            "https://raw.fastgit.org/$REPO/$BRANCH/$file",
            // Оригинал — последний вариант
            "https://raw.githubusercontent.com/$REPO/refs/heads/$BRANCH/$file"
        )

        // Маппинг id → имя файла для восстановления зеркал у старых записей
        private val ID_TO_FILE = mapOf(
            "black_vless_mobile"  to "BLACK_VLESS_RUS_mobile.txt",
            "black_vless_full"    to "BLACK_VLESS_RUS.txt",
            "black_ss_all"        to "BLACK_SS+All_RUS.txt",
            "white_cidr_mobile"   to "Vless-Reality-White-Lists-Rus-Mobile.txt",
            "white_cidr_all"      to "WHITE-CIDR-RU-all.txt",
            "white_cidr_checked"  to "WHITE-CIDR-RU-checked.txt",
            "white_sni_all"       to "WHITE-SNI-RU-all.txt"
        )

        val DEFAULT_SUBSCRIPTIONS = listOf(
            sub("black_vless_mobile", "⚫ VLESS (телефон, 100 конфигов)",
                "BLACK_VLESS_RUS_mobile.txt", SubscriptionType.BLACK_VLESS_MOBILE, true),
            sub("black_vless_full",   "⚫ VLESS (полная подписка)",
                "BLACK_VLESS_RUS.txt",        SubscriptionType.BLACK_VLESS_FULL),
            sub("black_ss_all",       "⚫ SS + Hysteria2 + VMess + Trojan",
                "BLACK_SS+All_RUS.txt",       SubscriptionType.BLACK_SS_ALL),
            sub("white_cidr_mobile",  "⚪ CIDR (телефон, 100 конфигов)",
                "Vless-Reality-White-Lists-Rus-Mobile.txt", SubscriptionType.WHITE_CIDR_MOBILE),
            sub("white_cidr_all",     "⚪ CIDR (полная, все хостеры)",
                "WHITE-CIDR-RU-all.txt",      SubscriptionType.WHITE_CIDR_ALL),
            sub("white_cidr_checked", "⚪ CIDR (VK, Яндекс, Beeline)",
                "WHITE-CIDR-RU-checked.txt",  SubscriptionType.WHITE_CIDR_CHECKED),
            sub("white_sni_all",      "⚪ SNI (российские домены)",
                "WHITE-SNI-RU-all.txt",       SubscriptionType.WHITE_SNI_ALL)
        )

        private fun sub(
            id: String, name: String, file: String,
            type: SubscriptionType, enabled: Boolean = false
        ) = Subscription(
            id          = id,
            name        = name,
            url         = mirrors(file).first(),
            mirrorUrls  = mirrors(file),
            type        = type,
            isEnabled   = enabled
        )
    }

    // ─── Хранение подписок ───────────────────────────────────────

    fun getSubscriptions(): List<Subscription> {
        val json = prefs.getString("subscriptions", null) ?: return DEFAULT_SUBSCRIPTIONS
        return try {
            val type = object : TypeToken<List<Subscription>>() {}.type
            val saved: List<Subscription> = gson.fromJson(json, type)
            // Восстанавливаем mirrorUrls для старых записей без них
            saved.map { sub ->
                if (sub.mirrorUrls.isEmpty()) {
                    val file = ID_TO_FILE[sub.id]
                    if (file != null)
                        sub.copy(url = mirrors(file).first(), mirrorUrls = mirrors(file))
                    else
                        sub
                } else sub
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load subscriptions: ${e.message}")
            DEFAULT_SUBSCRIPTIONS
        }
    }

    fun saveSubscriptions(subs: List<Subscription>) {
        prefs.edit().putString("subscriptions", gson.toJson(subs)).apply()
    }

    fun updateSubscription(sub: Subscription) {
        val list = getSubscriptions().toMutableList()
        val idx  = list.indexOfFirst { it.id == sub.id }
        if (idx >= 0) list[idx] = sub else list.add(sub)
        saveSubscriptions(list)
    }

    // ─── Кеш серверов ───────────────────────────────────────────

    fun getCachedServers(subId: String): List<ServerConfig> {
        val json = prefs.getString("servers_$subId", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ServerConfig>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) { emptyList() }
    }

    fun saveServers(subId: String, servers: List<ServerConfig>) {
        prefs.edit().putString("servers_$subId", gson.toJson(servers)).apply()
    }

    fun getAllCachedServers(): List<ServerConfig> =
        getSubscriptions().filter { it.isEnabled }.flatMap { getCachedServers(it.id) }

    // ─── Настройки ───────────────────────────────────────────────

    var updateIntervalHours: Int
        get() = prefs.getInt("update_interval", 2)
        set(v) { prefs.edit().putInt("update_interval", v).apply() }

    var autoConnect: Boolean
        get() = prefs.getBoolean("auto_connect", true)
        set(v) { prefs.edit().putBoolean("auto_connect", v).apply() }

    var pingOnUpdate: Boolean
        get() = prefs.getBoolean("ping_on_update", true)
        set(v) { prefs.edit().putBoolean("ping_on_update", v).apply() }

    var lastUpdateTime: Long
        get() = prefs.getLong("last_update_time", 0L)
        set(v) { prefs.edit().putLong("last_update_time", v).apply() }

    // ─── Загрузка подписки (пробует все зеркала по очереди) ──────

    suspend fun fetchSubscription(sub: Subscription): Result<List<ServerConfig>> =
        withContext(Dispatchers.IO) {
            // Зеркала: берём из mirrorUrls, восстанавливаем если пустые
            val urls = sub.mirrorUrls.ifEmpty {
                val file = ID_TO_FILE[sub.id]
                if (file != null) mirrors(file) else listOf(sub.url)
            }

            var lastError: Exception = Exception("No URLs")

            for (url in urls) {
                try {
                    Log.d(TAG, "Trying: $url")
                    val response = httpClient.newCall(
                        Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 VpnAutoManager/1.0")
                            .header("Accept", "text/plain, */*")
                            .build()
                    ).execute()

                    if (!response.isSuccessful) {
                        lastError = Exception("HTTP ${response.code} from $url")
                        Log.w(TAG, "HTTP ${response.code} from $url")
                        response.close()
                        continue
                    }

                    val body = response.body?.string()?.trim()
                    if (body.isNullOrEmpty()) {
                        lastError = Exception("Empty body from $url")
                        Log.w(TAG, "Empty body from $url")
                        continue
                    }

                    Log.d(TAG, "OK: $url (${body.length} bytes)")
                    File(context.cacheDir, "${sub.id}.txt").writeText(body)

                    val servers = ConfigParser.parseSubscription(body)
                    Log.d(TAG, "Parsed ${servers.size} servers from ${sub.id}")

                    saveServers(sub.id, servers)
                    updateSubscription(sub.copy(
                        lastUpdated = System.currentTimeMillis(),
                        serverCount = servers.size,
                        url         = url,
                        mirrorUrls  = urls
                    ))
                    // Сохранить рабочее зеркало как основной URL
                    if (url != sub.url) updateSubscription(sub.copy(url = url))
                    return@withContext Result.success(servers)

                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Error $url: ${e.message}")
                    continue
                }
            }

            Log.e(TAG, "All ${urls.size} mirrors failed for ${sub.id}: ${lastError.message}")
            Result.failure(lastError)
        }

    suspend fun fetchAllEnabledSubscriptions(): Map<String, Result<List<ServerConfig>>> {
        val results = mutableMapOf<String, Result<List<ServerConfig>>>()
        getSubscriptions().filter { it.isEnabled }.forEach { sub ->
            results[sub.id] = fetchSubscription(sub)
        }
        return results
    }
}
