package com.hop.preseed

import com.hop.crypto.DecayKeyStore
import com.hop.protocol.ContentType
import com.hop.protocol.ReachTier
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking

/**
 * The operator-facing CLI entry point for [PreseedNode] -- "the tooling to
 * push a starter batch of clips into a new venue/city ahead of local user
 * arrival" (BUILD_PLAN.md Phase 4). Run via `./gradlew :preseed:run --args=...`
 * (see `tools/preseed/build.gradle.kts`'s `run` task) or as a plain `java -jar`
 * once assembled -- this is a standalone process an operator runs on their
 * own infrastructure, never something HOP hosts on a user's behalf (see
 * [PreseedNode]'s own class doc).
 *
 * ## Manifest format: explicit content type, never inferred
 *
 * `--manifest` points at a plain text file, one clip per line:
 * `<PHOTO|VIDEO><TAB><path to source media file>`. Content type is an
 * explicit, required field of every manifest line -- **deliberately not
 * inferred from the file's extension or sniffed from its bytes** (see
 * [ClipPackager]'s own doc for why: this codebase's wire format treats
 * photo-vs-video as an explicit flag everywhere else, and the real app's
 * posting path gets this fact from `ContentResolver`, a framework source
 * this plain-JVM tool has no equivalent of). An operator populating a
 * manifest is standing in for that missing framework fact by hand -- a
 * deliberate, explicit step, not an inconvenience to optimize away by
 * guessing.
 *
 * ## Required arguments
 *
 * - `--manifest <path>`: the manifest file described above.
 * - `--lat <double>` / `--lon <double>`: the target venue/city's coordinates
 *   every clip in this batch is seeded at. (One coordinate pair per CLI
 *   invocation -- seeding multiple distinct locations in one run is out of
 *   scope for this slice; run this tool once per target location.)
 * - `--tier <TOWN|CITY|COUNTRY>`: the reach tier every clip in this batch is
 *   seeded at. **`LOCALITY` is refused** -- see [ClipPackager]'s own doc for
 *   why it's structurally impossible to pre-seed over the internet at all
 *   (ADR 0003).
 * - `--rendezvous <host:port>`: the bootstrap/rendezvous node address (ADR
 *   0002) this tool joins the DHT through. Required, unlike
 *   [com.hop.app.dht.DhtNodeManager]'s own optional dev-only bootstrap
 *   address -- this tool's whole purpose depends on being reachable from
 *   real peers, so it always needs a real join path.
 *
 * ## Optional arguments
 *
 * - `--ttl-seconds <long>`: decay window for every clip in this batch
 *   (default [DEFAULT_TTL_SECONDS]). **A genuine design decision, stated
 *   here rather than silently defaulted:** the real app's own Phase 1
 *   placeholder is a flat 24 hours
 *   (`PostComposerViewModel.PLACEHOLDER_DECAY_WINDOW_SECONDS`) for every
 *   post regardless of origin. Pre-seeded content's whole premise --
 *   pushing clips into a venue/city *ahead of* local user arrival (memo §8
 *   Phase 2 GTM) -- means a 24-hour window can plausibly expire before any
 *   real user ever sees the seeded content at all, defeating the point of
 *   seeding it. This tool's own default is therefore longer
 *   ([DEFAULT_TTL_SECONDS], 7 days) to give a launch-lead-time window room
 *   to matter -- but it is still a bounded, CLI-overridable, ordinary decay
 *   window, governed by the exact same [DecayKeyStore]/
 *   [com.hop.protocol.ReachTierKeyDistribution] enforcement path as any
 *   other post, never a special-cased "seeded content never decays"
 *   exemption (see [PreseedNode]'s own "Decay is real" doc section --
 *   weakening or bypassing that would be a real ADR 0003 violation, not a
 *   shortcut, per this task's own instruction).
 * - `--bind-port <int>`: UDP/TCP bind port (default `0`, ephemeral).
 * - `--sender-device-id-hex <32 hex chars>`: this run's nominal 16-byte
 *   sender device id (default: freshly random). Not a persistent identity --
 *   see [PreseedNode]'s own "Device identity" doc.
 *
 * ## What this does at startup
 *
 * 1. Parses arguments and the manifest.
 * 2. Reads each source file's plaintext bytes and packages it via
 *    [ClipPackager.packageClip] against a single shared [DecayKeyStore].
 * 3. Constructs and starts a [PreseedNode] with the resulting [SeedClip]
 *    list, bootstrapping through `--rendezvous`.
 * 4. Calls [PreseedNode.publishSeededContent] to announce presence for
 *    every seeded clip's target cell.
 * 5. Blocks (a shutdown hook calls [PreseedNode.stop] on Ctrl-C/SIGTERM)
 *    until interrupted, so the process stays up to actually serve content
 *    to whichever real peers later dial in.
 */
