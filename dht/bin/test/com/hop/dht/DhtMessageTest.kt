package com.hop.dht

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DhtMessageTest {

    private fun senderId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    @Test
    fun `PING round trip preserves all fields`() {
        val original = DhtMessage(DhtMessageType.PING, TransactionId.random(), senderId(1))
        val decoded = DhtMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `PONG round trip preserves all fields`() {
        val original = DhtMessage(DhtMessageType.PONG, TransactionId.random(), senderId(2))
        val decoded = DhtMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `both PING and PONG encode to the same fixed size`() {
        val ping = DhtMessage(DhtMessageType.PING, TransactionId.random(), senderId(1)).encode()
        val pong = DhtMessage(DhtMessageType.PONG, TransactionId.random(), senderId(1)).encode()
        assertEquals(DhtMessage.WIRE_SIZE, ping.size)
        assertEquals(DhtMessage.WIRE_SIZE, pong.size)
    }

    @Test
    fun `decode rejects wrong length`() {
        assertFailsWith<DhtMessageDecodeException> { DhtMessage.decode(ByteArray(DhtMessage.WIRE_SIZE - 1)) }
        assertFailsWith<DhtMessageDecodeException> { DhtMessage.decode(ByteArray(DhtMessage.WIRE_SIZE + 1)) }
    }

    @Test
    fun `decode rejects unknown version`() {
        val bytes = DhtMessage(DhtMessageType.PING, TransactionId.random(), senderId(1)).encode()
        bytes[0] = 99 // corrupt the version byte
        val exception = assertFailsWith<DhtMessageDecodeException> { DhtMessage.decode(bytes) }
        assertEquals(true, exception.message?.contains("version"))
    }

    @Test
    fun `decode rejects unknown type`() {
        val bytes = DhtMessage(DhtMessageType.PING, TransactionId.random(), senderId(1)).encode()
        bytes[1] = 99 // corrupt the type byte
        assertFailsWith<DhtMessageDecodeException> { DhtMessage.decode(bytes) }
    }
}
