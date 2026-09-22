package com.hop.dht

import java.security.SecureRandom

/**
 * Correlates an outbound DHT RPC (e.g. a PING) with its matching inbound
 * response (e.g. a PONG) across a connectionless UDP transport.
 *
 * Wraps a raw [ByteArray] specifically so it can be used as a map key with
 * content-based equality -- Kotlin's [ByteArray] uses reference equality, so
 * a raw `ByteArray` as a `Map` key would make every correlation lookup fail
 * even on a successful round trip (the bytes decoded off the wire from a PONG
 * are never reference-identical to the ones this device generated for the
 * matching PING). This is the exact footgun [Contact]'s own doc comment
 * already calls out and [NodeId]/[Distance] already establish the fix for
 * (Slice 2) -- this is that same fix, applied to transaction IDs (Slice 3).
 */
class TransactionId(val bytes: ByteArray) {

    init {
        require(bytes.size == SIZE_BYTES) { "TransactionId must be $SIZE_BYTES bytes, was ${bytes.size}" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransactionId) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "TransactionId(${bytes.joinToString("") { "%02x".format(it) }})"

    companion object {
        // Generous headroom over what PING/PONG's current concurrency needs --
        // leaves room for FIND_NODE/FIND_VALUE/STORE (a later slice) to reuse
        // this same field unchanged.
        const val SIZE_BYTES = 8

        private val random = SecureRandom()

        fun random(): TransactionId = TransactionId(ByteArray(SIZE_BYTES).also { random.nextBytes(it) })
    }
}
