package com.vpnauto.manager.service

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.*

data class LogEntry(
    val time: String,
    val level: LogLevel,
    val message: String
)

enum class LogLevel { INFO, OK, WARN, ERROR }

/**
 * Глобальный синглтон лога подключения.
 * Хранит последние 200 строк, доступен из любого места.
 */
object ConnectionLog {

    private const val MAX_LINES = 200
    private val _entries = MutableLiveData<List<LogEntry>>(emptyList())
    val entries: LiveData<List<LogEntry>> = _entries

    private val list = ArrayDeque<LogEntry>()
    private val fmt  = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @Synchronized
    fun add(message: String, level: LogLevel = LogLevel.INFO) {
        android.util.Log.d("VpnLog", message)
        val entry = LogEntry(fmt.format(Date()), level, message)
        list.addLast(entry)
        while (list.size > MAX_LINES) list.removeFirst()
        _entries.postValue(list.toList())
    }

    fun i(msg: String) = add(msg, LogLevel.INFO)
    fun ok(msg: String) = add(msg, LogLevel.OK)
    fun w(msg: String) = add(msg, LogLevel.WARN)
    fun e(msg: String) = add(msg, LogLevel.ERROR)

    @Synchronized
    fun clear() {
        list.clear()
        _entries.postValue(emptyList())
    }

    @Synchronized
    fun getText(): String = list.joinToString("\n") { "[${it.time}] ${it.level.name}: ${it.message}" }
}
