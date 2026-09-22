package com.hop.p2p

import com.hop.dht.PeerAddress
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Loopback coverage for [PeerDialer]'s IPv6-first race/fallback logic.
 * [PeerChannelTest] covers the "exchange bytes" half of this module
 * separately.
 *
 * **IPv6 loopback availability.** Several tests here need a real, working
 * `::1` (bindable AND actually connectable) to be meaningful. Per this
 * slice's own instructions, that's gracefully degraded via
 * [assumeIpv6LoopbackAvailable] rather than failing the whole suite when the
 * sandbox running these tests doesn't have a working IPv6 loopback --
 * [org.junit.jupiter.api.Assumptions.assumeTrue] turns the affected test
 * into a reported "skipped," not a failure, in that case. See this test
 * class's own committed run output (reported alongside this slice) for
 * whether that path was actually exercised in this environment.
 */
class PeerDialerTest {

    /** Binds and immediately closes an IPv6 loopback server socket -- throws if IPv6 loopback isn't usable at all here. */
    private fun assumeIpv6LoopbackAvailable() {
        val available = try {
            val probe = ServerSocket(0, 50, InetAddress.getByName("::1"))
            probe.close()
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue(available, "IPv6 loopback (::1) is not available in this sandbox -- skipping")
    }

    @Test
    fun `connects using the only IPv4 candidate given`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 50, loopback)
        try {
            val candidate = PeerAddress.from(loopback, server.localPort)
            val clientSocket = PeerDialer.dial(listOf(candidate))
            try {
                val accepted = server.accept()
                assertTrue(clientSocket.isConnected)
                accepted.close()
            } finally {
                clientSocket.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `connects using the only IPv6 candidate given`() {
        assumeIpv6LoopbackAvailable()
        val loopback = InetAddress.getByName("::1")
        val server = ServerSocket(0, 50, loopback)
        try {
            val candidate = PeerAddress.from(loopback, server.localPort)
            val clientSocket = PeerDialer.dial(listOf(candidate))
            try {
                val accepted = server.accept()
                assertTrue(clientSocket.isConnected)
                assertTrue(clientSocket.inetAddress is Inet6Address)
                accepted.close()
            } finally {
                clientSocket.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `prefers the IPv6 candidate over IPv4 when both are reachable`() {
        assumeIpv6LoopbackAvailable()
        val ipv6Loopback = InetAddress.getByName("::1")
        val ipv4Loopback = InetAddress.getByName("127.0.0.1")

        val ipv6Server = ServerSocket(0, 50, ipv6Loopback)
        val ipv4Server = ServerSocket(0, 50, ipv4Loopback)
        try {
            val ipv6Candidate = PeerAddress.from(ipv6Loopback, ipv6Server.localPort)
            val ipv4Candidate = PeerAddress.from(ipv4Loopback, ipv4Server.localPort)

            // Order in the input list must not matter -- family, not list position, decides.
            val clientSocket = PeerDialer.dial(listOf(ipv4Candidate, ipv6Candidate))
            try {
                assertTrue(clientSocket.inetAddress is Inet6Address, "dial() should have connected via the IPv6 candidate")

                val acceptedOnIpv6 = ipv6Server.accept()
                acceptedOnIpv6.close()

                // Confirm the IPv4 listener never received a connection at all.
                ipv4Server.soTimeout = 500
                assertFailsWith<SocketTimeoutException> { ipv4Server.accept() }
            } finally {
                clientSocket.close()
            }
        } finally {
            ipv6Server.close()
            ipv4Server.close()
        }
    }

    @Test
    fun `falls back to IPv4 when the IPv6 candidate is unreachable`() {
        val ipv4Loopback = InetAddress.getByName("127.0.0.1")
        val ipv4Server = ServerSocket(0, 50, ipv4Loopback)
        try {
            // A port nobody is listening on: bind then immediately release it.
            val deadPortProbe = ServerSocket(0, 50, InetAddress.getByName("::1"))
            val deadIpv6Port = deadPortProbe.localPort
            deadPortProbe.close()

            val unreachableIpv6Candidate = PeerAddress.from(InetAddress.getByName("::1"), deadIpv6Port)
            val ipv4Candidate = PeerAddress.from(ipv4Loopback, ipv4Server.localPort)

            val clientSocket = PeerDialer.dial(listOf(unreachableIpv6Candidate, ipv4Candidate))
            try {
                assertTrue(clientSocket.inetAddress is Inet4Address, "dial() should have fallen back to the IPv4 candidate")
                val accepted = ipv4Server.accept()
                accepted.close()
            } finally {
                clientSocket.close()
            }
        } finally {
            ipv4Server.close()
        }
    }

    @Test
    fun `throws PeerDialException without hanging when every candidate is unreachable`() {
        // A closed IPv4 loopback port -- guaranteed nobody is listening.
        val deadIpv4Probe = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val deadIpv4Port = deadIpv4Probe.localPort
        deadIpv4Probe.close()

        val deadIpv6Probe = ServerSocket(0, 50, InetAddress.getByName("::1"))
        val deadIpv6Port = deadIpv6Probe.localPort
        deadIpv6Probe.close()

        val ipv6Candidate = PeerAddress.from(InetAddress.getByName("::1"), deadIpv6Port)
        val ipv4Candidate = PeerAddress.from(InetAddress.getByName("127.0.0.1"), deadIpv4Port)

        val startedAtMs = System.currentTimeMillis()
        assertFailsWith<PeerDialException> { PeerDialer.dial(listOf(ipv6Candidate, ipv4Candidate)) }
        val elapsedMs = System.currentTimeMillis() - startedAtMs

        // Generous upper bound: IPv6_FIRST_ATTEMPT_TIMEOUT_MS + FALLBACK_CONNECT_TIMEOUT_MS
        // plus real slack, well under this. The point is "doesn't hang forever,"
        // not pinning an exact number -- a closed loopback port normally fails
        // near-instantly (ECONNREFUSED), well inside this bound.
        assertTrue(
            elapsedMs < 10_000,
            "dial() took ${elapsedMs}ms to give up on two unreachable candidates -- expected a bounded failure, not a hang",
        )
    }

    @Test
    fun `throws PeerDialException when given no candidates at all`() {
        assertFailsWith<PeerDialException> { PeerDialer.dial(emptyList()) }
    }
}