fun main(args: Array<String>) {
    val options = CliOptions.parse(args)

    val manifestFile = File(options.manifestPath)
    require(manifestFile.isFile) { "Manifest file not found: ${options.manifestPath}" }

    val entries = manifestFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map(::parseManifestLine)
    require(entries.isNotEmpty()) { "Manifest at ${options.manifestPath} contained no clip entries" }

    val decayKeyStore = DecayKeyStore()
    val senderDeviceId = options.senderDeviceIdHex
        ?.let(::hexToByteArray)
        ?: ByteArray(SENDER_DEVICE_ID_SIZE).also { SecureRandom().nextBytes(it) }

    println("Packaging ${entries.size} clip(s) for reachTier=${options.tier} at (${options.latitude}, ${options.longitude})...")
    val seededClips = entries.map { (contentType, file) ->
        println("  packaging ${file.path} as $contentType")
        ClipPackager.packageClip(
            plaintext = file.readBytes(),
            contentType = contentType,
            reachTier = options.tier,
            latitude = options.latitude,
            longitude = options.longitude,
            ttlSeconds = options.ttlSeconds,
            senderDeviceId = senderDeviceId,
            decayKeyStore = decayKeyStore,
        )
    }

    val ownNodeIdSeed = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val node = PreseedNode(
        ownNodeIdSeed = ownNodeIdSeed,
        bootstrapHost = options.rendezvousHost,
        bootstrapPort = options.rendezvousPort,
        seededClips = seededClips,
        decayKeyStore = decayKeyStore,
        bindPort = options.bindPort,
        onLog = { message -> println("[preseed] $message") },
    )

    val shutdownLatch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread({
            println("Shutting down...")
            node.stop()
            shutdownLatch.countDown()
        }, "hop-preseed-shutdown"),
    )

    runBlocking {
        node.start()
        println("Started -- ownNodeId=${node.ownNodeId}, ownAddress=${node.ownAddress}")
        node.publishSeededContent()
        println("Published presence for ${seededClips.size} seeded clip(s). Serving until interrupted (Ctrl-C).")
    }

    shutdownLatch.await()
}

/** One parsed manifest line: an explicit [ContentType] plus its source [File]. */
private fun parseManifestLine(line: String): Pair<ContentType, File> {
    val parts = line.split('\t', limit = 2)
    require(parts.size == 2) {
        "Malformed manifest line (expected \"<PHOTO|VIDEO><TAB><path>\"): \"$line\""
    }
    val contentType = ContentType.valueOf(parts[0].trim().uppercase())
    val file = File(parts[1].trim())
    require(file.isFile) { "Manifest references a source file that does not exist: ${file.path}" }
    return contentType to file
}

private fun hexToByteArray(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Hex string must have an even length: $hex" }
    return ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
}

/** 16 bytes, matching [com.hop.protocol.Frame.SENDER_DEVICE_ID_SIZE] -- duplicated as a literal here rather than importing `Frame` just for this one constant, matching this codebase's other "wire-format constant vs. policy constant" separations. */
private const val SENDER_DEVICE_ID_SIZE = 16

/** Parsed, validated CLI options for [main]. */
private data class CliOptions(
    val manifestPath: String,
    val latitude: Double,
    val longitude: Double,
    val tier: ReachTier,
    val rendezvousHost: String,
    val rendezvousPort: Int,
    val ttlSeconds: Long,
    val bindPort: Int,
    val senderDeviceIdHex: String?,
) {
    companion object {
        /**
         * See [main]'s own "Optional arguments" doc for the reasoning behind
         * this default (7 days) differing from the real app's own 24-hour
         * Phase 1 placeholder.
         */
        const val DEFAULT_TTL_SECONDS: Long = 7L * 24 * 60 * 60

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

            val tierArg = required("tier").uppercase()
            val tier = ReachTier.valueOf(tierArg)
            require(tier != ReachTier.LOCALITY) {
                "--tier LOCALITY is structurally impossible to pre-seed over the internet -- ADR 0003 " +
                    "(Locality resolves entirely offline via BLE/WiFi Direct). Use TOWN, CITY, or COUNTRY."
            }

            val rendezvous = required("rendezvous")
            val (rendezvousHost, rendezvousPortStr) = rendezvous.split(":", limit = 2).let {
                require(it.size == 2) { "--rendezvous must be host:port, got: $rendezvous" }
                it[0] to it[1]
            }

            return CliOptions(
                manifestPath = required("manifest"),
                latitude = required("lat").toDouble(),
                longitude = required("lon").toDouble(),
                tier = tier,
                rendezvousHost = rendezvousHost,
                rendezvousPort = rendezvousPortStr.toInt(),
                ttlSeconds = map["ttl-seconds"]?.toLong() ?: DEFAULT_TTL_SECONDS,
                bindPort = map["bind-port"]?.toInt() ?: 0,
                senderDeviceIdHex = map["sender-device-id-hex"],
            )
        }
    }
}
