package com.simpleshield.adblocker

// IpPacketLogic.kt
//
// Pure IPv4/UDP packet construction/parsing — no Android dependencies. This
// exact logic was compiled and run standalone (outside Android) to verify
// correctness, including checksum calculation, against test cases — see
// IpPacketLogicTest.kt in src/test for the same checks, runnable here via
// `./gradlew test`.
//
// IPv4 header (RFC 791), 20 bytes, no options.
// UDP header (RFC 768), 8 bytes.

object IpPacketLogic {

    /** Standard one's-complement checksum used by both IPv4 and UDP (RFC 1071). */
    fun internetChecksum(data: ByteArray): Int {
        val padded = if (data.size % 2 == 1) data + byteArrayOf(0) else data
        var total = 0L
        var i = 0
        while (i < padded.size) {
            val word = ((padded[i].toInt() and 0xFF) shl 8) or (padded[i + 1].toInt() and 0xFF)
            total += word
            i += 2
        }
        while (total shr 16 != 0L) {
            total = (total and 0xFFFF) + (total shr 16)
        }
        return (total.inv() and 0xFFFF).toInt()
    }

    data class ParsedPacket(
        val sourceIp: String,
        val destIp: String,
        val sourcePort: Int,
        val destPort: Int,
        val udpPayload: ByteArray
    )

    private fun ipToBytes(ip: String): ByteArray =
        ip.split(".").map { it.toInt().toByte() }.toByteArray()

    private fun bytesToIp(bytes: ByteArray): String =
        bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    /** Parses an incoming IPv4+UDP packet (as read from the VPN TUN interface). */
    fun parseIpv4UdpPacket(packet: ByteArray): ParsedPacket {
        val versionAndIhl = packet[0].toInt() and 0xFF
        val ipHeaderLength = (versionAndIhl and 0x0F) * 4 // IHL is in 32-bit words
        val protocol = packet[9].toInt() and 0xFF
        require(protocol == 17) { "Not a UDP packet (protocol=$protocol)" }

        val sourceIp = bytesToIp(packet.copyOfRange(12, 16))
        val destIp = bytesToIp(packet.copyOfRange(16, 20))

        val udpStart = ipHeaderLength
        val sourcePort = readUInt16(packet, udpStart)
        val destPort = readUInt16(packet, udpStart + 2)
        val udpLength = readUInt16(packet, udpStart + 4)
        val udpPayload = packet.copyOfRange(udpStart + 8, udpStart + udpLength)

        return ParsedPacket(sourceIp, destIp, sourcePort, destPort, udpPayload)
    }

    /** Builds a full IPv4+UDP packet wrapping `payload`, with correct checksums. */
    fun buildIpv4UdpPacket(
        sourceIp: String, destIp: String,
        sourcePort: Int, destPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size

        // --- UDP header: checksum computed over pseudo-header + header + payload ---
        val sourceIpBytes = ipToBytes(sourceIp)
        val destIpBytes = ipToBytes(destIp)

        fun uint16(value: Int) = byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

        val udpHeaderNoChecksum = uint16(sourcePort) + uint16(destPort) + uint16(udpLength) + uint16(0)
        val pseudoHeader = sourceIpBytes + destIpBytes +
            byteArrayOf(0, 17) + uint16(udpLength)
        var udpChecksum = internetChecksum(pseudoHeader + udpHeaderNoChecksum + payload)
        if (udpChecksum == 0) udpChecksum = 0xFFFF // 0 means "no checksum" in UDP; avoid that ambiguity
        val udpHeader = uint16(sourcePort) + uint16(destPort) + uint16(udpLength) + uint16(udpChecksum)

        // --- IPv4 header ---
        val totalLength = 20 + udpLength
        val ipHeaderNoChecksum = byteArrayOf(0x45, 0) + uint16(totalLength) + uint16(0) +
            uint16(0x4000) + byteArrayOf(64, 17) + uint16(0) + sourceIpBytes + destIpBytes
        val ipChecksum = internetChecksum(ipHeaderNoChecksum)
        val ipHeader = byteArrayOf(0x45, 0) + uint16(totalLength) + uint16(0) +
            uint16(0x4000) + byteArrayOf(64, 17) + uint16(ipChecksum) + sourceIpBytes + destIpBytes

        return ipHeader + udpHeader + payload
    }
}
