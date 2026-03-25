package com.vpnauto.manager.service

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

/** Простой SOCKS5-клиент для подключения через xray-proxy на 127.0.0.1:10808 */
object Socks5Client {

    private const val SOCKS5_VERSION: Byte = 5
    private const val CMD_CONNECT: Byte = 1
    private const val ATYP_IPV4: Byte = 1
    private const val ATYP_DOMAIN: Byte = 3
    private const val AUTH_NONE: Byte = 0
    const val PROXY_PORT = 10808

    /**
     * Открывает TCP соединение через SOCKS5 к [dstHost]:[dstPort].
     * [socket] уже должен быть подключён к SOCKS5-серверу.
     */
    fun connect(socket: Socket, dstHost: String, dstPort: Int) {
        socket.soTimeout = 10_000  // 10s timeout для SOCKS5 handshake
        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        // 1. Приветствие: версия + 1 метод (NO AUTH)
        out.write(byteArrayOf(SOCKS5_VERSION, 1, AUTH_NONE))
        out.flush()
        val serverChoice = ByteArray(2)
        inp.readFully(serverChoice)
        if (serverChoice[0] != SOCKS5_VERSION || serverChoice[1] != AUTH_NONE) {
            throw Exception("SOCKS5 auth failed: ${serverChoice.toList()}")
        }

        // 2. CONNECT запрос
        val addr = dstHost.toByteArray(Charsets.US_ASCII)
        val req = ByteArray(7 + addr.size)
        req[0] = SOCKS5_VERSION
        req[1] = CMD_CONNECT
        req[2] = 0  // reserved
        req[3] = ATYP_DOMAIN
        req[4] = addr.size.toByte()
        System.arraycopy(addr, 0, req, 5, addr.size)
        req[5 + addr.size] = (dstPort ushr 8).toByte()
        req[6 + addr.size] = (dstPort and 0xFF).toByte()
        out.write(req)
        out.flush()

        // 3. Читаем ответ (минимум 10 байт для IPv4)
        val rep = ByteArray(4)
        inp.readFully(rep)
        if (rep[1] != 0.toByte()) throw Exception("SOCKS5 connect refused: rep=${rep[1]}")
        // Читаем оставшуюся часть ответа
        when (rep[3]) {
            ATYP_IPV4 -> inp.readFully(ByteArray(6))  // 4 IP + 2 port
            ATYP_DOMAIN -> {
                val len = inp.read()
                inp.readFully(ByteArray(len + 2))
            }
            else -> inp.readFully(ByteArray(18))       // IPv6
        }
    }

    /** То же самое, но принимает IP (Int) вместо строки */
    fun connectByIp(socket: Socket, dstIp: Int, dstPort: Int) {
        val host = "${(dstIp ushr 24) and 0xFF}.${(dstIp ushr 16) and 0xFF}.${(dstIp ushr 8) and 0xFF}.${dstIp and 0xFF}"
        connect(socket, host, dstPort)
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = read(buf, offset, buf.size - offset)
            if (n < 0) throw Exception("SOCKS5: connection closed early")
            offset += n
        }
    }
}
