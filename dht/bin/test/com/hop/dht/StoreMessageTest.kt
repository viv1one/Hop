package com.hop.dht

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StoreMessageTest {

    private fun nodeId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    @Test
    fun `request round trip preserves all fields`() {
        val original = StoreRequestMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            key = nodeId(2),
        )
        val decoded = StoreRequestMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `request encodes to the fixed 74-byte size`() {
        val message = StoreRequestMessage(TransactionId.random(), nodeId(1), nodeId(2))
        assertEquals(74, message.encode().size)
        assertEquals(StoreRequestMessage.WIRE_SIZE, message.encode().size)
    }

    @Test
    fun `request decode rejects wrong length`() {
        assertFailsWith<DhtMessageDecodeException> {
            StoreRequestMessage.decode(ByteArray(StoreRequestMessage.WIRE_SIZE - 1))
        }
        assertFailsWith<DhtMessageDecodeException> {
            StoreRequestMessage.decode(ByteArray(StoreRequestMessage.WIRE_SIZE + 1))
        }
    }

    @Test
    fun `request decode rejects unknown version`() {
        val bytes = StoreRequestMessage(TransactionId.random(), nodeId(1), nodeId(2)).encode()
        bytes[0] = 99
        assertFailsWith<DhtMessageDecodeException> { StoreRequestMessage.decode(bytes) }
    }

    @Test
    fun `request decode rejects a non-STORE_REQUEST type byte`() {
        val bytes = StoreRequestMessage(TransactionId.random(), nodeId(1), nodeId(2)).encode()
        bytes[1] = DhtMessageType.PING.wireValue.toByte()
        assertFailsWith<DhtMessageDecodeException> { StoreRequestMessage.decode(bytes) }
    }

    // ---- STORE_RESPONSE reuses DhtMessage's own shape/tests -- confirming that reuse works here. ----

    @Test
    fun `STORE_RESPONSE round trips as a bare DhtMessage ack, same shape as PONG`() {
        val original = DhtMessage(type = DhtMessageType.STORE_RESPONSE, transactionId = TransactionId.random(), senderId = nodeId(1))
        val decoded = DhtMessage.decode(original.encode())
        assertEquals(original, decoded)
        assertEquals(DhtMessage.WIRE_SIZE, original.encode().size)
    }
}
