package com.simpleshield.adblocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BlockedDomainListTest.kt
 *
 * Same checks already run standalone (outside Android) to verify the
 * domain-matching logic, including edge cases that are easy to get subtly
 * wrong (suffix tricks, case sensitivity, trailing dots).
 */
class BlockedDomainListTest {

    @Test
    fun `exact domain match is blocked`() {
        assertTrue(BlockedDomainList.isBlocked("doubleclick.net"))
    }

    @Test
    fun `subdomain of a blocked domain is blocked`() {
        assertTrue(BlockedDomainList.isBlocked("ads.doubleclick.net"))
        assertTrue(BlockedDomainList.isBlocked("sub.ads.doubleclick.net"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(BlockedDomainList.isBlocked("DOUBLECLICK.NET"))
    }

    @Test
    fun `trailing dot (as DNS sometimes includes) is still matched`() {
        assertTrue(BlockedDomainList.isBlocked("googlesyndication.com."))
    }

    @Test
    fun `a domain that merely CONTAINS a blocked name is NOT blocked`() {
        // Guards against a classic false-positive bug: "notdoubleclick.net"
        // is a completely different, legitimate domain.
        assertFalse(BlockedDomainList.isBlocked("notdoubleclick.net"))
    }

    @Test
    fun `a suffix trick does not fool the matcher`() {
        // "doubleclick.net.evil.com" is NOT a subdomain of doubleclick.net —
        // it's a subdomain of evil.com that merely starts with the string
        // "doubleclick.net". Must not match.
        assertFalse(BlockedDomainList.isBlocked("doubleclick.net.evil.com"))
    }

    @Test
    fun `an ordinary unrelated domain is not blocked`() {
        assertFalse(BlockedDomainList.isBlocked("example.com"))
    }

    @Test
    fun `analytics and social domains are not blocked by ads-only mode`() {
        assertFalse(BlockedDomainList.isBlocked("google-analytics.com"))
        assertFalse(BlockedDomainList.isBlocked("connect.facebook.net"))
        assertFalse(BlockedDomainList.isBlocked("flurry.com"))
    }
}
