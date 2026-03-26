package com.vpnauto.manager.service

import kotlinx.coroutines.*
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

private const val MTU        = 1500
private const val SOCKS_HOST = "127.0.0.1"
private const val SOCKS_PORT = 10808

/**
 * Фоллбэк-обработчик TUN-пакетов, если tun2socks недоступен.
 *
 * Реализует минимальный TCP/UDP стек:
 *  - TCP: SYN → SOCKS5 connect → SYN-ACK → relay data → FIN/RST
 *  - UDP: single-packet relay (DNS и т.д.)
 *
 * Использует структуры из IpPacketUtils для парсинга/сборки пакетов.
 */
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

    // ─── Чтение пакетов из TUN ────────────────────────────────────
    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        val buf = ByteArray(MTU)
        while (isActive) {
            val n = try {
                tunIn.read(buf)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) continue
            val pkt = buf.copyOf(n)
            val bb = ByteBuffer.wrap(pkt)
            val ip = IpHeader.parse(bb) ?: continue
            if (ip.version != 4) continue

            when (ip.protocol) {
                Proto.TCP -> {
                    val tcp = TcpHeader.parse(bb, ip.headerBytes) ?: continue
                    val payload = if (n > ip.headerBytes + tcp.headerBytes)
                        pkt.copyOfRange(ip.headerBytes + tcp.headerBytes, n) else ByteArray(0)
                    scope.launch { handleTcp(ip, tcp, payload) }
                }
                Proto.UDP -> scope.launch { handleUdp(pkt, n, ip) }
            }
        }
    }

    // ─── TCP ──────────────────────────────────────────────────────
    private suspend fun handleTcp(ip: IpHeader, tcp: TcpHeader, payload: ByteArray) {
        val key = "${ip.srcIp}:${tcp.srcPort}>${ip.dstIp}:${tcp.dstPort}"

        when {
            tcp.hasFlag(TcpFlags.RST) ->
                tcpConns.remove(key)?.runCatching { socket.close() }

            tcp.hasFlag(TcpFlags.SYN) && !tcp.hasFlag(TcpFlags.ACK) ->
                openTcpSession(key, ip.srcIp, tcp.srcPort, ip.dstIp, tcp.dstPort, tcp.seq)

            tcp.hasFlag(TcpFlags.FIN) -> {
                val s = tcpConns.remove(key) ?: return
                runCatching { s.socket.close() }
                writeTcp(ip.dstIp, tcp.dstPort, ip.srcIp, tcp.srcPort,
                    s.serverSeq, tcp.seq + 1, TcpFlags.FIN or TcpFlags.ACK)
            }

            tcp.hasFlag(TcpFlags.ACK) && payload.isNotEmpty() -> {
                val s = tcpConns[key] ?: return
                runCatching {
                    synchronized(s.outLock) {
                        s.socket.getOutputStream().write(payload)
                        s.socket.getOutputStream().flush()
                    }
                    s.clientAck = tcp.seq + payload.size
                    writeTcp(ip.dstIp, tcp.dstPort, ip.srcIp, tcp.srcPort,
                        s.serverSeq, s.clientAck, TcpFlags.ACK)
                }
            }
        }
    }

    private suspend fun openTcpSession(
        key: String, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, clientIsn: Int
    ) {
        val dstAddr = PacketBuilder.intToIpString(dstIp)
        ConnectionLog.i("TCP → $dstAddr:$dstPort")
        val sock = Socket()
        try {
            vpnService.protect(sock)
            sock.connect(InetSocketAddress(SOCKS_HOST, SOCKS_PORT), 5000)
            sock.soTimeout = 0
            sock.tcpNoDelay = true
            Socks5Client.connect(sock, dstAddr, dstPort)
            ConnectionLog.ok("TCP сессия: $dstAddr:$dstPort")

            val srvIsn = (System.nanoTime() and 0x7FFF_FFFFL).toInt()
            writeTcp(dstIp, dstPort, srcIp, srcPort, srvIsn, clientIsn + 1,
                TcpFlags.SYN or TcpFlags.ACK)

            val session = TcpSession(sock, srvIsn + 1, clientIsn + 1)
            tcpConns[key] = session

            // upstream → TUN pump
            val buf = ByteArray(8192)
            try {
                while (currentCoroutineContext().isActive) {
                    val nr = sock.getInputStream().read(buf)
                    if (nr < 0) break
                    val data = buf.copyOf(nr)
                    writeTcp(dstIp, dstPort, srcIp, srcPort,
                        session.serverSeq, session.clientAck,
                        TcpFlags.ACK or TcpFlags.PSH, data)
                    session.serverSeq += nr
                }
            } finally {
                tcpConns.remove(key)
                runCatching { sock.close() }
                writeTcp(dstIp, dstPort, srcIp, srcPort,
                    session.serverSeq, session.clientAck, TcpFlags.FIN or TcpFlags.ACK)
            }
        } catch (_: Exception) {
            runCatching { sock.close() }
            tcpConns.remove(key)
            writeTcp(dstIp, dstPort, srcIp, srcPort, 0, clientIsn + 1, TcpFlags.RST)
        }
    }

    // ─── UDP ──────────────────────────────────────────────────────
    private suspend fun handleUdp(pkt: ByteArray, n: Int, ip: IpHeader) {
        val ihl = ip.headerBytes
        if (n < ihl + 8) return
        val bb      = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        val srcPort = bb.getShort(ihl).toInt() and 0xFFFF
        val dstPort = bb.getShort(ihl + 2).toInt() and 0xFFFF
        val udpLen  = bb.getShort(ihl + 4).toInt() and 0xFFFF
        val payload = pkt.copyOfRange(ihl + 8, (ihl + udpLen).coerceAtMost(n))
        relayUdp(payload, ip.srcIp, srcPort, ip.dstIp, dstPort)
    }

    private suspend fun relayUdp(
        payload: ByteArray, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int
    ) = withContext(Dispatchers.IO) {
        val sock = DatagramSocket()
        try {
            vpnService.protect(sock)
            sock.soTimeout = 3000
            sock.send(DatagramPacket(payload, payload.size,
                InetAddress.getByName(PacketBuilder.intToIpString(dstIp)), dstPort))
            val resp    = ByteArray(4096)
            val respPkt = DatagramPacket(resp, resp.size)
            sock.receive(respPkt)
            writeUdp(dstIp, dstPort, srcIp, srcPort, resp.copyOf(respPkt.length))
        } catch (_: Exception) {
        } finally {
            runCatching { sock.close() }
        }
    }

    // ─── Запись пакетов в TUN ─────────────────────────────────────
    private fun writeTcp(
        srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
        seq: Int, ack: Int, flags: Int, payload: ByteArray = ByteArray(0)
    ) {
        val p = PacketBuilder.buildTcpPacket(srcIp, srcPort, dstIp, dstPort, seq, ack, flags, payload = payload)
        synchronized(writeLock) { runCatching { tunOut.write(p) } }
    }

    private fun writeUdp(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, payload: ByteArray) {
        val udpLen = 8 + payload.size
        val total  = 20 + udpLen
        val b = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN).also { bb ->
            bb.put(0x45.toByte()); bb.put(0); bb.putShort(total.toShort())
            bb.putShort(0); bb.putShort(0x4000.toShort()); bb.put(64); bb.put(Proto.UDP)
            bb.putShort(0); bb.putInt(srcIp); bb.putInt(dstIp)
            bb.putShort(srcPort.toShort()); bb.putShort(dstPort.toShort())
            bb.putShort(udpLen.toShort()); bb.putShort(0)
            if (payload.isNotEmpty()) bb.put(payload)
        }.array()
        // IP checksum
        val csum = ipChecksum(b, 0, 20)
        b[10] = (csum ushr 8).toByte(); b[11] = (csum and 0xFF).toByte()
        synchronized(writeLock) { runCatching { tunOut.write(b) } }
    }

    private fun ipChecksum(data: ByteArray, off: Int, len: Int): Int {
        var s = 0; var i = off
        while (i < off + len - 1) {
            s += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF); i += 2
        }
        if ((off + len) % 2 != 0) s += (data[off + len - 1].toInt() and 0xFF) shl 8
        while (s ushr 16 != 0) s = (s and 0xFFFF) + (s ushr 16)
        return s.inv() and 0xFFFF
    }
}

private class TcpSession(val socket: Socket, @Volatile var serverSeq: Int, @Volatile var clientAck: Int) {
    val outLock = Any()
}
