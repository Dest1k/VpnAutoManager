package com.vpnauto.manager.service

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "LocalProxyServer"

/**
 * SOCKS5-прокси сервер, который слушает на интерфейсе точки доступа
 * и пересылает все запросы в xray SOCKS5 (127.0.0.1:10808).
 *
 * Клиентам (подключённым к хотспоту) достаточно указать:
 *   Proxy: SOCKS5  Host: 192.168.43.1  Port: 1080
 */
class LocalProxyServer(
    private val bindIp: String,
    private val bindPort: Int = PROXY_PORT,
    private val upstreamHost: String = "127.0.0.1",
    private val upstreamPort: Int = Socks5Client.PROXY_PORT
) {
    companion object {
        const val PROXY_PORT = 1080
    }

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName(bindIp), bindPort))
                serverSocket = ss
                Log.d(TAG, "Proxy listening on $bindIp:$bindPort")

                while (isActive) {
                    try {
                        val client = ss.accept()
                        launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (isActive) Log.w(TAG, "Accept error: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        scope.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        Log.d(TAG, "Proxy stopped")
    }

    val isRunning: Boolean
        get() {
            val ss = serverSocket ?: return false
            return ss.isBound && !ss.isClosed
        }

    // ─── Обработка клиентского подключения ───────────────────────
    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        client.soTimeout = 10_000
        try {
            val clientIn  = client.getInputStream()
            val clientOut = client.getOutputStream()

            // Определяем протокол: SOCKS5 (0x05) или HTTP
            val firstByte = clientIn.read()
            when (firstByte) {
                0x05 -> handleSocks5(client, clientIn, clientOut)
                in 'A'.code..'Z'.code,
                in 'a'.code..'z'.code -> handleHttp(client, clientIn, clientOut, firstByte.toChar())
                else -> { Log.w(TAG, "Unknown protocol byte: $firstByte") }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client error: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    // ─── SOCKS5 → пересылаем upstream SOCKS5 ─────────────────────
    private fun handleSocks5(
        client: Socket, clientIn: InputStream, clientOut: OutputStream
    ) {
        // Читаем приветствие клиента
        val nMethods = clientIn.read()
        val methods  = ByteArray(nMethods).also { clientIn.readFully(it) }

        // Отвечаем: NO AUTH
        clientOut.write(byteArrayOf(0x05, 0x00))
        clientOut.flush()

        // Читаем CONNECT запрос
        val ver  = clientIn.read()   // 0x05
        val cmd  = clientIn.read()   // 0x01 = CONNECT
        val rsv  = clientIn.read()   // 0x00
        val atyp = clientIn.read()

        val (host, port) = when (atyp) {
            0x01 -> { // IPv4
                val ip = ByteArray(4).also { clientIn.readFully(it) }
                Pair(InetAddress.getByAddress(ip).hostAddress!!, readPort(clientIn))
            }
            0x03 -> { // Domain
                val len = clientIn.read()
                val domain = ByteArray(len).also { clientIn.readFully(it) }.toString(Charsets.UTF_8)
                Pair(domain, readPort(clientIn))
            }
            0x04 -> { // IPv6
                val ip = ByteArray(16).also { clientIn.readFully(it) }
                Pair("[${InetAddress.getByAddress(ip).hostAddress}]", readPort(clientIn))
            }
            else -> return
        }

        // Подключаемся к upstream SOCKS5
        val upstream = Socket()
        try {
            upstream.connect(InetSocketAddress(upstreamHost, upstreamPort), 5000)
            Socks5Client.connect(upstream, host, port)

            // Отвечаем клиенту: SUCCESS
            clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0))
            clientOut.flush()

            // Двунаправленный обмен данными
            pump(clientIn, clientOut, upstream.getInputStream(), upstream.getOutputStream())
        } catch (e: Exception) {
            Log.w(TAG, "SOCKS5 upstream error $host:$port — ${e.message}")
            clientOut.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0,0,0,0, 0,0))
            clientOut.flush()
        } finally {
            runCatching { upstream.close() }
        }
    }

    // ─── HTTP CONNECT → пересылаем upstream SOCKS5 ───────────────
    private fun handleHttp(
        client: Socket, clientIn: InputStream, clientOut: OutputStream,
        firstChar: Char
    ) {
        // Читаем первую строку запроса (уже прочитан первый символ)
        val sb = StringBuilder().append(firstChar)
        var prev = ' '
        while (true) {
            val c = clientIn.read().toChar()
            sb.append(c)
            if (prev == '\r' && c == '\n') break
            prev = c
        }
        val requestLine = sb.toString().trim()
        // Читаем остальные заголовки до пустой строки
        val headerSb = StringBuilder()
        var line = ""
        while (true) {
            val c = clientIn.read().toChar()
            headerSb.append(c)
            if (c == '\n') {
                if (line.trim().isEmpty()) break
                line = ""
            } else if (c != '\r') {
                line += c
            }
        }

        // CONNECT host:port HTTP/1.1
        if (!requestLine.startsWith("CONNECT ")) {
            clientOut.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray())
            clientOut.flush()
            return
        }
        val target = requestLine.split(" ")[1]
        val host = target.substringBefore(':')
        val port = target.substringAfter(':').toIntOrNull() ?: 443

        val upstream = Socket()
        try {
            upstream.connect(InetSocketAddress(upstreamHost, upstreamPort), 5000)
            Socks5Client.connect(upstream, host, port)
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            clientOut.flush()
            pump(clientIn, clientOut, upstream.getInputStream(), upstream.getOutputStream())
        } catch (e: Exception) {
            Log.w(TAG, "HTTP CONNECT error $host:$port — ${e.message}")
            clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            clientOut.flush()
        } finally {
            runCatching { upstream.close() }
        }
    }

    // ─── Двунаправленная перекачка данных ────────────────────────
    private fun pump(
        clientIn: InputStream, clientOut: OutputStream,
        upIn: InputStream, upOut: OutputStream
    ) {
        val t1 = Thread {
            try {
                val buf = ByteArray(8192)
                while (true) {
                    val n = clientIn.read(buf)
                    if (n < 0) break
                    upOut.write(buf, 0, n); upOut.flush()
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        try {
            val buf = ByteArray(8192)
            while (true) {
                val n = upIn.read(buf)
                if (n < 0) break
                clientOut.write(buf, 0, n); clientOut.flush()
            }
        } catch (_: Exception) {}
        t1.interrupt()
    }

    private fun readPort(inp: InputStream): Int {
        val hi = inp.read()
        val lo = inp.read()
        return (hi shl 8) or lo
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = read(buf, offset, buf.size - offset)
            if (n < 0) throw Exception("EOF")
            offset += n
        }
    }
}
