package com.simpleshield.adblocker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * IpPacketLogicTest.kt
 *
 * Same checks already run standalone (outside Android) to verify
 * IpPacketLogic, including a byte-for-byte cross-check against an
 * independent Python implementation of the same checksum algorithm.
 * Re-run anytime with: ./gradlew test
 */
class IpPacketLogicTest {

    @Test
    fun `packet round-trips through build then parse unchanged`() {
        val payload = "hello-dns-payload".toByteArray()
        val packet = IpPacketLogic.buildIpv4UdpPacket("10.111.222.5", "1.1.1.1", 54321, 53, payload)
        val parsed = IpPacketLogic.parseIpv4UdpPacket(packet)

        assertEquals("10.111.222.5", parsed.sourceIp)
        assertEquals("1.1.1.1", parsed.destIp)
        assertEquals(54321, parsed.sourcePort)
        assertEquals(53, parsed.destPort)
        assertArrayEquals(payload, parsed.udpPayload)
    }

    @Test
    fun `odd-length payload round-trips correctly (exercises checksum padding path)`() {
        val payload = "x".toByteArray() // 1 byte — forces the checksum's odd-length padding branch
        val packet = IpPacketLogic.buildIpv4UdpPacket("192.168.1.50", "8.8.8.8", 33333, 53, payload)
        val parsed = IpPacketLogic.parseIpv4UdpPacket(packet)
        assertArrayEquals(payload, parsed.udpPayload)
    }

    @Test
    fun `matches a known-correct byte sequence produced by an independent Python implementation`() {
        // This exact hex string was produced by a separate Python
        // implementation of the same IPv4/UDP-with-checksum construction,
        // for the same inputs — confirms both follow the wire format
        // identically, not just that each is internally self-consistent.
        val expectedHex = "4500002d000040004011504a0a6fde0501010101d43100350019681468656c6c6f2d646e732d7061796c6f6164"
        val packet = IpPacketLogic.buildIpv4UdpPacket(
            "10.111.222.5", "1.1.1.1", 54321, 53, "hello-dns-payload".toByteArray(),
        )
        val actualHex = packet.joinToString("") { "%02x".format(it) }
        assertEquals(expectedHex, actualHex)
    }

    @Test
    fun `IPv6 UDP packet round-trips`() {
        val payload = "ipv6-dns".toByteArray()
        val packet = IpPacketLogic.buildUdpPacket("fd00:1::2", "fd00:1::1", 40000, 53, payload)
        val parsed = IpPacketLogic.parseUdpPacket(packet)
        assertEquals(40000, parsed.sourcePort)
        assertEquals(53, parsed.destPort)
        assertArrayEquals(payload, parsed.udpPayload)
    }

    @Test
    fun `truncated IPv4 packet is rejected`() {
        val packet = IpPacketLogic.buildIpv4UdpPacket("10.0.0.1", "10.0.0.2", 1234, 53, byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            IpPacketLogic.parseIpv4UdpPacket(packet.copyOf(packet.size - 1))
        }
    }

    @Test
    fun `fragmented IPv4 packet is rejected`() {
        val packet = IpPacketLogic.buildIpv4UdpPacket("10.0.0.1", "10.0.0.2", 1234, 53, byteArrayOf(1))
        packet[6] = 0x20 // more-fragments flag
        assertThrows(IllegalArgumentException::class.java) {
            IpPacketLogic.parseIpv4UdpPacket(packet)
        }
    }
}
