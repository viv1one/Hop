package com.hop.crypto

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [MessagePayload.encode]/[MessagePayload.decode] round-trip, independent of
 * [DoubleRatchetSession] -- this type is deliberately payload-shape-agnostic
 * from the ratchet's own encrypt/decrypt calls (see its own class doc), so
 * these tests exercise the codec on its own, not wrapped in a real session.
 */
class MessagePayloadTest {

    @Test
    fun `Text with a null groupId round-trips (plain 1-1 message, today's unchanged shape)`() {
        val original = MessagePayload.Text(groupId = null, text = "hey, on my way")

        val decoded = MessagePayload.decode(original.encode())

        assertEquals(original, decoded)
        assertEquals(null, (decoded as MessagePayload.Text).groupId)
    }

    @Test
    fun `Text with a groupId round-trips (a member's fan-out copy of a group message)`() {
        val original = MessagePayload.Text(groupId = "a1b2c3d4", text = "see you all there")

        val decoded = MessagePayload.decode(original.encode())

        assertEquals(original, decoded)
        assertEquals("a1b2c3d4", (decoded as MessagePayload.Text).groupId)
    }

    @Test
    fun `Text with empty text round-trips`() {
        val original = MessagePayload.Text(groupId = null, text = "")

        assertEquals(original, MessagePayload.decode(original.encode()))
    }

    @Test
    fun `GroupInvite round-trips including its member list`() {
        val original = MessagePayload.GroupInvite(
            groupId = "deadbeef",
            name = "Weekend trip",
            memberPeerIds = listOf("peer-b", "peer-c", "peer-d"),
        )

        val decoded = MessagePayload.decode(original.encode())

        assertEquals(original, decoded)
    }

    @Test
    fun `GroupInvite with an empty member list round-trips`() {
        val original = MessagePayload.GroupInvite(groupId = "deadbeef", name = "Solo group", memberPeerIds = emptyList())

        assertEquals(original, MessagePayload.decode(original.encode()))
    }

    @Test
    fun `decoding an unknown type tag fails loudly rather than silently misparsing`() {
        val bogus = byteArrayOf(99)

        assertFailsWith<IllegalArgumentException> { MessagePayload.decode(bogus) }
    }

    @Test
    fun `decoding truncated bytes fails loudly rather than silently misparsing`() {
        val truncated = MessagePayload.Text(groupId = null, text = "a full message").encode().copyOf(2)

        assertFailsWith<Exception> { MessagePayload.decode(truncated) }
    }
}
