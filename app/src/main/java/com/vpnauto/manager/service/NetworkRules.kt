package com.vpnauto.manager.service

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

enum class NetworkAction { CONNECT, DISCONNECT, NOTHING }

data class NetworkRule(
    val id: String,
    val name: String,
    val ssid: String,           // "" = любая сеть
    val networkType: String,    // "wifi" / "mobile" / "any"
    val action: NetworkAction,
    val profileId: String = "" // профиль для подключения
)

object NetworkRules {
    private var _prefs: SharedPreferences? = null
    private val gson = Gson()
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val DEFAULTS = listOf(
        NetworkRule("default_mobile", "📱 Мобильная сеть → VPN",
            ssid = "", networkType = "mobile", action = NetworkAction.CONNECT),
        NetworkRule("default_home",   "🏠 Домашний WiFi → без VPN",
            ssid = "", networkType = "wifi", action = NetworkAction.NOTHING)
    )

    fun init(context: Context) {
        if (_prefs != null) return
        _prefs = context.getSharedPreferences("network_rules", Context.MODE_PRIVATE)
    }

    fun getRules(): List<NetworkRule> {
        val json = _prefs?.getString("rules", null) ?: return DEFAULTS
        return try {
            val type = object : TypeToken<List<NetworkRule>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) { DEFAULTS }
    }

    fun saveRules(rules: List<NetworkRule>) =
        _prefs?.edit()?.putString("rules", gson.toJson(rules))?.apply()

    fun isEnabled(): Boolean = _prefs?.getBoolean("enabled", false) ?: false
    fun setEnabled(v: Boolean) { _prefs?.edit()?.putBoolean("enabled", v)?.apply() }

    fun getCurrentAction(context: Context): NetworkRule? {
        if (!isEnabled()) return null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        val isWifi   = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isMobile = nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val ssid = getCurrentSsid(context)

        return getRules().firstOrNull { rule ->
            when (rule.networkType) {
                "wifi"   -> isWifi   && (rule.ssid.isEmpty() || rule.ssid == ssid)
                "mobile" -> isMobile
                else     -> true
            }
        }
    }

    private fun getCurrentSsid(context: Context): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wm.connectionInfo?.ssid?.trim('"') ?: ""
    }

    fun registerReceiver(context: Context, onNetworkChange: (NetworkRule?) -> Unit) {
        unregisterReceiver(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isEnabled()) return
                onNetworkChange(getCurrentAction(context))
            }
            override fun onLost(network: Network) {
                if (!isEnabled()) return
                onNetworkChange(getCurrentAction(context))
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, cb)
        networkCallback = cb
    }

    fun unregisterReceiver(context: Context) {
        networkCallback?.let {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(it) }
        }
        networkCallback = null
    }
}
