package com.vpnauto.manager

import android.app.Application
import com.vpnauto.manager.service.ConnectionHistory
import com.vpnauto.manager.service.FileLogger
import com.vpnauto.manager.service.NetworkRules
import com.vpnauto.manager.service.ProfileManager
import com.vpnauto.manager.service.SplitTunneling

class VpnApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // FileLogger — первым делом, до чего угодно
        FileLogger.init(this)
        FileLogger.log("VpnApp.onCreate: log at ${FileLogger.getPath()}")

        // Перехват необработанных исключений → файл
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            FileLogger.log("=== UNCAUGHT EXCEPTION in thread: ${thread.name} ===")
            FileLogger.logThrowable("CRASH", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Инициализация singleton-ов с Context
        ConnectionHistory.init(this)
        SplitTunneling.init(this)
        ProfileManager.init(this)
        NetworkRules.init(this)

        FileLogger.log("VpnApp init done")
    }
}
