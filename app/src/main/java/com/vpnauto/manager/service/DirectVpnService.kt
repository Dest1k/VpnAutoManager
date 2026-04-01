package com.vpnauto.manager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vpnauto.manager.R
import com.vpnauto.manager.model.ServerConfig
import com.vpnauto.manager.ui.MainActivity
import kotlinx.coroutines.*

private const val TAG = "DirectVpnService"

const val ACTION_CONNECT        = "com.vpnauto.manager.CONNECT"
const val ACTION_DISCONNECT     = "com.vpnauto.manager.DISCONNECT"
const val ACTION_CANCEL_CONNECT = "com.vpnauto.manager.CANCEL_CONNECT"
const val ACTION_HOTSPOT_SHARE  = "com.vpnauto.manager.HOTSPOT_SHARE"
const val ACTION_VPN_STATUS     = "com.vpnauto.manager.VPN_STATUS"
const val ACTION_HOTSPOT_STATUS = "com.vpnauto.manager.HOTSPOT_STATUS"

const val EXTRA_SERVER_RAW     = "server_raw"
const val EXTRA_SERVER_HOST    = "server_host"
const val EXTRA_SERVER_PORT    = "server_port"
const val EXTRA_SERVER_NAME    = "server_name"
const val EXTRA_SERVER_PROTO   = "server_proto"
const val EXTRA_HOTSPOT_ENABLE = "hotspot_enable"
const val EXTRA_STATUS_MSG     = "message"
const val EXTRA_STATUS_CONN    = "connected"

const val CHANNEL_VPN = "vpn_channel"
const val NOTIF_VPN   = 3001

class DirectVpnService : VpnService() {

    private var tunPfd:      ParcelFileDescriptor? = null
    private var hotspotProxy: LocalProxyServer?    = null
    private var connectJob:   Job?                 = null
    private val xrayManager   by lazy { XrayManager(this) }
    private val tun2socks     by lazy { Tun2socksManager(this) }
    private val scope         = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        @Volatile var isRunning    = false
        @Volatile var isConnecting = false
        @Volatile var connectedServer  = ""
        @Volatile var hotspotProxyActive  = false
        @Volatile var hotspotProxyAddress = ""
        /** Актуальный порт xray SOCKS5. Устанавливается при подключении, нужен Watchdog и hotspot-прокси. */
        @Volatile var socksPort: Int = 10808
        /** true если прокси-проверка провалилась → ViewModel должен переключиться на следующий сервер. */
        @Volatile var serverFailed: Boolean = false

