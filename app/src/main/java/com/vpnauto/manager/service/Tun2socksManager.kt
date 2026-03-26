package com.vpnauto.manager.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.TimeUnit

/**
 * Управляет процессом tun2socks.
 *
 * tun2socks читает IP-пакеты из TUN-fd и пересылает их в xray SOCKS5.
 * Это заменяет самописный TunForwarder — не нужно реализовывать TCP-стек.
 *
 * Бинарник: libtun2socks.so (из nativeLibraryDir, скачивается в GitHub Actions)
 *
 * Проблема fd на Android: ProcessBuilder закрывает ВСЕ fd >= 3 в дочернем
 * процессе (UNIXProcess_md.c / childActions). Единственный способ передать
 * TUN fd — пробросить через stdin (fd 0) с помощью Os.dup2(), а tun2socks
 * запускать с -device fd://0.
 */
class Tun2socksManager(private val context: Context) {

    private val binary = File(context.applicationInfo.nativeLibraryDir, "libtun2socks.so")
    private var process: Process? = null

    /** Лок для синхронизации dup2 на stdin (fd 0) — один запуск за раз. */
    private val stdinLock = Any()

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
            FileLogger.log("tun2socks start: TUN fd=${pfd.fd}, socks=127.0.0.1:$socksPort")
            ConnectionLog.i("Запускаем tun2socks: fd=${pfd.fd} → stdin (fd://0)")

            /*
             * Android's ProcessBuilder закрывает все fd >= 3 в дочернем процессе.
             * Единственный способ передать TUN fd — подменить stdin (fd 0):
             *
             * 1. Сохраняем оригинальный stdin: savedStdin = dup(0)
             * 2. dup2(tunFd, 0) — теперь stdin = TUN устройство
             * 3. ProcessBuilder с redirectInput(INHERIT) — дочерний процесс
             *    наследует stdin = TUN, stdout/stderr = pipe для логов
             * 4. tun2socks -device fd://0 — читает/пишет TUN через fd 0
             * 5. Восстанавливаем stdin: dup2(savedStdin, 0), close(savedStdin)
             */
            synchronized(stdinLock) {
                val savedStdin = Os.dup(FileDescriptor.`in`)
                try {
                    Os.dup2(pfd.fileDescriptor, 0)
                    FileLogger.log("tun2socks: dup2(tunFd=${pfd.fd}, 0) OK, stdin = TUN")

                    process = ProcessBuilder(
                        binary.absolutePath,
                        "-device",   "fd://0",
                        "-proxy",    "socks5://127.0.0.1:$socksPort",
                        "-loglevel", "debug"
                    )
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectErrorStream(true)
                        .start()
                } finally {
                    Os.dup2(savedStdin, 0)
                    Os.close(savedStdin)
                    FileLogger.log("tun2socks: stdin restored")
                }
            }

            // Читаем вывод в лог (stdout/stderr через pipe)
            Thread {
                try {
                    process?.inputStream?.bufferedReader()?.forEachLine { line ->
                        if (line.isNotBlank()) {
                            FileLogger.log("t2s: $line")
                            ConnectionLog.i("tun2socks: $line")
                        }
                    }
                } catch (_: Exception) {
                    // process.destroy() закрывает поток — ожидаемо при остановке
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
