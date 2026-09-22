package com.hop.relaynode

import com.hop.dht.Contact
import com.hop.dht.DhtUdpTransport
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import com.hop.dht.RelayDirectory
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch

/**
 * The operator-facing CLI entry point for [RelayNode] -- BUILD_PLAN.md Phase
 * 4's volunteer relay-node fallback for the symmetric-NAT case, made a
 * runnable standalone process. Read [RelayNode]'s own class doc IN FULL
 * before touching this file -- its trust-model section (availability isn't
 * guaranteed, traffic-analysis exposure is real and distinct from incidental
 * mesh relay, this is an unauthenticated open relay with the abuse caveat
 * that implies) is the same honesty this CLI's own `README.md` carries
 * forward, not something to soften into marketing copy.
 *
 * ## Who runs this
 *
 * **A volunteer / third-party operator -- explicitly NOT HOP itself.** Same
 * posture as `rendezvous/`'s own `RendezvousCli` -- see this module's own
 * `README.md` and [RelayNode]'s class doc for why a *dedicated* relay is
 * still an ordinary mesh-relay-shaped role (everything it forwards is
 * already end-to-end ciphertext) even when run as always-on infrastructure.
 *
 * ## What this does at startup
 *
 * 1. Parses arguments.
 * 2. Loads or creates this node's identity seed file (same
 *    [NodeId.fromKeyMaterial]-from-a-persisted-seed convention
 *    `RendezvousCli` establishes -- see [loadOrCreateNodeIdSeed]'s doc; less
 *    load-bearing here than for a rendezvous node, since a stale
 *    [RelayDirectory] entry simply falls out at its own TTL rather than
 *    poisoning a long-lived peer cache, but there is no reason for this
 *    relay's announced identity to churn on every restart either).
 * 3. Binds [RelayNode]'s TCP [ServerSocket] on `--port` -- the port real
 *    clients dial to be bridged.
 * 4. Binds a **separate** UDP [DatagramSocket] on `--announce-udp-port`
 *    (default ephemeral) for [RelayAnnouncer]'s own [DhtUdpTransport] --
 *    deliberately independent from the TCP bridge socket/port: RELAY_ANNOUNCE
 *    is a DHT-wire (UDP) RPC to the rendezvous contact, entirely separate
 *    from the raw-byte TCP bridging [RelayNode] itself does (see
 *    [RelayNode]'s own "Wire shape" doc for why the two must never share a
 *    framing).
 * 5. Starts [RelayNode] and the announce [DhtUdpTransport].
 * 6. Calls [RelayAnnouncer.announceOnce] once immediately, then again every
 *    [reannounceIntervalMs] on a background daemon thread -- see
 *    [startReannounceLoop]'s own doc for why this cadence is derived from
 *    [RelayDirectory.DEFAULT_ENTRY_TTL_MS] rather than an independently
 *    invented number. [RelayAnnouncer] itself deliberately does not own this
 *    scheduling (see its own class doc: "background scheduling is left to
 *    the caller") -- this CLI is that caller.
 * 7. Logs enough for an operator to confirm this relay is alive, bound, and
 *    what address it's announcing.
 * 8. Blocks (a shutdown hook stops everything on Ctrl-C/SIGTERM) until
 *    interrupted.
 *
 * ## Required arguments
 *
 * - `--port <int>`: TCP port [RelayNode]'s [ServerSocket] binds to -- the
 *   port real clients actually dial to request a bridge.
 * - `--rendezvous <host:port>`: the bootstrap/rendezvous node address (ADR
 *   0002) this relay announces itself to, so clients can discover it via
 *   RELAY_QUERY. Required, same reasoning as [com.hop.preseed.PreseedCli]'s
 *   own required `--rendezvous`: this tool's whole purpose depends on being
 *   discoverable.
 * - `--advertise-host <host>`: the externally-reachable hostname/IP this
 *   relay should announce as its own TCP bridge address. Cannot be
 *   auto-discovered -- this codebase has no NAT/external-address-discovery
 *   mechanism anywhere yet (same documented limitation as
 *   [com.hop.app.dht.DhtNodeManager]'s own `localBindAddress` doc) -- an
 *   operator running this behind NAT/port-forwarding must supply the address
 *   clients can actually reach, not this host's local bind address.
 *
 * ## Optional arguments
 *
 * - `--advertise-port <int>`: the externally-reachable port paired with
 *   `--advertise-host` (default: same as `--port`, the common case where
 *   nothing remaps the port between the operator's public address and this
 *   process's own bind port).
 * - `--seed-file <path>`: where this relay's identity seed is persisted
 *   (default [DEFAULT_SEED_FILE_NAME] in the current working directory).
 * - `--announce-udp-port <int>`: UDP bind port for the announce
 *   [DhtUdpTransport] (default `0`, ephemeral -- this socket is never itself
 *   dialed by anyone; it only needs to receive RELAY_ANNOUNCE_ACK/
 *   RELAY_ANNOUNCE replies).
 * - `--max-concurrent-slots <int>`: forwarded to [RelayNode] (default
 *   [RelayNode.DEFAULT_MAX_CONCURRENT_SLOTS]).
 * - `--wait-timeout-ms <long>`: forwarded to [RelayNode] (default
 *   [RelayNode.DEFAULT_WAIT_TIMEOUT_MS]).
 * - `--reannounce-interval-ms <long>`: how often this process re-announces
 *   itself to `--rendezvous` after the initial startup announcement (default
 *   [DEFAULT_REANNOUNCE_INTERVAL_MS] -- see [startReannounceLoop]'s doc).
 */
