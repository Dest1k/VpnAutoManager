package com.vpnauto.manager.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface

private const val TAG = "HotspotManager"

object HotspotManager {

    /** Возвращает IP-адрес точки доступа (обычно 192.168.43.1 или 192.168.1.1) */
    fun getHotspotIp(): String? {
        return try {
            // Перебираем все сетевые интерфейсы — ищем ap0, wlan1, rndis, swlan
            val hotspotPrefixes = listOf("ap", "wlan1", "swlan", "rndis", "usb")
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { iface ->
                    iface.isUp && !iface.isLoopback &&
                    hotspotPrefixes.any { iface.name.startsWith(it) }
                }
                ?.flatMap { iface -> iface.inetAddresses.asSequence() }
                ?.filter { addr -> addr is java.net.Inet4Address && !addr.isLoopbackAddress }
                ?.map { it.hostAddress }
                ?.firstOrNull()
                ?: fallbackHotspotIp()
        } catch (e: Exception) {
            Log.w(TAG, "getHotspotIp failed: ${e.message}")
            null
        }
    }

    /** Проверяет, активна ли точка доступа (через reflection — единственный способ без root) */
    @Suppress("UNCHECKED_CAST")
    fun isHotspotEnabled(context: Context): Boolean {
        return try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wm) as Boolean
        } catch (e: Exception) {
            // На Android 13+ метод может быть недоступен — проверяем по наличию интерфейса
            getHotspotIp() != null
        }
    }

    /** Запасной вариант: ищем IP в диапазоне 192.168.x.1 кроме 192.168.0.1/192.168.1.1 */
    private fun fallbackHotspotIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filter { addr ->
                    addr is java.net.Inet4Address &&
                    !addr.isLoopbackAddress &&
                    addr.hostAddress?.startsWith("192.168.") == true &&
                    !addr.hostAddress!!.startsWith("192.168.0.") &&
                    !addr.hostAddress!!.startsWith("192.168.1.")
                }
                ?.map { it.hostAddress }
                ?.firstOrNull()
        } catch (_: Exception) { null }
    }

    /** Подсеть хотспота для отображения пользователю */
    fun getHotspotSubnet(hotspotIp: String): String {
        val parts = hotspotIp.split(".")
        return "${parts[0]}.${parts[1]}.${parts[2]}.0/24"
    }
}
