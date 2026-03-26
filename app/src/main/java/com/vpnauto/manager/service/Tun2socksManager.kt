package com.vpnauto.manager.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
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
     * Подготавливает fd для наследования дочерним процессом (tun2socks).
     *
     * Android/Linux по умолчанию ставит CLOEXEC на дескрипторы — дочерний
     * процесс НЕ наследует fd. Снимаем CLOEXEC, чтобы tun2socks получил
     * рабочий fd. Используем "fd://<N>" — формат, который tun2socks понимает
     * напрямую, без обращения к /proc/self/fd/ (который указывает на таблицу
     * fd дочернего процесса, а не родителя).
     */
    private fun tunDevicePath(pfd: ParcelFileDescriptor): String {
        val fdNum = pfd.fd
        try {
            // Снимаем CLOEXEC — fd будет унаследован дочерним процессом
            val flags = Os.fcntl(pfd.fileDescriptor, OsConstants.F_GETFD)
            Os.fcntl(pfd.fileDescriptor, OsConstants.F_SETFD, flags and OsConstants.FD_CLOEXEC.inv())
            FileLogger.log("TUN fd=$fdNum: CLOEXEC cleared for child process inheritance")
        } catch (e: Exception) {
            FileLogger.log("WARNING: failed to clear CLOEXEC on fd=$fdNum: ${e.message}")
        }
        val devicePath = "fd://$fdNum"
        FileLogger.log("TUN device path: $devicePath (fd=$fdNum)")
        return devicePath
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
