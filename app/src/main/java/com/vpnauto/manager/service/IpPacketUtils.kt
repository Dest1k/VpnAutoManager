package com.vpnauto.manager.service

import java.nio.ByteBuffer
import java.nio.ByteOrder

// ─── IP / TCP константы ──────────────────────────────────────────
object Proto {
    const val TCP: Byte = 6
    const val UDP: Byte = 17
}

object TcpFlags {
    const val FIN: Int = 0x01
    const val SYN: Int = 0x02
    const val RST: Int = 0x04
    const val PSH: Int = 0x08
    const val ACK: Int = 0x10
    const val URG: Int = 0x20
}

// ─── IPv4 заголовок ──────────────────────────────────────────────
data class IpHeader(
    val version: Int,
    val ihl: Int,          // в 32-битных словах
    val totalLen: Int,
    val protocol: Byte,
    val srcIp: Int,
    val dstIp: Int
) {
    val headerBytes: Int get() = ihl * 4
    companion object {
        fun parse(buf: ByteBuffer): IpHeader? {
            if (buf.remaining() < 20) return null
            buf.order(ByteOrder.BIG_ENDIAN)
            val versionIhl = buf.get(buf.position()).toInt() and 0xFF
            val version = versionIhl ushr 4
            val ihl = versionIhl and 0x0F
            if (version != 4 || ihl < 5) return null
            val totalLen = buf.getShort(buf.position() + 2).toInt() and 0xFFFF
            val protocol = buf.get(buf.position() + 9)
            val srcIp = buf.getInt(buf.position() + 12)
            val dstIp = buf.getInt(buf.position() + 16)
            return IpHeader(version, ihl, totalLen, protocol, srcIp, dstIp)
        }
    }
}

// ─── TCP заголовок ───────────────────────────────────────────────
data class TcpHeader(
    val srcPort: Int,
    val dstPort: Int,
    val seq: Int,
    val ack: Int,
    val dataOffset: Int,    // в 32-битных словах
    val flags: Int,
    val window: Int
) {
    val headerBytes: Int get() = dataOffset * 4
    fun hasFlag(flag: Int) = (flags and flag) != 0
    companion object {
        fun parse(buf: ByteBuffer, offset: Int): TcpHeader? {
            if (buf.capacity() - offset < 20) return null
            buf.order(ByteOrder.BIG_ENDIAN)
            val srcPort = buf.getShort(offset).toInt() and 0xFFFF
            val dstPort = buf.getShort(offset + 2).toInt() and 0xFFFF
            val seq = buf.getInt(offset + 4)
            val ack = buf.getInt(offset + 8)
            val dataOffset = (buf.get(offset + 12).toInt() and 0xFF) ushr 4
            val flags = buf.get(offset + 13).toInt() and 0xFF
            val window = buf.getShort(offset + 14).toInt() and 0xFFFF
            return TcpHeader(srcPort, dstPort, seq, ack, dataOffset, flags, window)
        }
    }
}

// ─── Построение пакетов ──────────────────────────────────────────
object PacketBuilder {

    fun buildTcpPacket(
        srcIp: Int, srcPort: Int,
        dstIp: Int, dstPort: Int,
        seq: Int, ack: Int,
        flags: Int,
        window: Int = 65535,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val tcpLen = 20 + payload.size
        val totalLen = 20 + tcpLen
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

        // IP header
        buf.put(0x45.toByte())          // Version=4, IHL=5
        buf.put(0)                       // DSCP/ECN
        buf.putShort(totalLen.toShort())
        buf.putShort(0)                  // ID
        buf.putShort(0x4000.toShort())   // Don't fragment
        buf.put(64)                      // TTL
        buf.put(Proto.TCP)
        buf.putShort(0)                  // Checksum placeholder
        buf.putInt(srcIp)
        buf.putInt(dstIp)

        // TCP header
        val tcpStart = 20
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq)
        buf.putInt(ack)
        buf.put(0x50.toByte())           // Data offset = 5
        buf.put(flags.toByte())
        buf.putShort(window.toShort())
        buf.putShort(0)                  // Checksum placeholder
        buf.putShort(0)                  // Urgent pointer

        if (payload.isNotEmpty()) buf.put(payload)

        val bytes = buf.array()

        // IP checksum
        val ipChecksum = checksum(bytes, 0, 20)
        bytes[10] = (ipChecksum ushr 8).toByte()
        bytes[11] = (ipChecksum and 0xFF).toByte()

        // TCP checksum (with pseudo-header)
        val tcpChecksum = tcpChecksum(bytes, srcIp, dstIp, tcpLen)
        bytes[tcpStart + 16] = (tcpChecksum ushr 8).toByte()
        bytes[tcpStart + 17] = (tcpChecksum and 0xFF).toByte()

        return bytes
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if ((offset + length) % 2 != 0) sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun tcpChecksum(packet: ByteArray, srcIp: Int, dstIp: Int, tcpLen: Int): Int {
        // Pseudo-header: srcIp(4) + dstIp(4) + 0(1) + proto(1) + tcpLen(2)
        val pseudo = ByteBuffer.allocate(12 + tcpLen).order(ByteOrder.BIG_ENDIAN)
        pseudo.putInt(srcIp)
        pseudo.putInt(dstIp)
        pseudo.put(0)
        pseudo.put(Proto.TCP)
        pseudo.putShort(tcpLen.toShort())
        pseudo.put(packet, 20, tcpLen)
        // Zero checksum field in copy
        pseudo.put(28, 0)
        pseudo.put(29, 0)
        return checksum(pseudo.array(), 0, pseudo.capacity())
    }

    fun intToIpString(ip: Int): String {
        return "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
    }
}
