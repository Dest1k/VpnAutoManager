package com.vpnauto.manager.service

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean,
    var isBypass: Boolean = false   // true = идёт мимо VPN напрямую
)

object SplitTunneling {

    private var _prefs: SharedPreferences? = null
    private val prefs get() = _prefs ?: throw IllegalStateException("SplitTunneling not initialized. Call init(context) first.")
    private val gson = Gson()

    fun init(context: Context) {
        if (_prefs != null) return
        _prefs = context.getSharedPreferences("split_tunnel", Context.MODE_PRIVATE)
    }

    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val bypass = getBypassPackages()
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .map { info ->
                AppInfo(
                    packageName = info.packageName,
                    appName = pm.getApplicationLabel(info).toString(),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isBypass = info.packageName in bypass
                )
            }
            .sortedWith(compareBy({ it.isSystem }, { it.appName }))
    }

    fun getBypassPackages(): Set<String> {
        val json = prefs.getString("bypass_packages", null) ?: return emptySet()
        return try {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) { emptySet() }
    }

    fun setBypass(packageName: String, bypass: Boolean) {
        val set = getBypassPackages().toMutableSet()
        if (bypass) set.add(packageName) else set.remove(packageName)
        prefs.edit().putString("bypass_packages", gson.toJson(set)).apply()
    }

    fun isEnabled(): Boolean = prefs.getBoolean("enabled", false)
    fun setEnabled(value: Boolean) = prefs.edit().putBoolean("enabled", value).apply()
}
