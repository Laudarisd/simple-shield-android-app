package com.simpleshield.adblocker

/**
 * Small, dependency-free DNS codec used by the VPN service.
 *
 * Input comes from other apps and is untrusted, so all counts, lengths,
 * compression pointers, and array boundaries are validated before use.
 */
object DnsMessageLogic {
    private const val HEADER_SIZE = 12
    private const val MAX_POINTER_JUMPS = 32

    private data class NameResult(val value: String, val nextOffset: Int)

    /** Returns the first queried domain from a standard single-question query. */
    fun extractQueriedDomain(message: ByteArray): String {
        validateQueryHeader(message)
        val name = readName(message, HEADER_SIZE)
        require(name.nextOffset + 4 <= message.size) { "DNS question is truncated" }
        require(name.value.isNotBlank()) { "DNS question has an empty name" }
        return name.value.lowercase()
    }

    /** Builds an NXDOMAIN response for a domain intentionally blocked by Shield. */
    fun buildBlockedResponse(query: ByteArray): ByteArray =
        buildErrorResponse(query, responseCode = 3)

    /** Builds an error response preserving the ID, RD bit, and question. */
    fun buildErrorResponse(query: ByteArray, responseCode: Int): ByteArray {
        require(responseCode in 0..15) { "DNS response code must fit in four bits" }
        validateQueryHeader(query)
        val name = readName(query, HEADER_SIZE)
        val questionEnd = name.nextOffset + 4
        require(questionEnd <= query.size) { "DNS question is truncated" }

        val recursionDesired = query[2].toInt() and 0x01
        val header = byteArrayOf(
            query[0], query[1],
            (0x80 or recursionDesired).toByte(), // response + original RD
            (0x80 or responseCode).toByte(), // recursion available + RCODE
            0, 1, // one question
            0, 0, // no answers
            0, 0, // no authority records
            0, 0, // no additional records
        )
        return header + query.copyOfRange(HEADER_SIZE, questionEnd)
    }

    private fun validateQueryHeader(message: ByteArray) {
        require(message.size >= HEADER_SIZE) { "DNS message is shorter than its header" }
        require(message[2].toInt() and 0x80 == 0) { "Expected a DNS query" }
        require(readUInt16(message, 4) == 1) { "Only single-question DNS messages are supported" }
    }

    /** Reads a possibly compressed name and returns its wire continuation. */
    private fun readName(message: ByteArray, startOffset: Int): NameResult {
        val labels = mutableListOf<String>()
        val visited = mutableSetOf<Int>()
        var cursor = startOffset
        var nextOffset = -1
        var jumps = 0

        while (true) {
            require(cursor in message.indices) { "DNS name runs past the message" }
            val length = message[cursor].toInt() and 0xFF
            when {
                length == 0 -> {
                    if (nextOffset < 0) nextOffset = cursor + 1
                    break
                }
                length and 0xC0 == 0xC0 -> {
                    require(cursor + 1 < message.size) { "Truncated DNS compression pointer" }
                    val pointer = ((length and 0x3F) shl 8) or
                        (message[cursor + 1].toInt() and 0xFF)
                    require(pointer < message.size) { "DNS compression pointer is out of bounds" }
                    require(visited.add(pointer) && ++jumps <= MAX_POINTER_JUMPS) {
                        "DNS compression pointer loop"
                    }
                    if (nextOffset < 0) nextOffset = cursor + 2
                    cursor = pointer
                }
                length and 0xC0 != 0 -> error("Invalid DNS label marker")
                else -> {
                    require(length <= 63 && cursor + 1 + length <= message.size) {
                        "Invalid or truncated DNS label"
                    }
                    val label = message.copyOfRange(cursor + 1, cursor + 1 + length)
                        .toString(Charsets.US_ASCII)
                    require(label.all { it.code in 0x21..0x7E }) { "DNS label contains invalid bytes" }
                    labels += label
                    require(labels.sumOf { it.length } + labels.size - 1 <= 253) { "DNS name is too long" }
                    cursor += 1 + length
                }
            }
        }
        return NameResult(labels.joinToString("."), nextOffset)
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    /** Test helper that builds a minimal A/IN query. */
    fun buildTestQuery(domain: String, transactionId: Int = 1): ByteArray {
        require(domain.isNotBlank())
        val header = byteArrayOf(
            (transactionId shr 8).toByte(), transactionId.toByte(),
            0x01, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val question = buildList<Byte> {
            domain.trimEnd('.').split('.').forEach { label ->
                require(label.length in 1..63)
                add(label.length.toByte())
                addAll(label.toByteArray(Charsets.US_ASCII).toList())
            }
            add(0.toByte())
            addAll(listOf(0.toByte(), 1.toByte(), 0.toByte(), 1.toByte()))
        }.toByteArray()
        return header + question
    }
}
