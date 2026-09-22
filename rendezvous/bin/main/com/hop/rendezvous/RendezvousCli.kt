package com.hop.rendezvous

import com.hop.dht.NodeId
import java.io.File
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch

/**
 * The operator-facing CLI entry point for [RendezvousNode] -- ADR 0002's
 * bootstrap/rendezvous node, made a runnable standalone process
 * (`docs/adr/0002-bootstrap-node-carveout.md`).
 *
 * ## Who runs this
 *
 * **A volunteer / third-party operator -- explicitly NOT HOP itself.** This
 * repo's own non-negotiable constraint is "no HOP-owned server ever sits in
 * the content, discovery, or message-delivery path," and ADR 0002's whole
 * carve-out for a bootstrap/rendezvous node depends on that node not being
 * HOP-operated -- see `rendezvous/README.md` for the full explanation. This
 * `main()` exists so there is finally something concrete to hand a real
 * third-party operator; running it yourself as "the HOP bootstrap node" would
 * defeat the point of building it.
 *
 * ## What this process is
 *
 * [RendezvousNode] itself is address-only by construction -- see that
 * class's own doc for exactly why it is structurally incapable of answering
 * a content-hash or topic query (it never wires
 * [com.hop.dht.DhtUdpTransport.onStoreRequested]/`onFindValueRequested`).
 * This CLI adds nothing to that surface: it only binds a socket, derives a
 * stable [NodeId], constructs [RendezvousNode], and starts it.
 *
 * ## Stable identity across restarts
 *
 * A rendezvous node's whole value is being a well-known, reliably-dialable
 * address -- every peer that ever bootstrapped through it or received its
 * [com.hop.dht.Contact] via peer exchange caches that contact under its
 * [NodeId]. A fresh random id on every process restart would silently break
 * every one of those cached entries (a PING to the old id would now hit
 * nobody, or worse, an entirely different node that later happens to bind
 * the same id). [DhtNodeManager][com.hop.app.dht.DhtNodeManager] (the
 * Android app's own equivalent) solves this by deriving its [NodeId] from a
 * seed persisted in per-install `DataStore`; this standalone JVM process has
 * no such framework storage, so [main] reads/generates an equivalent flat
 * seed file instead (see [loadOrCreateNodeIdSeed]) -- same
 * [NodeId.fromKeyMaterial] derivation, same "one stable identity for the
 * life of this deployment" property, just backed by a file instead of
 * `DataStore`.
 *
 * ## Required arguments
 *
 * - `--port <int>`: the UDP port this node binds to and advertises as its
 *   address. Required, unlike [com.hop.preseed.PreseedCli]'s own optional
 *   `--bind-port` (default ephemeral `0`) -- a rendezvous node's whole
 *   purpose is being dialable at a KNOWN, stable address an operator has
 *   told others about, so an ephemeral port an operator would have to
 *   re-discover after every restart defeats that purpose.
 *
 * ## Optional arguments
 *
 * - `--seed-file <path>`: where this node's identity seed is persisted
 *   (default [DEFAULT_SEED_FILE_NAME] in the current working directory). If
 *   the file doesn't exist, a fresh random seed is generated and written
 *   there before this node starts -- every subsequent restart against the
 *   same file reuses the identical seed, and therefore the identical
 *   [NodeId].
 * - `--response-cap <int>`: forwarded to [RendezvousNode]'s own
 *   `responseCap` (default [RendezvousNode.DEFAULT_RESPONSE_CAP]).
 *
 * ## What this does at startup
 *
 * 1. Parses arguments.
 * 2. Loads or creates this node's identity seed file, derives its [NodeId].
 * 3. Binds a [DatagramSocket] on `--port`.
 * 4. Constructs and starts a [RendezvousNode].
 * 5. Logs its bound address and [NodeId] so an operator can confirm it's
 *    alive and hand the address to real clients/other operators.
 * 6. Blocks (a shutdown hook calls [RendezvousNode.stop] on Ctrl-C/SIGTERM)
 *    until interrupted.
 */
fun main(args: Array<String>) {
    val options = CliOptions.parse(args)

    val seedFile = File(options.seedFilePath)
    val seed = loadOrCreateNodeIdSeed(seedFile)
    val ownId = NodeId.fromKeyMaterial(seed)

    val socket = DatagramSocket(InetSocketAddress(options.port))
    val node = RendezvousNode(socket, ownId, responseCap = options.responseCap)

    val shutdownLatch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread({
            println("Shutting down...")
            node.stop()
            shutdownLatch.countDown()
        }, "hop-rendezvous-shutdown"),
    )

    node.start()
    println("Started -- ownNodeId=${ownId.bytes.toHexString()}, bound on UDP port ${socket.localPort}")
    println("Serving until interrupted (Ctrl-C). This node answers address-only PING/FIND_NODE/INTRODUCE/RELAY_ANNOUNCE/RELAY_QUERY -- see RendezvousNode's class doc.")

    shutdownLatch.await()
}

/**
 * Reads a 32-byte identity seed from [seedFile] if it already exists, or
 * generates a fresh random one and writes it there first. Deliberately NOT
 * regenerated on every call -- see [main]'s "Stable identity across
 * restarts" doc for why a stable seed file, not a fresh random seed per
 * process start, is load-bearing here.
 *
 * `internal`, not `private`, so [RendezvousCliReachabilityTest] can construct
 * a [RendezvousNode] via this exact same seed-loading step [main] itself
 * uses, rather than a parallel test-only reimplementation of it.
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
    println("No seed file found at ${seedFile.path} -- generated a fresh one. This node's identity is now stable across restarts as long as this file is preserved.")
    return fresh
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

/** 32 bytes: no particular relationship to [NodeId.SIZE_BYTES] other than convenience -- [NodeId.fromKeyMaterial] hashes arbitrary-length key material, so this is just a comfortably-large source of entropy, not a wire-format constant. */
private const val SEED_SIZE_BYTES = 32

/** Parsed, validated CLI options for [main]. */
private data class CliOptions(
    val port: Int,
    val seedFilePath: String,
    val responseCap: Int,
) {
    companion object {
        const val DEFAULT_SEED_FILE_NAME = "rendezvous-node-seed.bin"

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

            val port = map["port"]?.toInt()
                ?: throw IllegalArgumentException("Missing required argument: --port")

            return CliOptions(
                port = port,
                seedFilePath = map["seed-file"] ?: DEFAULT_SEED_FILE_NAME,
                responseCap = map["response-cap"]?.toInt() ?: RendezvousNode.DEFAULT_RESPONSE_CAP,
            )
        }
    }
}