        fun buildConnectIntent(ctx: Context, s: ServerConfig) =
            Intent(ctx, DirectVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_SERVER_RAW,   s.raw)
                putExtra(EXTRA_SERVER_HOST,  s.host)
                putExtra(EXTRA_SERVER_PORT,  s.port)
                putExtra(EXTRA_SERVER_NAME,  s.name)
                putExtra(EXTRA_SERVER_PROTO, s.protocol)
            }

        fun buildDisconnectIntent(ctx: Context) =
            Intent(ctx, DirectVpnService::class.java).apply { action = ACTION_DISCONNECT }

        fun buildCancelIntent(ctx: Context) =
            Intent(ctx, DirectVpnService::class.java).apply { action = ACTION_CANCEL_CONNECT }

        fun buildHotspotIntent(ctx: Context, enable: Boolean) =
            Intent(ctx, DirectVpnService::class.java).apply {
                action = ACTION_HOTSPOT_SHARE
                putExtra(EXTRA_HOTSPOT_ENABLE, enable)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val server = ServerConfig(
                    raw      = intent.getStringExtra(EXTRA_SERVER_RAW)   ?: "",
                    protocol = intent.getStringExtra(EXTRA_SERVER_PROTO)  ?: "vless",
                    host     = intent.getStringExtra(EXTRA_SERVER_HOST)   ?: "",
                    port     = intent.getIntExtra(EXTRA_SERVER_PORT, 443),
                    name     = intent.getStringExtra(EXTRA_SERVER_NAME)   ?: ""
                )
                if (server.host.isEmpty()) {
                    FileLogger.log("ERROR: пустой хост сервера")
                    broadcastError("Некорректный сервер")
                    stopSelf()
                } else {
                    startVpn(server)
                }
            }
            ACTION_CANCEL_CONNECT -> cancelConnect()
            ACTION_DISCONNECT     -> stopVpn()
            ACTION_HOTSPOT_SHARE  -> {
                if (intent.getBooleanExtra(EXTRA_HOTSPOT_ENABLE, false)) startHotspotProxy()
                else stopHotspotProxy()
            }
        }
        return START_NOT_STICKY
    }

    // ─── Запуск VPN ───────────────────────────────────────────────
    private fun startVpn(server: ServerConfig) {
        if (isRunning || isConnecting) {
            FileLogger.log("Переключение на ${server.name}")
            quickCleanup()
        }
        isConnecting = false
        serverFailed = false

        ConnectionLog.clear()
        ConnectionLog.i("═══ Подключение: ${server.name} ═══")
        ConnectionLog.i("Хост: ${server.host}:${server.port}  Proto: ${server.protocol}")
        FileLogger.log("=== Connecting to ${server.name} ${server.host}:${server.port} proto=${server.protocol} ===")

        connectJob = scope.launch {
            isConnecting = true
            try {
                doConnect(server)
            } catch (e: CancellationException) {
                FileLogger.log("Connection cancelled by user")
                ConnectionLog.w("Подключение отменено")
                broadcastStatus(false, "Отменено")
                cleanup()
                if (!isRunning) stopSelf()
            } catch (e: Exception) {
                FileLogger.logThrowable("doConnect", e)
                ConnectionLog.e("ОШИБКА: ${e.message}")
                broadcastError(e.message ?: "Неизвестная ошибка")
                cleanup()
                stopSelf()
            } finally {
                isConnecting = false
            }
        }
    }

    private suspend fun doConnect(server: ServerConfig) {
        // 1. Foreground
        log("Запуск foreground…")
        startForeground(NOTIF_VPN, buildNotification("⏳ ${server.name}"))

        // 2. Проверить xray
        log("Шаг 1/4: xray-core…")
        val xrayResult = xrayManager.ensureInstalled { msg ->
            log(msg); startForeground(NOTIF_VPN, buildNotification(msg))
        }
        if (xrayResult.isFailure) throw Exception(xrayResult.exceptionOrNull()?.message ?: "xray install failed")
        ConnectionLog.ok("xray готов")

        // 3. Запустить xray
        log("Шаг 2/4: запуск xray…")

        // Находим свободный SOCKS5-порт ДО сборки конфига.
        // stop() использует SIGKILL → нет TIME_WAIT → порт свободен сразу после kill.
        // findFreePort() стартует с 10808 и ищет свободный, чтобы не конфликтовать
        // с возможными orphan-процессами от предыдущих сессий.
        val socksPort = withContext(Dispatchers.IO) { xrayManager.findFreePort() }
        DirectVpnService.socksPort = socksPort   // сохраняем для Watchdog и hotspot-прокси

        val config = try {
            XrayConfigBuilder.build(server, socksPort)
        } catch (e: Exception) {
            FileLogger.logThrowable("XrayConfigBuilder", e)
            throw Exception("Ошибка конфига: ${e.message}")
        }
        FileLogger.log("xray config (first 400):\n${config.take(400)}")
        ConnectionLog.i("Конфиг: ${config.length} байт, порт $socksPort")

        val xrayStarted = withContext(Dispatchers.IO) { xrayManager.start(config) }
        if (!xrayStarted) throw Exception("xray не запустился")
        ConnectionLog.ok("xray запущен")

        // 4. Ждём SOCKS5
        log("Шаг 3/4: ждём SOCKS5 порт $socksPort…")
        val portReady = waitForSocksPort(socksPort, attempts = 25, intervalMs = 400)
        if (!portReady) throw Exception("SOCKS5 порт $socksPort не открылся. Проверьте конфиг сервера.")
        ConnectionLog.ok("SOCKS5 готов")

        // 5. Создать TUN
        log("Шаг 4/4: создание TUN-интерфейса…")
        val serverIp = resolveServerIp(server.host)
        val pfd = buildTun(serverIp)
            ?: throw Exception("Не удалось создать TUN-интерфейс")
        tunPfd = pfd
        FileLogger.log("TUN created: fd=${pfd.fd} serverIp=$serverIp")
        ConnectionLog.ok("TUN создан (fd=${pfd.fd})")

        // 6. Запустить tun2socks
        log("Запуск tun2socks…")
        val t2sOk = withContext(Dispatchers.IO) { tun2socks.start(pfd, socksPort) }
        if (!t2sOk) {
            throw Exception("tun2socks не запустился — проверьте права TUN-устройства")
        }

        isRunning = true
        connectedServer = server.name
        broadcastStatus(true, server.name)
        startForeground(NOTIF_VPN, buildNotification("✅ ${server.name}", buildDisconnectPi()))
        FileLogger.log("=== VPN CONNECTED: ${server.name} ===")
        ConnectionLog.ok("═══ VPN подключён: ${server.name} ═══")

        // Проверяем что трафик реально идёт через прокси (сервер не просто принимает
        // подключение, но и возвращает данные). Делаем это асинхронно — не блокируем старт.
        // Ждём дольше (10 с) — VLESS/Reality поверх TLS требует времени на установку туннеля.
        // 3 попытки с паузой 5 с между ними перед тем как признать сервер недоступным.
        scope.launch(Dispatchers.IO) {
            delay(10_000L) // даём tun2socks, xray и TLS-хэндшейк время стабилизироваться
            if (!isRunning) return@launch

            var proxyOk = false
            repeat(3) { attempt ->
                if (proxyOk || !isRunning) return@repeat
                try {
                    val socksProxy = java.net.Proxy(
                        java.net.Proxy.Type.SOCKS,
                        java.net.InetSocketAddress("127.0.0.1", socksPort)
                    )
                    val url = java.net.URL("https://connectivitycheck.gstatic.com/generate_204")
                    val conn = url.openConnection(socksProxy) as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout    = 8000
                    conn.requestMethod  = "HEAD"
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code == 204) {
                        proxyOk = true
                        FileLogger.log("PROXY CHECK: OK (HTTP 204, attempt=${attempt+1})")
                        ConnectionLog.ok("📶 Связь через прокси подтверждена")
                    } else {
                        FileLogger.log("PROXY CHECK: HTTP $code (attempt=${attempt+1})")
                        ConnectionLog.w("⚠️ Прокси вернул HTTP $code (попытка ${attempt+1}/3)")
                    }
                } catch (e: Exception) {
                    FileLogger.log("PROXY CHECK FAILED attempt=${attempt+1}: ${e.message}")
                    ConnectionLog.w("⚠️ Проверка прокси не прошла (попытка ${attempt+1}/3): ${e.message?.take(60)}")
                }
                if (!proxyOk && attempt < 2 && isRunning) delay(5_000L)
            }

            if (!proxyOk && isRunning) {
                FileLogger.log("PROXY CHECK: all 3 attempts failed — switching server")
                ConnectionLog.e("⚠️ Сервер ${server.host} недоступен после 3 попыток — переключаемся...")
                serverFailed = true
                withContext(Dispatchers.Main) { stopVpn() }
            }
        }
    }

    // ─── Ожидание порта ───────────────────────────────────────────
    private suspend fun waitForSocksPort(port: Int, attempts: Int, intervalMs: Long): Boolean {
        repeat(attempts) { i ->
            currentCoroutineContext().ensureActive()
            try {
                withContext(Dispatchers.IO) {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500) }
                }
                ConnectionLog.ok("Порт $port открыт (попытка ${i+1})")
                FileLogger.log("SOCKS5 port $port open after ${i+1} attempts")
                return true
            } catch (_: Exception) {
                ConnectionLog.i("Порт $port закрыт, попытка ${i+1}/$attempts…")
                delay(intervalMs)
                if (!xrayManager.isRunning()) {
                    FileLogger.log("ERROR: xray process died at attempt ${i+1}")
                    ConnectionLog.e("xray упал на попытке ${i+1}")
                    return false
                }
            }
        }
        return false
    }

    // ─── DNS резолвинг ────────────────────────────────────────────
    private suspend fun resolveServerIp(host: String): String {
        // Уже IP — не резолвим
        if (host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) return host
        // IPv6 в скобках
        if (host.startsWith("[")) return host.trim('[', ']')
        return withContext(Dispatchers.IO) {
            try {
                val ip = java.net.InetAddress.getByName(host).hostAddress ?: host
                FileLogger.log("DNS: $host → $ip")
                ConnectionLog.ok("DNS: $host → $ip")
                ip
            } catch (e: Exception) {
                FileLogger.log("DNS failed for $host: ${e.message}, using host as-is")
                ConnectionLog.w("DNS провалился для $host: ${e.message}")
                host
            }
        }
    }

    // ─── TUN-интерфейс ────────────────────────────────────────────
    private fun buildTun(serverIp: String): ParcelFileDescriptor? {
        return try {
            val routes = buildPublicRoutes(serverIp)
            FileLogger.log("TUN routes count: ${routes.size}")
            ConnectionLog.i("Маршрутов: ${routes.size} (публичные IP, LAN исключён)")

            val b = Builder()
                .setSession("VPN Guard")
                .addAddress("10.8.0.1", 30)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                // MTU 1380 — с запасом на VPN-оверхед (IP+TCP+TLS+VLESS ≈ 55–120 байт).
                // При MTU 1500 пакеты после оборачивания в тоннель могут превышать физический MTU
                // канала → фрагментация → TCP RST → ERR_CONNECTION_CLOSED в браузере.
                .setMtu(1380)
                .setBlocking(true)   // blocking — корректно для tun2socks

            for ((addr, prefix) in routes) {
                b.addRoute(addr, prefix)
            }

            // Исключаем собственный UID приложения (включая дочерний процесс xray) из VPN-маршрутизации.
            // Без этого xray's direct outbound (DNS, прямые соединения) попадают обратно в TUN,
            // создавая бесконечную петлю: xray→TUN→tun2socks→xray→TUN→...
            // VPN-сервер доступен напрямую через реальный сетевой интерфейс.
            try {
                b.addDisallowedApplication(packageName)
                FileLogger.log("TUN: app excluded from VPN (no loop): $packageName")
            } catch (e: Exception) {
                FileLogger.log("TUN WARNING: addDisallowedApplication failed: ${e.message} — xray direct outbound may loop!")
                ConnectionLog.w("⚠️ Не удалось исключить приложение из VPN: ${e.message}")
            }

            if (SplitTunneling.isEnabled()) {
                SplitTunneling.getBypassPackages().forEach { pkg ->
                    runCatching { b.addDisallowedApplication(pkg) }
                }
            }

            b.establish().also {
                if (it == null) FileLogger.log("ERROR: establish() returned null")
            }
        } catch (e: Exception) {
            FileLogger.logThrowable("buildTun", e)
            ConnectionLog.e("buildTun ошибка: ${e.message}")
            null
        }
    }

    /**
     * Вычисляет маршруты: весь IPv4-интернет КРОМЕ приватных диапазонов и IP сервера.
     * Это предотвращает маршрутную петлю (LAN-трафик не идёт через TUN).
     *
     * Алгоритм: начинаем с 0.0.0.0/0 и вычитаем исключаемые диапазоны.
     * Результат — точный набор CIDR, покрывающий только публичные IP.
     */
    private fun buildPublicRoutes(serverIp: String): List<Pair<String, Int>> {

        fun ipToLong(ip: String): Long {
            val p = ip.split(".")
            if (p.size != 4) return 0L
            return (p[0].toLong() shl 24) or (p[1].toLong() shl 16) or
                   (p[2].toLong() shl 8)  or  p[3].toLong()
        }

        fun longToIp(n: Long) =
            "${(n shr 24) and 0xFF}.${(n shr 16) and 0xFF}.${(n shr 8) and 0xFF}.${n and 0xFF}"

        fun subtract(
            ranges: List<Pair<Long, Int>>,
            excIp: Long, excPfx: Int
        ): List<Pair<Long, Int>> {
            val excMask = if (excPfx == 0) 0L else (0xFFFFFFFFL shl (32 - excPfx)) and 0xFFFFFFFFL
            val excBase = excIp and excMask
            val result  = mutableListOf<Pair<Long, Int>>()

            for ((net, pfx) in ranges) {
                val mask    = if (pfx == 0) 0L else (0xFFFFFFFFL shl (32 - pfx)) and 0xFFFFFFFFL
                val base    = net and mask
                val cmpMask = if (pfx <= excPfx) mask else excMask
                if ((base and cmpMask) != (excBase and cmpMask)) {
                    result.add(base to pfx); continue   // нет пересечения
                }
                if (excPfx <= pfx) continue              // исключение поглощает весь диапазон

                // Разбиваем диапазон побитово
                var cur = base; var cp = pfx
                while (cp < excPfx) {
                    val xBit  = (excBase ushr (31 - cp)) and 1L
                    result.add((cur or ((1L - xBit) shl (31 - cp))) to (cp + 1))
                    cur = cur or (xBit shl (31 - cp))
                    cp++
                }
                // cur == excBase — исключено, не добавляем
            }
            return result
        }

        // Всё адресное пространство IPv4
        var ranges: List<Pair<Long, Int>> = listOf(0L to 0)

        // Исключаемые приватные и специальные диапазоны
        val excludes = mutableListOf(
            "0.0.0.0"     to 8,   // "This" network
            "10.0.0.0"    to 8,   // RFC 1918
            "100.64.0.0"  to 10,  // CGNAT
            "127.0.0.0"   to 8,   // Loopback
            "169.254.0.0" to 16,  // Link-local
            "172.16.0.0"  to 12,  // RFC 1918
            "192.168.0.0" to 16,  // RFC 1918
            "224.0.0.0"   to 4,   // Multicast
            "240.0.0.0"   to 4    // Reserved
        )

        // Исключаем IP VPN-сервера (предотвращаем петлю)
        if (serverIp.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            excludes.add(serverIp to 32)
            FileLogger.log("TUN: excluding VPN server IP $serverIp")
        }

        for ((ip, pfx) in excludes) {
            runCatching { ranges = subtract(ranges, ipToLong(ip), pfx) }
        }

        FileLogger.log("TUN public routes: ${ranges.size} CIDRs")
        return ranges.map { (n, p) -> longToIp(n) to p }
    }

    // ─── Hotspot ──────────────────────────────────────────────────
    private fun startHotspotProxy() {
        if (!isRunning) { broadcastHotspotStatus(false, "Сначала подключите VPN"); return }
        val ip = HotspotManager.getHotspotIp() ?: run {
            broadcastHotspotStatus(false, "Точка доступа не найдена"); return
        }
        stopHotspotProxy()
        LocalProxyServer(ip, LocalProxyServer.PROXY_PORT, "127.0.0.1", DirectVpnService.socksPort)
            .also { it.start(); hotspotProxy = it }
        hotspotProxyActive    = true
        hotspotProxyAddress   = "$ip:${LocalProxyServer.PROXY_PORT}"
        broadcastHotspotStatus(true, hotspotProxyAddress)
        startForeground(NOTIF_VPN, buildNotification("✅ $connectedServer | 📶 $hotspotProxyAddress"))
    }

    private fun stopHotspotProxy() {
        hotspotProxy?.stop(); hotspotProxy = null
        hotspotProxyActive = false; hotspotProxyAddress = ""
        broadcastHotspotStatus(false, "")
        if (isRunning) startForeground(NOTIF_VPN, buildNotification("✅ $connectedServer", buildDisconnectPi()))
    }

    // ─── Остановка ────────────────────────────────────────────────
    private fun cancelConnect() {
        FileLogger.log("Cancel connect")
        connectJob?.cancel(); connectJob = null
        isConnecting = false
        quickCleanup()
        broadcastStatus(false, "Подключение отменено")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopVpn() {
        FileLogger.log("=== stopVpn ===")
        stopHotspotProxy()
        connectJob?.cancel(); connectJob = null
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Останавливает tun2socks/xray и закрывает TUN fd. Идемпотентна. */
    private fun tearDown() {
        tun2socks.stop()
        val pfd = tunPfd; tunPfd = null   // обнуляем до close() — защита от double-close
        runCatching { pfd?.close() }
        xrayManager.stop()
        isRunning = false; connectedServer = ""
    }

    /** Быстрая очистка без broadcastStatus/stopSelf (при переключении серверов). */
    private fun quickCleanup() = tearDown()

    private fun cleanup() {
        tearDown()
        broadcastStatus(false, "Отключено")
        FileLogger.log("=== cleanup done ===")
    }

    // ─── Broadcasts ───────────────────────────────────────────────
    private fun broadcastStatus(connected: Boolean, message: String) =
        sendBroadcast(Intent(ACTION_VPN_STATUS).apply {
            putExtra(EXTRA_STATUS_CONN, connected); putExtra(EXTRA_STATUS_MSG, message) })

    private fun broadcastError(message: String) =
        sendBroadcast(Intent(ACTION_VPN_STATUS).apply {
            putExtra(EXTRA_STATUS_CONN, false)
            putExtra(EXTRA_STATUS_MSG, "❌ $message")
            putExtra("error", true) })

    private fun broadcastHotspotStatus(active: Boolean, address: String) =
        sendBroadcast(Intent(ACTION_HOTSPOT_STATUS).apply {
            putExtra("active", active); putExtra("address", address) })

    private fun log(msg: String) {
        FileLogger.log(msg)
        ConnectionLog.i(msg)
        broadcastStatus(false, "⏳ $msg")
    }

    override fun onRevoke() { FileLogger.log("onRevoke"); stopVpn(); super.onRevoke() }
    override fun onDestroy() {
        FileLogger.log("onDestroy")
        scope.cancel()
        // cleanup() уже вызван из stopVpn/cancelConnect — но на случай если
        // сервис уничтожается системой без stopVpn, делаем повторную очистку.
        // cleanup() идемпотентна: tunPfd обнуляется до close(), повторный вызов безопасен.
        cleanup()
        super.onDestroy()
    }

    // ─── Уведомление ─────────────────────────────────────────────
    private fun buildDisconnectPi() = PendingIntent.getService(this, 1,
        Intent(this, DirectVpnService::class.java).apply { action = ACTION_DISCONNECT },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildNotification(text: String, actionPi: PendingIntent? = null): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_VPN) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_VPN, "VPN Соединение", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) })
        }
        val openPi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle("VPN Guard")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .apply { if (actionPi != null) addAction(Notification.Action.Builder(null, "Отключить", actionPi).build()) }
            .build()
    }
}
