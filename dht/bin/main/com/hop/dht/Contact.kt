package com.hop.dht

/**
 * A known peer in the routing table.
 *
 * [address] is a wire-opaque [ByteArray] as far as [Contact]/[RoutingTable]/
 * [KBucket]/every wire-framing class in this module ([ContactListCodec],
 * [FindNodeResponseMessage], [StoreRequestMessage]/[StoreResponseMessage],
 * [FindValueResponseMessage]) is concerned -- none of them ever inspect what's
 * *inside* it, they only carry it as a length-prefixed blob. Decoding it is
 * entirely a consumer choice: [PeerAddress.decode] for exactly one address, or
 * [PeerAddress.decodeList]/[PeerAddress.encodeList] for one or more -- e.g. a
 * dual-stack device self-announcing both an IPv6 and an IPv4 [PeerAddress]
 * (Phase 4's IPv6-first goal) inside this same opaque blob, no wire-format
 * change required. See [DhtNode]'s `ownAddresses` constructor param for where
 * that self-announcement is built.
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
