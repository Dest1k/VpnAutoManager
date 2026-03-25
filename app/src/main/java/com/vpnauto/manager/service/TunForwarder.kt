package com.vpnauto.manager.service

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

private const val TAG        = "TunForwarder"
private const val MTU        = 1500
private const val SOCKS_HOST = "127.0.0.1"
private const val SOCKS_PORT = 10808

class TunForwarder(
    private val tunFd: FileDescriptor,
    private val vpnService: DirectVpnService
) {
    private val tunIn    = FileInputStream(tunFd)
    private val tunOut   = FileOutputStream(tunFd)
    private val scope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tcpConns = ConcurrentHashMap<String, TcpSession>()
    private val writeLock = Any()

    fun start() { scope.launch { readLoop() }; ConnectionLog.ok("TunForwarder запущен") }

    fun stop() {
        scope.cancel()
        tcpConns.values.forEach { runCatching { it.socket.close() } }
        tcpConns.clear()
        ConnectionLog.i("TunForwarder остановлен")
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        val buf = ByteArray(MTU)
        while (isActive) {
            val n = try {
                tunIn.read(buf)   // blocking — ждёт пакета, не тратит CPU
            } catch (_: Exception) {
                break             // fd закрыт при stop()
            }
            if (n <= 0) continue
            val pkt = buf.copyOf(n)           // копия ПЕРЕД async — нет race condition
            val version = (pkt[0].toInt() and 0xFF) ushr 4
            if (version != 4) continue        // только IPv4
            val ihl   = (pkt[0].toInt() and 0x0F) * 4
            val proto = pkt[9].toInt() and 0xFF
            val bb    = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
            val srcIp = bb.getInt(12)
            val dstIp = bb.getInt(16)
            when (proto) {
                6  -> scope.launch { handleTcp(pkt, n, ihl, srcIp, dstIp) }
                17 -> scope.launch { handleUdp(pkt, n, ihl, srcIp, dstIp) }
            }
        }
    }

    // ─── TCP ─────────────────────────────────────────────────────
    private suspend fun handleTcp(pkt: ByteArray, n: Int, ihl: Int, srcIp: Int, dstIp: Int) {
        if (n < ihl + 20) return
        val bb      = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        val srcPort = bb.getShort(ihl).toInt() and 0xFFFF
        val dstPort = bb.getShort(ihl + 2).toInt() and 0xFFFF
        val seq     = bb.getInt(ihl + 4)
        val dataOff = ((pkt[ihl + 12].toInt() and 0xFF) ushr 4) * 4
        val flags   = pkt[ihl + 13].toInt() and 0xFF
        val payload = if (n > ihl + dataOff) pkt.copyOfRange(ihl + dataOff, n) else ByteArray(0)
        val key     = "$srcIp:$srcPort>$dstIp:$dstPort"

        when {
            flags and 0x04 != 0 ->                           // RST
                tcpConns.remove(key)?.runCatching { socket.close() }

            flags and 0x02 != 0 && flags and 0x10 == 0 ->    // SYN
                openTcpSession(key, srcIp, srcPort, dstIp, dstPort, seq)

            flags and 0x01 != 0 -> {                          // FIN
                val s = tcpConns.remove(key) ?: return
                runCatching { s.socket.close() }
                sendTcp(dstIp, dstPort, srcIp, srcPort, s.serverSeq, seq + 1, 0x11)
            }

            flags and 0x10 != 0 && payload.isNotEmpty() -> { // ACK+data
                val s = tcpConns[key] ?: return
                runCatching {
                    synchronized(s.outLock) {
                        s.socket.getOutputStream().write(payload)
                        s.socket.getOutputStream().flush()
                    }
                    s.clientAck = seq + payload.size
                    sendTcp(dstIp, dstPort, srcIp, srcPort, s.serverSeq, s.clientAck, 0x10)
                }
            }
        }
    }

    private suspend fun openTcpSession(
        key: String, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, clientIsn: Int
    ) {
        val dstAddr = intToIp(dstIp)
        ConnectionLog.i("TCP → $dstAddr:$dstPort")
        val sock = Socket()
        try {
            vpnService.protect(sock)
            sock.connect(InetSocketAddress(SOCKS_HOST, SOCKS_PORT), 5000)
            sock.soTimeout = 0
            Socks5Client.connect(sock, dstAddr, dstPort)
            ConnectionLog.ok("TCP сессия открыта: $dstAddr:$dstPort")
            val srvIsn = (System.nanoTime() and 0x7FFF_FFFFL).toInt()
            sendTcp(dstIp, dstPort, srcIp, srcPort, srvIsn, clientIsn + 1, 0x12) // SYN-ACK
            val session = TcpSession(sock, srvIsn + 1, clientIsn + 1)
            tcpConns[key] = session
            // pump: upstream → TUN
            val buf = ByteArray(8192)
            try {
                while (currentCoroutineContext().isActive) {
                    val nr = sock.getInputStream().read(buf)
                    if (nr < 0) break
                    val data = buf.copyOf(nr)
                    sendTcp(dstIp, dstPort, srcIp, srcPort, session.serverSeq, session.clientAck, 0x18, data)
                    session.serverSeq += nr
                }
            } finally {
                tcpConns.remove(key)
                runCatching { sock.close() }
                sendTcp(dstIp, dstPort, srcIp, srcPort, session.serverSeq, session.clientAck, 0x11)
            }
        } catch (e: Exception) {
            runCatching { sock.close() }
            tcpConns.remove(key)
            sendTcp(dstIp, dstPort, srcIp, srcPort, 0, clientIsn + 1, 0x04) // RST
        }
    }

    // ─── UDP ─────────────────────────────────────────────────────
    private suspend fun handleUdp(pkt: ByteArray, n: Int, ihl: Int, srcIp: Int, dstIp: Int) {
        if (n < ihl + 8) return
        val bb      = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        val srcPort = bb.getShort(ihl).toInt() and 0xFFFF
        val dstPort = bb.getShort(ihl + 2).toInt() and 0xFFFF
        val udpLen  = bb.getShort(ihl + 4).toInt() and 0xFFFF
        val payload = pkt.copyOfRange(ihl + 8, (ihl + udpLen).coerceAtMost(n))
        relayUdp(payload, srcIp, srcPort, dstIp, dstPort)
    }

    private suspend fun relayUdp(
        payload: ByteArray, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int
    ) = withContext(Dispatchers.IO) {
        val sock = DatagramSocket()
        try {
            vpnService.protect(sock)
            sock.soTimeout = 3000
            sock.send(DatagramPacket(payload, payload.size,
                InetAddress.getByName(intToIp(dstIp)), dstPort))
            val resp    = ByteArray(4096)
            val respPkt = DatagramPacket(resp, resp.size)
            sock.receive(respPkt)
            // Правильный порядок: отвечаем от dstIp:dstPort → srcIp:srcPort
            sendUdp(dstIp, dstPort, srcIp, srcPort, resp.copyOf(respPkt.length))
        } catch (_: Exception) {
        } finally {
            runCatching { sock.close() }
        }
    }

    // ─── Построение пакетов ──────────────────────────────────────
    private fun sendTcp(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
                        seq: Int, ack: Int, flags: Int, payload: ByteArray = ByteArray(0)) {
        val p = buildTcpPkt(srcIp, srcPort, dstIp, dstPort, seq, ack, flags, payload)
        synchronized(writeLock) { runCatching { tunOut.write(p) } }
    }

    private fun sendUdp(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, payload: ByteArray) {
        val p = buildUdpPkt(srcIp, srcPort, dstIp, dstPort, payload)
        synchronized(writeLock) { runCatching { tunOut.write(p) } }
    }

    private fun buildTcpPkt(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
                             seq: Int, ack: Int, flags: Int, payload: ByteArray): ByteArray {
        val tcpLen = 20 + payload.size; val total = 20 + tcpLen
        val b = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN).also { bb ->
            bb.put(0x45.toByte()); bb.put(0); bb.putShort(total.toShort())
            bb.putShort(0); bb.putShort(0x4000.toShort()); bb.put(64); bb.put(6)
            bb.putShort(0); bb.putInt(srcIp); bb.putInt(dstIp)
            bb.putShort(srcPort.toShort()); bb.putShort(dstPort.toShort())
            bb.putInt(seq); bb.putInt(ack)
            bb.put(0x50.toByte()); bb.put(flags.toByte())
            bb.putShort(65535.toShort()); bb.putShort(0); bb.putShort(0)
            if (payload.isNotEmpty()) bb.put(payload)
        }.array()
        csum16(b, 0, 20).let { b[10] = (it ushr 8).toByte(); b[11] = (it and 0xFF).toByte() }
        tcpCsum(b, srcIp, dstIp, tcpLen).let { b[36] = (it ushr 8).toByte(); b[37] = (it and 0xFF).toByte() }
        return b
    }

    private fun buildUdpPkt(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size; val total = 20 + udpLen
        val b = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN).also { bb ->
            bb.put(0x45.toByte()); bb.put(0); bb.putShort(total.toShort())
            bb.putShort(0); bb.putShort(0x4000.toShort()); bb.put(64); bb.put(17)
            bb.putShort(0); bb.putInt(srcIp); bb.putInt(dstIp)
            bb.putShort(srcPort.toShort()); bb.putShort(dstPort.toShort())
            bb.putShort(udpLen.toShort()); bb.putShort(0)
            if (payload.isNotEmpty()) bb.put(payload)
        }.array()
        csum16(b, 0, 20).let { b[10] = (it ushr 8).toByte(); b[11] = (it and 0xFF).toByte() }
        return b
    }

    private fun csum16(data: ByteArray, off: Int, len: Int): Int {
        var s = 0; var i = off
        while (i < off + len - 1) { s += ((data[i].toInt() and 0xFF) shl 8) or (data[i+1].toInt() and 0xFF); i += 2 }
        if ((off + len) % 2 != 0) s += (data[off + len - 1].toInt() and 0xFF) shl 8
        while (s ushr 16 != 0) s = (s and 0xFFFF) + (s ushr 16)
        return s.inv() and 0xFFFF
    }

    private fun tcpCsum(pkt: ByteArray, srcIp: Int, dstIp: Int, tcpLen: Int): Int {
        val ps = ByteBuffer.allocate(12 + tcpLen).order(ByteOrder.BIG_ENDIAN)
        ps.putInt(srcIp); ps.putInt(dstIp); ps.put(0); ps.put(6); ps.putShort(tcpLen.toShort())
        ps.put(pkt, 20, tcpLen); ps.put(28, 0); ps.put(29, 0)
        return csum16(ps.array(), 0, ps.capacity())
    }

    private fun intToIp(ip: Int) =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
}

private class TcpSession(val socket: Socket, @Volatile var serverSeq: Int, @Volatile var clientAck: Int) {
    val outLock = Any()
}
