package com.hop.p2p

import com.hop.protocol.WireEnvelope
import com.hop.protocol.WireEnvelopeDecodeException
import com.hop.protocol.WirePayloadType
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Loopback round-trip coverage for [PeerChannel] — the "exchange bytes"
 * half of this module. [PeerDialerTest] covers the "get connected" half
 * ([PeerDialer]) separately.
 */
class PeerChannelTest {

    @Test
    fun `sends and receives a WireEnvelope round trip over a real TCP socket`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 50, loopback)
        try {
            val client = Socket()
            client.connect(java.net.InetSocketAddress(loopback, server.localPort), 2_000)
            val accepted = server.accept()

            val clientChannel = PeerChannel(client)
            val serverChannel = PeerChannel(accepted)

            val sent = WireEnvelope(WirePayloadType.POST_FRAME, byteArrayOf(1, 2, 3, 4, 5))
            clientChannel.sendEnvelope(sent)
            val received = serverChannel.receiveEnvelope()

            assertEquals(sent, received)

            clientChannel.close()
            serverChannel.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `round trips in both directions over the same connection`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 50, loopback)
        try {
            val client = Socket()
            client.connect(java.net.InetSocketAddress(loopback, server.localPort), 2_000)
            val accepted = server.accept()

            val clientChannel = PeerChannel(client)
            val serverChannel = PeerChannel(accepted)

            val fromClient = WireEnvelope(WirePayloadType.TIER_KEY_REQUEST, byteArrayOf(9, 8, 7))
            clientChannel.sendEnvelope(fromClient)
            assertEquals(fromClient, serverChannel.receiveEnvelope())

            val fromServer = WireEnvelope(WirePayloadType.TIER_KEY_RESPONSE, byteArrayOf(6, 5, 4, 3))
            serverChannel.sendEnvelope(fromServer)
            assertEquals(fromServer, clientChannel.receiveEnvelope())

            clientChannel.close()
            serverChannel.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `handles an empty payload envelope`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 50, loopback)
        try {
            val client = Socket()
            client.connect(java.net.InetSocketAddress(loopback, server.localPort), 2_000)
            val accepted = server.accept()

            val clientChannel = PeerChannel(client)
            val serverChannel = PeerChannel(accepted)

            val sent = WireEnvelope(WirePayloadType.DONT_RELAY_FLAG, ByteArray(0))
            clientChannel.sendEnvelope(sent)
            assertEquals(sent, serverChannel.receiveEnvelope())

            clientChannel.close()
            serverChannel.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `sendRawBytes round trips correctly against WireEnvelope decode on the receiving side`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 50, loopback)
        try {
            val client = Socket()
            client.connect(java.net.InetSocketAddress(loopback, server.localPort), 2_000)
            val accepted = server.accept()

            val clientChannel = PeerChannel(client)
            val serverChannel = PeerChannel(accepted)

            // Simulates a caller (e.g. mobile/android's *Repository.buildOutgoingBacklog())
            // that already has a fully WireEnvelope.encode()'d blob in hand and
            // just wants it written to the socket as-is, with no re-encode step.
            val sent = WireEnvelope(WirePayloadType.POST_FRAME, byteArrayOf(10, 20, 30))
            clientChannel.sendRawBytes(sent.encode())
            val received = serverChannel.receiveEnvelope()

            assertEquals(sent, received)

            clientChannel.close()
            serverChannel.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `sendRawBytes can carry more than one concatenated envelope in a single call, same as sendEnvelope called twice`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 50, loopback)
        try {
            val client = Socket()
            client.connect(java.net.InetSocketAddress(loopback, server.localPort), 2_000)
            val accepted = server.accept()

            val clientChannel = PeerChannel(client)
            val serverChannel = PeerChannel(accepted)

            val first = WireEnvelope(WirePayloadType.DONT_RELAY_FLAG, byteArrayOf(1))
            val second = WireEnvelope(WirePayloadType.PREKEY_BUNDLE, byteArrayOf(2, 3))
            clientChannel.sendRawBytes(first.encode() + second.encode())

            assertEquals(first, serverChannel.receiveEnvelope())
            assertEquals(second, serverChannel.receiveEnvelope())

            clientChannel.close()
            serverChannel.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `receiveEnvelope throws EOFException once the peer closes the connection`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 50, loopback)
        try {
            val client = Socket()
            client.connect(java.net.InetSocketAddress(loopback, server.localPort), 2_000)
            val accepted = server.accept()

            val serverChannel = PeerChannel(accepted)
            client.close()

            assertFailsWith<java.io.EOFException> { serverChannel.receiveEnvelope() }
        } finally {
            server.close()
        }
    }

    @Test
    fun `receiveEnvelope rejects a declared payload length over MAX_PAYLOAD_BYTES before allocating`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 50, loopback)
        try {
            val client = Socket()
            client.connect(java.net.InetSocketAddress(loopback, server.localPort), 2_000)
            val accepted = server.accept()

            // Hand-craft a header claiming a payload one byte over the cap --
            // never actually send that many payload bytes, so this test would
            // hang/OOM instead of failing fast if the length check didn't
            // happen before the allocation.
            val maliciousHeader = ByteBuffer.allocate(WireEnvelope.HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .put(WirePayloadType.POST_FRAME.wireValue.toByte())
                .putInt(PeerChannel.MAX_PAYLOAD_BYTES + 1)
                .array()
            DataOutputStream(client.getOutputStream()).write(maliciousHeader)

            val serverChannel = PeerChannel(accepted)
            assertFailsWith<WireEnvelopeDecodeException> { serverChannel.receiveEnvelope() }

            client.close()
        } finally {
            server.close()
        }
    }
}
