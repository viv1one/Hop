package com.hop.dht

/**
 * A known peer in the routing table.
 *
 * [address] follows the same convention as [BundleQueueEntity.encodedEnvelope] /
 * [PendingMessageEntity.encodedEnvelope]: an opaque [ByteArray] placeholder for a
 * concrete type this slice doesn't decide. This slice never inspects, parses, or
 * connects to [address] -- its structure (IP:port, or whatever the network-
 * transport slice settles on) is owned entirely by that future slice.
 */
data class Contact(
    val id: NodeId,
    val address: ByteArray,
    val lastSeenAtMs: Long,
) {
    // Manual equals/hashCode: the data class default would use reference equality
    // for the ByteArray field, which is never what we want for a value-like Contact.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Contact) return false
        return id == other.id && address.contentEquals(other.address) && lastSeenAtMs == other.lastSeenAtMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + address.contentHashCode()
        result = 31 * result + lastSeenAtMs.hashCode()
        return result
    }
}
