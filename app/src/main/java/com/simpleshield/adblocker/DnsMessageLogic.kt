package com.simpleshield.adblocker

// DnsMessageLogic.kt
//
// Pure DNS-message parsing/building logic — no Android dependencies. This
// exact logic was compiled and run standalone (outside Android) to verify
// correctness against test cases before being added to this project — see
// DnsMessageLogicTest.kt in src/test for the same checks, runnable here via
// `./gradlew test`.
//
// DNS message format (RFC 1035 §4.1):
//   Header (12 bytes): ID(2) Flags(2) QDCOUNT(2) ANCOUNT(2) NSCOUNT(2) ARCOUNT(2)
//   Question section: QNAME (length-prefixed labels, ending in a 0x00 byte),
//                      QTYPE(2), QCLASS(2)

object DnsMessageLogic {

    /** Reads the domain name being queried out of a raw DNS query message. */
    fun extractQueriedDomain(dnsMessage: ByteArray): String {
        require(dnsMessage.size >= 12) { "DNS message too short to contain a header" }

        var position = 12 // skip the fixed 12-byte header
        val labels = mutableListOf<String>()

        while (true) {
            require(position < dnsMessage.size) { "Malformed DNS message: ran off the end while reading QNAME" }
            val labelLength = dnsMessage[position].toInt() and 0xFF
            position += 1
            if (labelLength == 0) break // 0x00 terminates the QNAME
            require(position + labelLength <= dnsMessage.size) { "Malformed DNS message: label runs past end of message" }
            val labelBytes = dnsMessage.copyOfRange(position, position + labelLength)
            labels.add(String(labelBytes, Charsets.US_ASCII))
            position += labelLength
        }

        return labels.joinToString(".")
    }

    /**
     * Builds an NXDOMAIN ("this domain does not exist") response for a
     * blocked domain, reusing the original query's transaction ID and
     * question section so the requesting app recognizes it as the answer
     * to its own query.
     */
    fun buildBlockedResponse(originalQuery: ByteArray): ByteArray {
        require(originalQuery.size >= 12) { "Query too short" }

        val transactionId = originalQuery.copyOfRange(0, 2)
        // Flags: response=1, opcode=standard query, RD+RA set, RCODE=3 (NXDOMAIN)
        val flags = byteArrayOf(0x81.toByte(), 0x83.toByte())
        val qdCount = byteArrayOf(0x00, 0x01)
        val anCount = byteArrayOf(0x00, 0x00) // zero answers
        val nsCount = byteArrayOf(0x00, 0x00)
        val arCount = byteArrayOf(0x00, 0x00)

        // Copy the question section verbatim from the original query.
        var position = 12
        while (originalQuery[position].toInt() != 0) {
            position += 1 + (originalQuery[position].toInt() and 0xFF)
        }
        val questionSectionEnd = position + 1 + 4 // +1 terminator, +4 QTYPE/QCLASS
        val questionSection = originalQuery.copyOfRange(12, questionSectionEnd)

        return transactionId + flags + qdCount + anCount + nsCount + arCount + questionSection
    }

    /** Test helper only: builds a minimal, valid DNS query for a domain. */
    fun buildTestQuery(domain: String, transactionId: Int = 1): ByteArray {
        val header = byteArrayOf(
            (transactionId shr 8).toByte(), (transactionId and 0xFF).toByte(),
            0x01, 0x00, // standard query, recursion desired
            0x00, 0x01, // QDCOUNT = 1
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        var question = ByteArray(0)
        for (label in domain.split(".")) {
            question += byteArrayOf(label.length.toByte()) + label.toByteArray(Charsets.US_ASCII)
        }
        question += byteArrayOf(0x00)             // terminator
        question += byteArrayOf(0x00, 0x01, 0x00, 0x01) // QTYPE=A, QCLASS=IN
        return header + question
    }
}