fun main(args: Array<String>) {
    val options = CliOptions.parse(args)

    val seedFile = File(options.seedFilePath)
    val seed = loadOrCreateNodeIdSeed(seedFile)
    val ownId = NodeId.fromKeyMaterial(seed)

    val serverSocket = ServerSocket(options.port)
    val relay = RelayNode(
        serverSocket = serverSocket,
        maxConcurrentSlots = options.maxConcurrentSlots,
        waitTimeoutMs = options.waitTimeoutMs,
    )

    val announceSocket = DatagramSocket(options.announceUdpPort)
    val announceTransport = DhtUdpTransport(announceSocket, ownId)

    // A throwaway placeholder id for the rendezvous contact -- same trick
    // DhtNode.bootstrapJoin's own doc already establishes for the identical
    // problem (we know the rendezvous node's ADDRESS, not its NodeId, ahead
    // of time). Safe here because DhtUdpTransport.announceRelay correlates
    // its response purely by transactionId + destination address (via
    // firstDialableAddress), never by validating the reply's senderId
    // against this placeholder -- see that method's own implementation.
    val (rendezvousHost, rendezvousPort) = options.rendezvous
    val rendezvousContact = Contact(
        id = NodeId(ByteArray(NodeId.SIZE_BYTES)),
        address = PeerAddress.from(InetAddress.getByName(rendezvousHost), rendezvousPort).encode(),
        lastSeenAtMs = 0L,
    )

    val ownBridgeAddress = PeerAddress.from(InetAddress.getByName(options.advertiseHost), options.advertisePort)
    val announcer = RelayAnnouncer(
        transport = announceTransport,
        bootstrapContact = rendezvousContact,
        ownBridgeAddress = ownBridgeAddress,
    )

    val shutdownLatch = CountDownLatch(1)
    val reannounceThread = startReannounceLoop(announcer, options.reannounceIntervalMs)
    Runtime.getRuntime().addShutdownHook(
        Thread({
            println("Shutting down...")
            reannounceThread.interrupt()
            relay.stop()
            announceTransport.stop()
            shutdownLatch.countDown()
        }, "hop-relay-node-shutdown"),
    )

    announceTransport.start()
    relay.start()
    println("Started -- ownNodeId=${ownId.bytes.toHexString()}, TCP bridge bound on port ${serverSocket.localPort}, announcing as $ownBridgeAddress to $rendezvousHost:$rendezvousPort")

    val ackedOnStartup = announcer.announceOnce()
    println(
        if (ackedOnStartup) {
            "Initial RELAY_ANNOUNCE acked by the rendezvous contact."
        } else {
            "Initial RELAY_ANNOUNCE was NOT acked -- the rendezvous contact may be unreachable. Retrying every ${options.reannounceIntervalMs}ms; this relay is still up and will bridge a direct dial regardless of announce status."
        },
    )
    println("Serving until interrupted (Ctrl-C). Unauthenticated open relay -- see RelayNode's class doc for this CLI's trust-model limits.")

    shutdownLatch.await()
}

/**
 * Spawns a daemon thread that calls [RelayAnnouncer.announceOnce] every
 * [intervalMs], starting after one interval (the CALLER -- [main] -- already
 * makes the immediate startup announcement itself, so this loop's first
 * action is to sleep, not to double-announce at t=0).
 *
 * **Why [intervalMs]'s default is derived from [RelayDirectory.DEFAULT_ENTRY_TTL_MS],
 * not an independently chosen number:** [RelayAnnouncer]'s own class doc is
 * explicit that it deliberately owns no scheduling itself and defers the
 * choice to its caller -- this function IS that caller's answer. The
 * `RelayDirectory` entry this announcement keeps alive expires
 * [RelayDirectory.DEFAULT_ENTRY_TTL_MS] after it's last (re-)announced; the
 * whole point of re-announcing on a loop at all is to keep that entry alive
 * for as long as this process keeps running, so the interval must stay
 * comfortably under that TTL rather than introducing an unrelated cadence.
 * [DEFAULT_REANNOUNCE_INTERVAL_MS] (a third of the TTL) is an unmeasured
 * placeholder choice within that constraint, same posture as every other
 * "unmeasured placeholder" constant elsewhere in this codebase -- a third
 * leaves two full retry attempts of margin before an entry could actually
 * expire even if one re-announce attempt is dropped, without re-announcing
 * so often it meaningfully increases this relay's own outbound RPC volume.
 * Revisit once a real deployment's actual announce-success rate is known.
 *
 * `internal`, not `private`, so [RelayNodeCliReachabilityTest] can drive this
 * exact loop directly with a short test interval.
 */
