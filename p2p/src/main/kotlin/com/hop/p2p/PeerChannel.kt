package com.hop.p2p

import com.hop.protocol.WireEnvelope
import com.hop.protocol.WireEnvelopeDecodeException
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sends/receives [WireEnvelope]-framed bytes over an already-connected
 * [Socket] — the "exchange bytes" half of this module, paired with
 * [PeerDialer]'s "get connected" half. See [PeerDialer]'s class doc for the
 * full reasoning behind why both live in this small `:dht`/`:protocol`
 * bridge module rather than either of those two modules or the Android app.
 *
 * Deliberately dumb about *what* [WireEnvelope.payload] actually contains —
 * a `POST_FRAME`, a `TIER_KEY_REQUEST`, whatever — matching
 * `com.hop.transport.WifiDirectTransport`'s own socket read/write loop's
 * "opaque payload, dispatch is someone else's job" posture. Wiring a
 * received envelope into `mobile/android/`'s actual `EnvelopeDispatcher`/
 * `TransportManager`/repositories is explicit follow-on, Android-app-layer
 * work — not this class's job (same incremental-slice precedent as
 * `PreKeyBundleEnvelope`'s wire format shipping before its own transport
 * wiring did, in this same codebase's history).
 *
 * Not thread-safe for concurrent [sendEnvelope] calls, or concurrent
 * [receiveEnvelope] calls, from more than one thread each — matches
 * `WifiDirectTransport`'s own inner `PeerConnection` (a `writeLock`-guarded
 * single writer) posture. A caller needing concurrent multi-writer access
 * should add its own lock around [sendEnvelope], same as that class does,
 * rather than this low-level primitive imposing one unconditionally. A
 * single reader loop per [PeerChannel] (one dedicated thread calling
 * [receiveEnvelope] in a loop until it throws) is the expected usage shape,
 * again mirroring `WifiDirectTransport.receivePosts`.
 *
 * [receiveEnvelope] rejects a declared payload length over [MAX_PAYLOAD_BYTES]
 * before allocating anything for it — unlike a local WiFi Direct group member,
 * a peer reached over the open internet (this module's whole reason to exist)
 * is not vetted by any physical-proximity/group-membership fact, so a 5-byte
 * header claiming a multi-gigabyte payload is a cheap resource-exhaustion
 * attempt this side must refuse before it costs anything, not "out of scope
 * for a foundational slice."
 */
class PeerChannel(private val socket: Socket) : Closeable {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())

    /** Writes one [WireEnvelope], self-framed per [WireEnvelope.encode] — no additional outer length prefix. */
    fun sendEnvelope(envelope: WireEnvelope) {
        output.write(envelope.encode())
        output.flush()
    }

    /**
     * Blocks until one full [WireEnvelope] has arrived, then decodes it via
     * [WireEnvelope.decode] — reads exactly [WireEnvelope.HEADER_SIZE]
     * header bytes first (enough to know the declared payload length per
     * [WireEnvelope.encode]'s own `[type][length][payload]` layout), then
     * exactly that many more payload bytes, then hands the concatenated
     * header+payload back to [WireEnvelope.decode] rather than re-deriving
     * that class's own type/length parsing a second time here.
     *
     * Deliberately simpler than `WifiDirectTransport.receivePosts`'s own
     * manual field-by-field read (that class trades an extra header+payload
     * array copy for one fewer decode-time re-parse of a field it already
     * has in hand) — this primitive isn't on any per-frame-at-scale hot path
     * yet (this slice is loopback-tested foundation code, not wired into a
     * live mesh), so the simpler, single-source-of-truth-for-framing version
     * is the better trade here. A future performance pass touching this
     * class is free to switch to that other approach if profiling ever
     * justifies it.
     *
     * Throws [java.io.EOFException] if the peer closes the connection before
     * a complete envelope arrives (including mid-header) — callers reading
     * in a loop should treat that as "peer is done sending," matching
     * `WifiDirectTransport.receivePosts`'s own EOF handling. Throws
     * [WireEnvelopeDecodeException] on an unrecognized type byte, a negative
     * declared length, or a declared length over [MAX_PAYLOAD_BYTES] — the
     * last of these checked, and rejected, before [payload] is ever
     * allocated, precisely so a peer can't force a huge allocation merely by
     * lying in the 5-byte header before any payload bytes have arrived.
     */
    fun receiveEnvelope(): WireEnvelope {
        val header = ByteArray(WireEnvelope.HEADER_SIZE)
        input.readFully(header)
        val declaredLength = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(1)
        if (declaredLength < 0) {
            throw WireEnvelopeDecodeException("Invalid declared payload length: $declaredLength")
        }
        if (declaredLength > MAX_PAYLOAD_BYTES) {
            throw WireEnvelopeDecodeException(
                "Declared payload length=$declaredLength exceeds MAX_PAYLOAD_BYTES=$MAX_PAYLOAD_BYTES " +
                    "-- refusing to allocate for it"
            )
        }
        val payload = ByteArray(declaredLength)
        input.readFully(payload)
        return WireEnvelope.decode(header + payload)
    }

    override fun close() {
        socket.close()
    }

    companion object {
        /**
         * Upper bound on a single [WireEnvelope]'s declared payload size this
         * side will allocate for, checked in [receiveEnvelope] before any
         * allocation happens. Unmeasured placeholder (same posture as
         * `DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS`/`PeerDialer`'s own
         * constants) — comfortably above any realistic compressed photo/short
         * video clip or message ciphertext this app produces (PRD's stated
         * short-clip-length ceiling), far below what would meaningfully
         * threaten this process's memory on its own from a single envelope.
         * Revisit if real clip-size data ever justifies a different number.
         */
        const val MAX_PAYLOAD_BYTES: Int = 64 * 1024 * 1024
    }
}
