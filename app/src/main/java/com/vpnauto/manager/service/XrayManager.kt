package com.vpnauto.manager.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "XrayManager"

/**
 * Управляет бинарником xray-core.
 *
 * xray включён в APK как нативная библиотека libxray.so и устанавливается
 * в applicationInfo.nativeLibraryDir — единственное место, откуда
 * Android разрешает запускать исполняемые файлы (политика W^X / SELinux).
 *
 * Путь: /data/app/<pkg>/lib/<abi>/libxray.so
 */
class XrayManager(private val context: Context) {

    // Бинарник установлен APK-менеджером в nativeLibraryDir
    private val nativeXray = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
    // Рабочая копия конфига — в filesDir (не исполняется, только читается)
    private val configFile = File(context.filesDir, "xray_config.json")

    private var xrayProcess: Process? = null

    val isInstalled: Boolean
        get() {
            val exists = nativeXray.exists()
            val size   = if (exists) nativeXray.length() else 0L
            val exec   = if (exists) nativeXray.canExecute() else false
            ConnectionLog.i("xray check: path=${nativeXray.absolutePath}, exists=$exists, size=${size/1024}KB, canExec=$exec")
            return exists && size > 500_000
        }

    fun isV2RayNGInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.v2ray.ang", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
    }

    /**
     * Проверяет наличие xray в APK.
     * Если бинарник не найден — значит APK собран без него
     * (например старая версия или ошибка в GitHub Actions).
     */
    suspend fun ensureInstalled(onProgress: ((String) -> Unit)? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            ConnectionLog.i("Проверяем xray: ${nativeXray.absolutePath}")
            ConnectionLog.i("nativeLibraryDir: ${context.applicationInfo.nativeLibraryDir}")
            ConnectionLog.i("Device ABI: ${Build.SUPPORTED_ABIS.take(3).toList()}")

            if (isInstalled) {
                ConnectionLog.ok("xray найден в APK: ${nativeXray.length() / 1024} KB")
                onProgress?.invoke("xray готов")

                // Проверить что действительно запускается
                val ver = runVersion()
                if (ver != null) {
                    ConnectionLog.ok("xray версия: $ver")
                    return@withContext Result.success(Unit)
                } else {
                    ConnectionLog.e("xray есть, но не запускается — ABI несовместимость?")
                    ConnectionLog.e("APK ABI: ${context.applicationInfo.nativeLibraryDir}")
                    ConnectionLog.e("Device: ${Build.SUPPORTED_ABIS.take(3).toList()}")
                    return@withContext Result.failure(Exception(
                        "xray установлен, но не запускается.\n" +
                        "ABI устройства: ${Build.SUPPORTED_ABIS.firstOrNull()}\n" +
                        "Пересоберите APK через GitHub Actions."
                    ))
                }
            }

            // xray не найден — APK собран без него или ошибка при сборке
            ConnectionLog.e("xray НЕ найден в нативных библиотеках APK!")
            ConnectionLog.e("Ожидаемый путь: ${nativeXray.absolutePath}")

            // Перечислить что есть в nativeLibraryDir для диагностики
            val libDir = File(context.applicationInfo.nativeLibraryDir)
            if (libDir.exists()) {
                val files = libDir.listFiles()
                if (files.isNullOrEmpty()) {
                    ConnectionLog.w("nativeLibraryDir пуст")
                } else {
                    ConnectionLog.i("Файлы в nativeLibraryDir:")
                    files.forEach { ConnectionLog.i("  ${it.name} (${it.length()/1024} KB)") }
                }
            } else {
                ConnectionLog.e("nativeLibraryDir не существует: ${libDir.absolutePath}")
            }

            Result.failure(Exception(
                "xray-core не найден в APK.\n\n" +
                "Это значит GitHub Actions не скачал xray при сборке.\n" +
                "Проверьте логи Actions на GitHub → шаг 'Download xray-core binaries'."
            ))
        }

    private fun runVersion(): String? {
        return try {
            val p = ProcessBuilder(nativeXray.absolutePath, "version")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(5, TimeUnit.SECONDS)
            if (out.contains("Xray", ignoreCase = true) || out.contains("xray", ignoreCase = true))
                out.lines().firstOrNull { it.isNotBlank() }?.trim()
            else {
                ConnectionLog.w("version output: ${out.take(200)}")
                null
            }
        } catch (e: Exception) {
            ConnectionLog.w("version check failed: ${e.message}")
            null
        }
    }

    fun start(config: String): Boolean {
        stop()
        // Ждём пока порт 10808 освободится (race-condition после stop/onDestroy)
        waitForPortFree(10808)
        if (!nativeXray.exists()) {
            ConnectionLog.e("start(): xray не найден: ${nativeXray.absolutePath}")
            return false
        }
        return try {
            configFile.writeText(config)
            // Логируем первые 300 символов конфига для диагностики
            ConnectionLog.i("Конфиг (начало): ${config.take(300)}")
            ConnectionLog.i("Запускаем: ${nativeXray.absolutePath}")
            xrayProcess = ProcessBuilder(
                nativeXray.absolutePath, "run", "-c", configFile.absolutePath
            )
                .redirectErrorStream(true)
                .start()

            Thread {
                try {
                    xrayProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                        if (line.isNotBlank()) {
                            val level = when {
                                line.contains("error",   ignoreCase = true)   -> LogLevel.ERROR
                                line.contains("warning", ignoreCase = true)   -> LogLevel.WARN
                                line.contains("started", ignoreCase = true) ||
                                line.contains("ready",   ignoreCase = true)   -> LogLevel.OK
                                else                                          -> LogLevel.INFO
                            }
                            ConnectionLog.add("xray: $line", level)
                        }
                    }
                } catch (_: Exception) {
                    // process.destroy() закрывает поток — InterruptedIOException ожидаем
                }
            }.apply { isDaemon = true; start() }

            Thread.sleep(700)
            val alive = xrayProcess?.isAlive == true
            if (alive) ConnectionLog.ok("xray процесс жив!")
            else ConnectionLog.e("xray процесс завершился сразу!")
            alive
        } catch (e: Exception) {
            ConnectionLog.e("start() исключение: ${e.message}")
            false
        }
    }

    fun stop() {
        val p = xrayProcess ?: return
        xrayProcess = null
        p.destroy()
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                // Не умер за 2 секунды — SIGKILL
                p.destroyForcibly()
                p.waitFor(1, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {}
        ConnectionLog.i("xray остановлен")
    }

    /**
     * Ждёт освобождения порта на 127.0.0.1.
     * Нужно после stop() — ядро асинхронно закрывает сокеты умершего процесса.
     */
    private fun waitForPortFree(port: Int, timeoutMs: Long = 4000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.ServerSocket().use {
                    it.reuseAddress = false
                    it.bind(java.net.InetSocketAddress("127.0.0.1", port))
                }
                return  // Порт свободен
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        ConnectionLog.w("Порт $port не освободился за ${timeoutMs}мс — запускаем xray всё равно")
    }

    fun isRunning(): Boolean = xrayProcess?.isAlive == true


    fun deleteInstallation() {
        nativeXray.let { if (it.exists()) it.delete() }
        configFile.delete()
        ConnectionLog.i("xray installation deleted")
    }
}
