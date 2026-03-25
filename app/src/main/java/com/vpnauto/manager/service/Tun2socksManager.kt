package com.vpnauto.manager.service

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Управляет процессом tun2socks.
 *
 * tun2socks читает IP-пакеты из TUN-fd и пересылает их в xray SOCKS5.
 * Это заменяет самописный TunForwarder — не нужно реализовывать TCP-стек.
 *
 * Бинарник: libtun2socks.so (из nativeLibraryDir, скачивается в GitHub Actions)
 * Команда: tun2socks -device fd://<N> -proxy socks5://127.0.0.1:10808
 */
class Tun2socksManager(private val context: Context) {

    private val binary = File(context.applicationInfo.nativeLibraryDir, "libtun2socks.so")
    private var process: Process? = null

    val isInstalled: Boolean
        get() {
            val ok = binary.exists() && binary.length() > 100_000 && binary.canExecute()
            FileLogger.log("tun2socks check: ${binary.absolutePath} exists=${binary.exists()} size=${binary.length()} exec=$ok")
            return ok
        }

    /**
     * Запускает tun2socks.
     * @param pfd  ParcelFileDescriptor от VpnService.Builder.establish()
     * @param socksPort порт xray SOCKS5 (обычно 10808)
     */
    fun start(pfd: ParcelFileDescriptor, socksPort: Int = 10808): Boolean {
        stop()
        if (!isInstalled) {
            FileLogger.log("ERROR: tun2socks не найден: ${binary.absolutePath}")
            ConnectionLog.e("tun2socks не найден: ${binary.absolutePath}")
            listNativeLibs()
            return false
        }

        return try {
            val devicePath = tunDevicePath(pfd)
            FileLogger.log("tun2socks start: device=$devicePath socks=127.0.0.1:$socksPort")
            ConnectionLog.i("Запускаем tun2socks: device=$devicePath")

            process = ProcessBuilder(
                binary.absolutePath,
                "-device",   devicePath,
                "-proxy",    "socks5://127.0.0.1:$socksPort",
                "-loglevel", "debug"
            )
                .redirectErrorStream(true)
                .start()

            // Читаем вывод в лог
            Thread {
                process?.inputStream?.bufferedReader()?.forEachLine { line ->
                    if (line.isNotBlank()) {
                        FileLogger.log("t2s: $line")
                        ConnectionLog.i("tun2socks: $line")
                    }
                }
            }.apply { isDaemon = true; start() }

            Thread.sleep(400)
            val alive = process?.isAlive == true
            if (alive) {
                FileLogger.log("tun2socks запущен успешно")
                ConnectionLog.ok("tun2socks запущен!")
            } else {
                FileLogger.log("ERROR: tun2socks завершился сразу!")
                ConnectionLog.e("tun2socks завершился сразу после старта!")
            }
            alive
        } catch (e: Exception) {
            FileLogger.logThrowable("tun2socks start", e)
            ConnectionLog.e("tun2socks ошибка запуска: ${e.message}")
            false
        }
    }

    fun stop() {
        process?.let {
            it.destroy()
            runCatching { it.waitFor(2, TimeUnit.SECONDS) }
            FileLogger.log("tun2socks остановлен")
            ConnectionLog.i("tun2socks остановлен")
        }
        process = null
    }

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * На Android Os.fcntl недоступен напрямую.
     * Используем /proc/self/fd/<N> — симлинк на открытый дескриптор,
     * tun2socks поддерживает этот путь как устройство.
     */
    private fun tunDevicePath(pfd: ParcelFileDescriptor): String {
        val fdNum = pfd.fd
        // Попробуем /proc/self/fd/<N> — работает на Android
        val procPath = "/proc/self/fd/$fdNum"
        FileLogger.log("TUN device path: $procPath (fd=$fdNum)")
        return procPath
    }

    private fun listNativeLibs() {
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val files = dir.listFiles()
        if (files.isNullOrEmpty()) {
            FileLogger.log("nativeLibraryDir is EMPTY: ${dir.absolutePath}")
        } else {
            FileLogger.log("nativeLibraryDir contents (${dir.absolutePath}):")
            files.forEach { FileLogger.log("  ${it.name} ${it.length()/1024}KB") }
        }
    }
}
