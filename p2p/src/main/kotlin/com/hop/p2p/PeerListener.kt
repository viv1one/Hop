package com.hop.p2p

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Accepts inbound TCP connections and hands each one, wrapped in a
 * [PeerChannel], to a caller-supplied [onConnected] callback -- the missing
 * "accept inbound connections" half of this module. [PeerDialer] dials out
 * to a known [com.hop.dht.PeerAddress]; [PeerChannel] exchanges
 * [com.hop.protocol.WireEnvelope]-framed bytes once connected; until this
 * class, nothing in `p2p/` (or anywhere else in this codebase's real
 * content-exchange path) ever *accepted* a connection dialed at it.
 *
 * **Why this class exists -- the concrete gap it closes.** Phase 4's NAT
 * hole-punching machinery (address self-discovery via
 * `DhtUdpTransport.reflectOwnAddress`, rendezvous-relayed introduction via
 * `DhtUdpTransport.introduce`, relay-fallback coordination -- all already
 * built) is correctly built for exactly what it claims: helping two peers
 * learn each other's reachable address (or agree on a shared relay) so that
 * an ordinary dial can then succeed. But "the other side attempts an
 * ordinary dial back" only works if something is actually listening on that
 * address. Before this class, nothing was -- `grep -rl ServerSocket p2p/
 * mobile/android/app/src/main/kotlin/com/hop/transport/` only ever found
 * `WifiDirectTransport` (a different, local-mesh transport, not this
 * internet-mode path) and test files. [PeerListener] is that missing
 * listener, built to the same "small, dumb, testable" posture as
 * [PeerDialer]/[PeerChannel].
 *
 * **What this class does NOT do -- deliberately, matching [PeerChannel]'s
 * own "dumb about payload contents" posture.** It knows nothing about
 * `WireEnvelope`, `WirePayloadType`, handshakes, or any of HOP's protocol
 * semantics -- it accepts a raw TCP connection, wraps it in a [PeerChannel],
 * and calls [onConnected] with that channel. What happens next (reading a
 * handshake, dispatching envelopes, registering the resulting channel with
 * whatever connection registry the caller maintains) is entirely the
 * caller's job.
 *
 * **What this class does NOT close -- real, separate follow-on work, not
 * attempted here.** Nothing in `mobile/android/app` wires this in yet. A
 * device running today's app still cannot be dialed from the internet --
 * only dial out, via `InternetPeerConnection.connectTo`. Composing a
 * [PeerListener] into `InternetPeerConnectionManager`/`AppContainer` so a
 * real device actually listens is separate, later, Android-app-layer work:
 * this module has no visibility into which port to bind, when to start/stop
 * the listener relative to the app's foreground/background lifecycle, how a
 * long-lived listening socket interacts with the existing WiFi Direct
 * lifecycle, or device-side firewall/NAT considerations for inbound
 * connections -- all of that is the caller's design problem, not this
 * class's.
 *
 * **Lifecycle, mirroring `RelayNode`'s/`RendezvousNode`'s exact shape.**
 * Constructor takes an already-bound [ServerSocket] (caller creates and
 * binds it -- same "caller owns bind/port choice" posture as `RelayNode`'s
 * own constructor, chosen for the same reason: it lets tests bind to
 * loopback + port 0 without this class needing to know anything about port
 * selection). [start] spawns a dedicated accept-loop thread; safe to call
 * once, a second call is a no-op. [stop] stops accepting new connections,
 * closes the [ServerSocket] (unblocking a pending `accept()`), and closes
 * every socket this listener has accepted and not yet been told is closed --
 * mirroring `RelayNode.trackedSockets`'s exact "track everything accepted so
 * stop() can clean it all up" approach. Unlike [RelayNode], this class never
 * pairs or bridges two connections -- each accepted connection is handed off
 * to [onConnected] independently, with no matching/waiting-registry logic at
 * all.
 */
class PeerListener(
    private val serverSocket: ServerSocket,
    private val onConnected: (PeerChannel) -> Unit,
) {
    /** Every socket this listener has accepted and not yet closed -- so [stop] can close everything still open, mirroring `RelayNode.trackedSockets`. */
    private val trackedSockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var running = false

    @Volatile
    private var acceptThread: Thread? = null

    /** Spawns the accept loop. Safe to call once; a second call is a no-op. */
    fun start() {
        if (running) return
        running = true
        acceptThread = Thread({ acceptLoop() }, "hop-peer-listener-accept").also { it.isDaemon = true; it.start() }
    }

    /**
     * Stops accepting new connections, closes [serverSocket] (unblocking a
     * pending `accept()`), and closes every socket this listener currently
     * has open. Safe to call even if [start] was never called, or has
     * already been called once.
     */
    fun stop() {
        running = false
        closeQuietly(serverSocket)

        val snapshot = trackedSockets.toList()
        snapshot.forEach { closeAndUntrack(it) }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                // Expected on stop() closing serverSocket to unblock a
                // pending accept() call -- if we're still supposed to be
                // running, this was some other transient I/O error; keep
                // serving rather than letting one hiccup kill the whole
                // accept loop, matching RelayNode.acceptLoop's own posture.
                if (!running) break
                continue
            }
            trackedSockets.add(socket)
            handleAccepted(socket)
        }
    }

    /**
     * Wraps [socket] in a [PeerChannel] and hands it to [onConnected]. Any
     * exception thrown by [onConnected] is swallowed here (logged nowhere,
     * matching this module's other classes' minimal-dependency posture --
     * `p2p/` has no logging dependency of its own) rather than allowed to
     * kill the shared accept-loop thread; a caller that wants its own
     * exception handling should do it inside [onConnected].
     */
    private fun handleAccepted(socket: Socket) {
        try {
            onConnected(PeerChannel(socket))
        } catch (e: Exception) {
            closeAndUntrack(socket)
        }
    }

    private fun closeAndUntrack(socket: Socket) {
        trackedSockets.remove(socket)
        closeQuietly(socket)
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            // Already closed, or never fully connected -- nothing to do.
        }
    }

    private fun closeQuietly(serverSocket: ServerSocket) {
        try {
            serverSocket.close()
        } catch (e: IOException) {
            // Already closed.
        }
    }
}
