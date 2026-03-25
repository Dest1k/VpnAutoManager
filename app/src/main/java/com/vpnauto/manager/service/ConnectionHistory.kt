package com.vpnauto.manager.service

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ConnectionRecord(
    val serverName: String,
    val host: String,
    val protocol: String,
    val connectedAt: Long,
    val disconnectedAt: Long = 0,
    val avgPingMs: Long = -1,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val success: Boolean = true,
    val failReason: String = ""
) {
    val durationSec: Long get() =
        if (disconnectedAt > connectedAt) (disconnectedAt - connectedAt) / 1000 else 0

    val connectedAtFormatted: String get() =
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(connectedAt))

    val durationFormatted: String get() = when {
        durationSec < 60   -> "${durationSec}с"
        durationSec < 3600 -> "${durationSec/60}м"
        else               -> "${durationSec/3600}ч ${(durationSec%3600)/60}м"
    }
}

object ConnectionHistory {
    private const val MAX_RECORDS = 50
    private var _prefs: SharedPreferences? = null
    private val prefs get() = _prefs ?: throw IllegalStateException("ConnectionHistory not initialized. Call init(context) first.")
    private val gson = Gson()

    fun init(context: Context) {
        if (_prefs != null) return
        _prefs = context.getSharedPreferences("conn_history", Context.MODE_PRIVATE)
    }

    fun getAll(): List<ConnectionRecord> {
        val json = prefs.getString("records", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ConnectionRecord>>() {}.type
            (gson.fromJson(json, type) as List<ConnectionRecord>).sortedByDescending { it.connectedAt }
        } catch (_: Exception) { emptyList() }
    }

    fun add(record: ConnectionRecord) {
        val list = getAll().toMutableList()
        list.add(0, record)
        while (list.size > MAX_RECORDS) list.removeAt(list.lastIndex)
        prefs.edit().putString("records", gson.toJson(list)).apply()
    }

    fun updateLast(block: ConnectionRecord.() -> ConnectionRecord) {
        val list = getAll().toMutableList()
        if (list.isEmpty()) return
        list[0] = list[0].block()
        prefs.edit().putString("records", gson.toJson(list)).apply()
    }

    fun clear() = prefs.edit().remove("records").apply()
}
