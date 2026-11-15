package com.simpleshield.adblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * DnsMessageLogicTest.kt
 *
 * These are the exact same checks that were already run standalone (outside
 * Android, via `kotlinc`) to verify DnsMessageLogic before it was added to
 * this project. Re-run anytime with: ./gradlew test
 */
class DnsMessageLogicTest {

    @Test
    fun `extractQueriedDomain parses a simple domain correctly`() {
        val query = DnsMessageLogic.buildTestQuery("ads.example.com")
        assertEquals("ads.example.com", DnsMessageLogic.extractQueriedDomain(query))
    }

    @Test
    fun `extractQueriedDomain parses a domain with many subdomain labels`() {
        val domain = "a.b.c.d.e.googlesyndication.com"
        val query = DnsMessageLogic.buildTestQuery(domain)
        assertEquals(domain, DnsMessageLogic.extractQueriedDomain(query))
    }

    @Test
    fun `extractQueriedDomain parses a short domain`() {
        val query = DnsMessageLogic.buildTestQuery("short.io")
        assertEquals("short.io", DnsMessageLogic.extractQueriedDomain(query))
    }

    @Test
    fun `buildBlockedResponse preserves the original transaction ID`() {
        val query = DnsMessageLogic.buildTestQuery("doubleclick.net", transactionId = 0xABCD)
        val response = DnsMessageLogic.buildBlockedResponse(query)
        val responseId = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
        assertEquals(0xABCD, responseId)
    }

    @Test
    fun `buildBlockedResponse sets RCODE to 3 (NXDOMAIN)`() {
        val query = DnsMessageLogic.buildTestQuery("doubleclick.net")
        val response = DnsMessageLogic.buildBlockedResponse(query)
        val flags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        val rcode = flags and 0x000F
        assertEquals(3, rcode)
    }

    @Test
    fun `buildBlockedResponse sets zero answer records`() {
        val query = DnsMessageLogic.buildTestQuery("doubleclick.net")
        val response = DnsMessageLogic.buildBlockedResponse(query)
        val answerCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        assertEquals(0, answerCount)
    }

    @Test
    fun `SERVFAIL response uses response code 2`() {
        val response = DnsMessageLogic.buildErrorResponse(
            DnsMessageLogic.buildTestQuery("example.com"),
            responseCode = 2,
        )
        assertEquals(2, response[3].toInt() and 0x0F)
    }

    @Test
    fun `truncated question is rejected`() {
        val truncated = DnsMessageLogic.buildTestQuery("example.com").copyOf(15)
        assertThrows(IllegalArgumentException::class.java) {
            DnsMessageLogic.extractQueriedDomain(truncated)
        }
    }

    @Test
    fun `compression pointer loop is rejected`() {
        val query = byteArrayOf(
            0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            0xC0.toByte(), 12, 0, 1, 0, 1,
        )
        assertThrows(IllegalArgumentException::class.java) {
            DnsMessageLogic.extractQueriedDomain(query)
        }
    }
}
