package com.hop.relaynode

import com.hop.dht.NodeId
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real-socket-pair testing, matching this codebase's established style
 * (`PeerChannelTest`/`RendezvousNodeTest`): plain test-side [Socket]s
 * connecting to a real [RelayNode] over a real loopback [ServerSocket],
 * playing the role of two clients attempting relay fallback. Wiring an
 * actual client-side dial path is separate, later work (see [RelayNode]'s
 * class doc) -- this file only proves the standalone relay's own bridging
 * primitive.
 *
 * The last test below is this module's whole reason to have the structural
 * shape it does per [RelayNode]'s class doc: zero dependency on `protocol/`,
 * enforced here at the code level rather than left as prose alone.
 */
class RelayNodeTest {

    private fun nodeId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    private fun newRelay(
        maxConcurrentSlots: Int = RelayNode.DEFAULT_MAX_CONCURRENT_SLOTS,
        waitTimeoutMs: Long = 2_000L,
    ): Pair<RelayNode, Int> {
        val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val relay = RelayNode(serverSocket, maxConcurrentSlots = maxConcurrentSlots, waitTimeoutMs = waitTimeoutMs)
        return relay to serverSocket.localPort
    }

    private fun connect(port: Int): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
        return socket
    }

    private fun sendHandshake(socket: Socket, ownId: NodeId, bridgeToId: NodeId) {
        socket.getOutputStream().write(ownId.bytes + bridgeToId.bytes)
        socket.getOutputStream().flush()
    }

    private fun readExactly(socket: Socket, expectedLength: Int): ByteArray {
        val buffer = ByteArray(expectedLength)
        var read = 0
        while (read < expectedLength) {
            val n = socket.getInputStream().read(buffer, read, expectedLength - read)
            assertTrue(n >= 0, "connection closed before receiving all expected bytes")
            read += n
        }
        return buffer
    }

    @Test
    fun `two mutually-matching connections are bridged, bytes flow correctly in both directions`() {
        val (relay, port) = newRelay()
        relay.start()
        try {
            val a = connect(port)
            val b = connect(port)
            val idA = nodeId(1)
            val idB = nodeId(2)

            // A declares "I am A, bridge me to B"; B declares the exact
            // mutual reverse -- the only shape RelayNode ever pairs.
            sendHandshake(a, idA, idB)
            sendHandshake(b, idB, idA)

            val fromA = "hello-from-a".toByteArray()
            a.getOutputStream().write(fromA)
            a.getOutputStream().flush()
            assertEquals(String(fromA), String(readExactly(b, fromA.size)), "bytes sent by A must arrive unmodified on B")

            val fromB = "hello-from-b-and-then-some".toByteArray()
            b.getOutputStream().write(fromB)
            b.getOutputStream().flush()
            assertEquals(String(fromB), String(readExactly(a, fromB.size)), "bytes sent by B must arrive unmodified on A -- proving genuine bidirectional forwarding, not just one direction")

            a.close()
            b.close()
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `a connection that never gets a match times out and is closed`() {
        val (relay, port) = newRelay(waitTimeoutMs = 300L)
        relay.start()
        try {
            val lonely = connect(port)
            sendHandshake(lonely, nodeId(10), nodeId(11))

            // No matching peer ever connects. Within a bounded window past
            // the configured timeout, the relay must close its end -- a
            // read here must return EOF (-1), not hang forever.
            lonely.soTimeout = 3_000
            val result = lonely.getInputStream().read()
            assertEquals(-1, result, "an unmatched connection must be closed (EOF) once it exceeds waitTimeoutMs, not hang forever")

            lonely.close()
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `a connection beyond the configured cap is rejected and closed rather than accepted and left to starve`() {
        val (relay, port) = newRelay(maxConcurrentSlots = 2, waitTimeoutMs = 5_000L)
        relay.start()
        try {
            // Fill the cap with two unmatched (never-to-be-matched) waiting connections.
            val first = connect(port)
            sendHandshake(first, nodeId(20), nodeId(21))
            val second = connect(port)
            sendHandshake(second, nodeId(22), nodeId(23))

            // Give the relay a moment to register both as waiting before the third arrives.
            Thread.sleep(200)

            val third = connect(port)
            sendHandshake(third, nodeId(24), nodeId(25))

            third.soTimeout = 3_000
            val result = third.getInputStream().read()
            assertEquals(-1, result, "a connection beyond the configured cap must be rejected/closed immediately, not accepted and left to starve")

            first.close()
            second.close()
            third.close()
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `either side closing its socket tears down the whole bridge`() {
        val (relay, port) = newRelay()
        relay.start()
        try {
            val a = connect(port)
            val b = connect(port)
            val idA = nodeId(30)
            val idB = nodeId(31)

            sendHandshake(a, idA, idB)
            sendHandshake(b, idB, idA)

            // Give the relay a moment to actually form the bridge before
            // tearing one side down.
            Thread.sleep(200)

            a.close()

            b.soTimeout = 3_000
            val result = b.getInputStream().read()
            assertEquals(-1, result, "closing one side of a bridge must tear down the other side too, not leak it half-open")

            b.close()
        } finally {
            relay.stop()
        }
    }

    // ---- The critical structural check: this module's whole reason to have the shape it does ----

    @Test
    fun `this module has zero dependency on protocol -- by construction, not policy`() {
        val buildFile = File("build.gradle.kts")
        assertTrue(
            buildFile.exists(),
            "expected to find tools/relay-node's own build.gradle.kts as this test task's working directory's build file",
        )
        val buildFileText = buildFile.readText()
        // Checks for the actual dependency-declaration shape
        // (`project(":protocol")`), not the bare substring ":protocol" --
        // this file's own comments legitimately mention ":protocol" by name
        // to explain why it must never be added as a real dependency.
        assertFalse(
            buildFileText.contains("project(\":protocol\")"),
            "tools/relay-node must never depend on :protocol, directly or transitively -- see RelayNode's class doc",
        )

        val mainSourceRoot = File("src/main/kotlin")
        assertTrue(mainSourceRoot.exists(), "expected to find this module's own main source root at src/main/kotlin")
        // Checks for an actual `import com.hop.protocol...` statement, not
        // the bare substring -- RelayNode.kt's own class doc legitimately
        // names `com.hop.protocol.WireEnvelope` in a KDoc `[...]` reference
        // to explain why this module must never import it for real.
        val protocolImports = mainSourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().mapIndexed { lineIndex, line -> Triple(file, lineIndex + 1, line) } }
            .filter { (_, _, line) -> line.trim().startsWith("import com.hop.protocol") }
            .toList()
        assertTrue(
            protocolImports.isEmpty(),
            "found an import of com.hop.protocol in this module's main source -- must be zero, by construction, not just by policy: $protocolImports",
        )
    }
}
