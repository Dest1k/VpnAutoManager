package com.vpnauto.manager.service

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Пишет логи в /storage/emulated/0/Download/VpnGuard/
 * Не требует никаких разрешений начиная с Android 10 для папки скачиваний.
 * Fallback: внутреннее хранилище приложения.
 */
object FileLogger {

    private var logFile: File? = null
    private val timeFmt  = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val stampFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** Если false — лог в файл не пишется (только logcat). */
    var fileWriteEnabled: Boolean = true

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        fileWriteEnabled = prefs.getBoolean("file_logging", true)

        logFile = try {
            val dl  = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(dl, "VpnGuard").also { it.mkdirs() }
            File(dir, "vpn_${stampFmt.format(Date())}.log")
        } catch (_: Exception) {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "vpn.log")
        }
        log("=== VPN Guard log file=${logFile?.absolutePath} enabled=$fileWriteEnabled ===")
    }

    /** Записать строку в файл + Android logcat */
    fun log(msg: String) {
        android.util.Log.d("VpnFileLog", msg)
        if (!fileWriteEnabled) return
        val line = "${timeFmt.format(Date())} $msg\n"
        try { logFile?.appendText(line) } catch (_: Exception) {}
    }

    /** Записать исключение со стектрейсом */
    fun logThrowable(tag: String, t: Throwable) {
        log("$tag CRASH: ${t.javaClass.name}: ${t.message}")
        t.stackTrace.take(20).forEach { log("   at $it") }
        t.cause?.let { cause ->
            log("   Caused by: ${cause.javaClass.name}: ${cause.message}")
            cause.stackTrace.take(10).forEach { log("   at $it") }
        }
    }

    fun getPath(): String = logFile?.absolutePath ?: "N/A"

    fun getLogFile(): File? = logFile
}
