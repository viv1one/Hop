package com.hop.p2p

import com.hop.dht.PeerAddress
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Loopback coverage for [PeerListener] -- the "accept inbound connections"
 * half of this module, paired with [PeerDialerTest] ("get connected, dialing
 * out") and [PeerChannelTest] ("exchange bytes once connected"). Matches
 * those two test classes' real-loopback-socket style rather than mocking the
 * transport layer, same posture `RelayNodeTest` establishes for
 * `tools/relay-node/`'s own accept-loop class.
 */
class PeerListenerTest {

    private fun newServerSocket(): ServerSocket =
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    @Test
    fun `accepts a connection dialed via PeerDialer and exchanges a WireEnvelope round trip on both sides`() {
        val serverSocket = newServerSocket()
        val accepted = LinkedBlockingQueue<PeerChannel>()
        val listener = PeerListener(serverSocket) { channel -> accepted.add(channel) }
        listener.start()
        try {
            val candidate = PeerAddress.from(InetAddress.getByName("127.0.0.1"), serverSocket.localPort)
            val clientSocket = PeerDialer.dial(listOf(candidate))
            val clientChannel = PeerChannel(clientSocket)

            val serverChannel = accepted.poll(2, TimeUnit.SECONDS) ?: fail("PeerListener never invoked onConnected")

            val fromClient = WireEnvelope(WirePayloadType.POST_FRAME, byteArrayOf(1, 2, 3))
            clientChannel.sendEnvelope(fromClient)
            assertEquals(fromClient, serverChannel.receiveEnvelope())

            val fromServer = WireEnvelope(WirePayloadType.TIER_KEY_RESPONSE, byteArrayOf(9, 8))
            serverChannel.sendEnvelope(fromServer)
            assertEquals(fromServer, clientChannel.receiveEnvelope())

            clientChannel.close()
            serverChannel.close()
        } finally {
            listener.stop()
        }
    }

    @Test
    fun `multiple sequential connections are each accepted and handed to the callback`() {
        val serverSocket = newServerSocket()
        val accepted = LinkedBlockingQueue<PeerChannel>()
        val listener = PeerListener(serverSocket) { channel -> accepted.add(channel) }
        listener.start()
        try {
            val connectionCount = 5
            repeat(connectionCount) { i ->
                val candidate = PeerAddress.from(InetAddress.getByName("127.0.0.1"), serverSocket.localPort)
                val clientSocket = PeerDialer.dial(listOf(candidate))
                val clientChannel = PeerChannel(clientSocket)

                val serverChannel = accepted.poll(2, TimeUnit.SECONDS)
                    ?: fail("PeerListener never invoked onConnected for connection #$i")

                val envelope = WireEnvelope(WirePayloadType.POST_FRAME, byteArrayOf(i.toByte()))
                clientChannel.sendEnvelope(envelope)
                assertEquals(envelope, serverChannel.receiveEnvelope())

                clientChannel.close()
                serverChannel.close()
            }
            assertTrue(accepted.isEmpty(), "every accepted connection should have been consumed by the loop above")
        } finally {
            listener.stop()
        }
    }

    @Test
    fun `stop unblocks a pending accept cleanly without hanging or throwing`() {
        val serverSocket = newServerSocket()
        val accepted = LinkedBlockingQueue<PeerChannel>()
        val listener = PeerListener(serverSocket) { channel -> accepted.add(channel) }
        listener.start()

        // No connection is ever dialed -- the accept loop's accept() call is
        // left genuinely pending when stop() is called below.
        val startedAtMs = System.currentTimeMillis()
        listener.stop()
        val elapsedMs = System.currentTimeMillis() - startedAtMs

        assertTrue(
            elapsedMs < 5_000,
            "stop() took ${elapsedMs}ms to return -- expected it to unblock the pending accept() promptly",
        )
        assertTrue(accepted.isEmpty(), "no connection was ever dialed, so onConnected should never have fired")
    }

    @Test
    fun `stop closes already-accepted sockets so their channels observe the connection is gone`() {
        val serverSocket = newServerSocket()
        val accepted = LinkedBlockingQueue<PeerChannel>()
        val listener = PeerListener(serverSocket) { channel -> accepted.add(channel) }
        listener.start()

        val candidate = PeerAddress.from(InetAddress.getByName("127.0.0.1"), serverSocket.localPort)
        val clientSocket = PeerDialer.dial(listOf(candidate))
        val clientChannel = PeerChannel(clientSocket)

        val serverChannel = accepted.poll(2, TimeUnit.SECONDS) ?: fail("PeerListener never invoked onConnected")

        listener.stop()

        // The server side's socket was closed by stop() out from under the
        // still-open client socket -- the client observes that as EOF (or an
        // I/O failure) the next time it tries to read, proving stop() really
        // closed the already-accepted connection rather than merely leaving
        // it open and unattended.
        assertFailsWith<Exception> { clientChannel.receiveEnvelope() }

        clientChannel.close()
        serverChannel.close()
    }

    @Test
    fun `start is a no-op when called a second time`() {
        val serverSocket = newServerSocket()
        val accepted = LinkedBlockingQueue<PeerChannel>()
        val listener = PeerListener(serverSocket) { channel -> accepted.add(channel) }
        listener.start()
        listener.start()
        try {
            val candidate = PeerAddress.from(InetAddress.getByName("127.0.0.1"), serverSocket.localPort)
            val clientSocket = PeerDialer.dial(listOf(candidate))
            val clientChannel = PeerChannel(clientSocket)
            val serverChannel = accepted.poll(2, TimeUnit.SECONDS) ?: fail("PeerListener never invoked onConnected")
            clientChannel.close()
            serverChannel.close()
        } finally {
            listener.stop()
        }
    }
}