internal fun startReannounceLoop(announcer: RelayAnnouncer, intervalMs: Long): Thread {
    val thread = Thread({
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(intervalMs)
                announcer.announceOnce()
            }
        } catch (e: InterruptedException) {
            // Expected on shutdown -- see main's shutdown hook, which
            // interrupts this thread before stopping everything else.
        }
    }, "hop-relay-reannounce")
    thread.isDaemon = true
    thread.start()
    return thread
}

/**
 * Reads a 32-byte identity seed from [seedFile] if it already exists, or
 * generates a fresh random one and writes it there first. Mirrors
 * `RendezvousCli`'s own function of the same name/shape byte-for-byte --
 * duplicated rather than shared, since `rendezvous/` and `tools/relay-node/`
 * have no dependency relationship to each other and neither may gain one
 * just to share four lines of file I/O.
 *
 * `internal`, not `private`, so [RelayNodeCliReachabilityTest] can construct
 * a [RelayNode] via this exact same seed-loading step [main] itself uses.
 */
internal fun loadOrCreateNodeIdSeed(seedFile: File): ByteArray {
    if (seedFile.isFile) {
        val existing = seedFile.readBytes()
        require(existing.size == SEED_SIZE_BYTES) {
            "Seed file ${seedFile.path} has ${existing.size} bytes, expected $SEED_SIZE_BYTES -- refusing to use a malformed identity seed"
        }
        return existing
    }
    val fresh = ByteArray(SEED_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
    seedFile.parentFile?.mkdirs()
    seedFile.writeBytes(fresh)
    println("No seed file found at ${seedFile.path} -- generated a fresh one. This node's announced identity is now stable across restarts as long as this file is preserved.")
    return fresh
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

/** 32 bytes -- see `RendezvousCli`'s identical constant doc for why this size has no relationship to [NodeId.SIZE_BYTES] beyond convenience. */
private const val SEED_SIZE_BYTES = 32

/**
 * See [startReannounceLoop]'s own doc for why this is derived from
 * [RelayDirectory.DEFAULT_ENTRY_TTL_MS], not an independently invented
 * number. `internal`, not `private`, so [RelayNodeCliReachabilityTest] can
 * assert this default against [RelayDirectory]'s own real constant directly,
 * rather than a duplicated literal that could silently drift from it.
 */
internal const val DEFAULT_REANNOUNCE_INTERVAL_MS = RelayDirectory.DEFAULT_ENTRY_TTL_MS / 3

/** Parsed, validated CLI options for [main]. */
private data class CliOptions(
    val port: Int,
    val rendezvous: Pair<String, Int>,
    val advertiseHost: String,
    val advertisePort: Int,
    val seedFilePath: String,
    val announceUdpPort: Int,
    val maxConcurrentSlots: Int,
    val waitTimeoutMs: Long,
    val reannounceIntervalMs: Long,
) {
    companion object {
        const val DEFAULT_SEED_FILE_NAME = "relay-node-seed.bin"

        fun parse(args: Array<String>): CliOptions {
            val map = mutableMapOf<String, String>()
            var i = 0
            while (i < args.size) {
                val key = args[i]
                require(key.startsWith("--")) { "Expected a --flag, got: $key" }
                require(i + 1 < args.size) { "Missing value for $key" }
                map[key.removePrefix("--")] = args[i + 1]
                i += 2
            }

            fun required(name: String): String =
                map[name] ?: throw IllegalArgumentException("Missing required argument: --$name")

            val port = required("port").toInt()

            val rendezvousArg = required("rendezvous")
            val (rendezvousHost, rendezvousPortStr) = rendezvousArg.split(":", limit = 2).let {
                require(it.size == 2) { "--rendezvous must be host:port, got: $rendezvousArg" }
                it[0] to it[1]
            }

            return CliOptions(
                port = port,
                rendezvous = rendezvousHost to rendezvousPortStr.toInt(),
                advertiseHost = required("advertise-host"),
                advertisePort = map["advertise-port"]?.toInt() ?: port,
                seedFilePath = map["seed-file"] ?: DEFAULT_SEED_FILE_NAME,
                announceUdpPort = map["announce-udp-port"]?.toInt() ?: 0,
                maxConcurrentSlots = map["max-concurrent-slots"]?.toInt() ?: RelayNode.DEFAULT_MAX_CONCURRENT_SLOTS,
                waitTimeoutMs = map["wait-timeout-ms"]?.toLong() ?: RelayNode.DEFAULT_WAIT_TIMEOUT_MS,
                reannounceIntervalMs = map["reannounce-interval-ms"]?.toLong() ?: DEFAULT_REANNOUNCE_INTERVAL_MS,
            )
        }
    }
}
