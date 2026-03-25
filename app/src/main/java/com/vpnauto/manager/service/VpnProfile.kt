package com.vpnauto.manager.service

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class VpnProfile(
    val id: String,
    val name: String,
    val subscriptionIds: List<String>,   // подписки для этого профиля
    val preferProtocol: String = "",     // vless / vmess / ss / "" = любой
    val killSwitch: Boolean = true,
    val autoReconnect: Boolean = true,
    val icon: String = "🌐"
)

object ProfileManager {

    private var _prefs: SharedPreferences? = null
    private val prefs get() = _prefs!!
    private val gson = Gson()

    private val DEFAULTS = listOf(
        VpnProfile("work",  "💼 Работа",          listOf("black_vless_mobile"), killSwitch = true),
        VpnProfile("fast",  "⚡ Максимум скорость", listOf("black_vless_mobile", "black_ss_all")),
        VpnProfile("safe",  "🔒 Безопасный",        listOf("white_cidr_mobile"),  killSwitch = true),
        VpnProfile("all",   "🌐 Все источники",     listOf("black_vless_mobile", "black_ss_all", "white_cidr_mobile"))
    )

    fun init(context: Context) {
        if (_prefs != null) return
        _prefs = context.getSharedPreferences("vpn_profiles", Context.MODE_PRIVATE)
    }

    fun getProfiles(): List<VpnProfile> {
        val json = prefs.getString("profiles", null) ?: return DEFAULTS
        return try {
            val type = object : TypeToken<List<VpnProfile>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) { DEFAULTS }
    }

    fun saveProfiles(list: List<VpnProfile>) =
        prefs.edit().putString("profiles", gson.toJson(list)).apply()

    fun getActive(): VpnProfile? {
        val id = prefs.getString("active_profile", null) ?: return null
        return getProfiles().find { it.id == id }
    }

    fun setActive(profileId: String) =
        prefs.edit().putString("active_profile", profileId).apply()

    fun addProfile(p: VpnProfile) {
        val list = getProfiles().toMutableList()
        list.removeIf { it.id == p.id }
        list.add(p)
        saveProfiles(list)
    }

    fun deleteProfile(id: String) {
        val list = getProfiles().filter { it.id != id }
        saveProfiles(list)
    }
}
