package com.simpleshield.adblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRulesStoreTest {
    @Test fun `normalizes URL to host`() {
        assertEquals("ads.example.com", DomainRulesStore.normalize("https://Ads.Example.com/path"))
    }

    @Test fun `rejects invalid domain`() {
        assertNull(DomainRulesStore.normalize("not a domain"))
    }

    @Test fun `rule covers its subdomains but not lookalikes`() {
        val rules = setOf("example.com")
        assertTrue(DomainRulesStore.matches("ads.example.com", rules))
        assertFalse(DomainRulesStore.matches("notexample.com", rules))
    }
}
