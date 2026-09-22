package com.hop.preseed

import com.hop.crypto.DecayKeyStore
import com.hop.p2p.PeerChannel
import com.hop.protocol.ReachTierKeyDistribution
import com.hop.protocol.TierKeyRequestEnvelope
import com.hop.protocol.TierKeyResponseEnvelope
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import java.io.EOFException

/**
 * The connect-time-backlog-offer + tier-key-request-answering half of this
 * tool -- the "content-serving sibling of
 * [com.hop.app.dht.DhtNodeManager]/`InternetPeerConnectionManager`" the task
 * spec describes, deliberately much narrower than the real app's
 * `InternetPeerConnectionManager`/`EnvelopeDispatcher` pair: this class
 * offers pre-seeded content and answers [TierKeyRequestEnvelope]s, full
 * stop. It does not take relay custody of anything a connecting peer sends
 * it, does not fan content out to other connections, does not persist
 * "don't relay" flags, and does not participate in messaging -- this is a
 * content *source*, not a relay node or a full mesh participant. See
 * [receiveLoop]'s own doc for exactly what's ignored and why that's a
 * deliberate scope boundary, not an oversight.
 *
 * **Connect-time backlog offer, reused shape.** [acceptInbound] mirrors
 * `InternetPeerConnectionManager.acceptInbound`'s own two-thread shape
 * (`"hop-preseed-send"` offering the backlog, `"hop-preseed-receive"`
 * reading whatever the peer sends back) -- see that class's own doc for why
 * a dedicated send thread matters (so offering a backlog to one peer can
 * never block accepting/serving another). Unlike that class, there is no
 * Room-backed repository to build a backlog from -- [seededClips] is a
 * plain in-memory list, built once at startup by [PreseedCli] from
 * [ClipPackager] output, per this task's own instruction that this tool
 * "has no Android repositories to draw from."
 *
 * **Tier-key-request answering, reused decision logic.** [handleTierKeyRequest]
 * mirrors `EnvelopeDispatcher.dispatch`'s own `TIER_KEY_REQUEST` branch (see
 * that function's own doc in `mobile/android/app/`) rather than
 * reimplementing the policy: not held -> denied, wrong tier -> denied, no
 * origin cell recorded -> denied (never reachable here in practice, since
 * [ClipPackager] always derives one from real coordinates, but kept for the
 * same defensive reason the real app keeps it), otherwise
 * [ReachTierKeyDistribution.releaseKeyFor] decides. **No attestation check
 * anywhere in this path** -- see [PreseedNode]'s own class doc for why that's
 * a documented, pre-existing protocol fact, not something this tool works
 * around.
 */
class PreseedContentServer(
    private val seededClips: List<SeedClip>,
    private val decayKeyStore: DecayKeyStore,
    private val onLog: (String) -> Unit = {},
) {
    private val byContentId: Map<String, SeedClip> = seededClips.associateBy { it.contentId }

    /**
     * Adopts an already-accepted, already-connected [channel] -- handed here
     * by [com.hop.p2p.PeerListener]'s own `onConnected` callback (see
     * [PreseedNode]'s own wiring) -- into this tool's content-serving world.
     * Two things happen, each on its own dedicated thread so neither can
     * block the other or the shared accept loop: [offerBacklog] offers every
     * seeded clip once, and [receiveLoop] starts reading whatever the peer
     * sends back (in practice: [TierKeyRequestEnvelope]s).
     */
    fun acceptInbound(channel: PeerChannel) {
        Thread({ offerBacklog(channel) }, "hop-preseed-send").start()
        Thread({ receiveLoop(channel) }, "hop-preseed-receive").start()
    }

    /**
     * Offers every entry in [seededClips] to [channel], unconditionally and
     * once, each already wrapped as a [WirePayloadType.POST_FRAME]
     * [WireEnvelope] -- the connect-time backlog offer this whole class
     * exists for. A send failure partway through (peer closes mid-stream) is
     * logged and stops this offer; it never throws out of this method,
     * mirroring `InternetPeerConnectionManager.sendBacklog`'s own posture.
     */
    private fun offerBacklog(channel: PeerChannel) {
        onLog("Offering ${seededClips.size} seeded clip(s) to a newly connected peer")
        for (clip in seededClips) {
            try {
                channel.sendRawBytes(WireEnvelope.encode(WirePayloadType.POST_FRAME, clip.encodedFrame))
            } catch (e: Exception) {
                onLog("Failed to offer the seeded backlog to a connected peer: ${e.message}")
                return
            }
        }
    }

    /**
     * Reads [WireEnvelope]s off [channel] in a loop until it throws (peer
     * closed, or another I/O error), same EOF/exception handling posture as
     * `InternetPeerConnection.receiveLoop`. Only [WirePayloadType.TIER_KEY_REQUEST]
     * gets real handling ([handleTierKeyRequest]); every other envelope type
     * is logged and otherwise ignored -- **deliberately, not a gap**: this
     * tool never takes custody of a post/message/bundle/"don't relay" flag a
     * peer sends it, never fans anything out, and has no messaging support
     * at all. A real client dialing this tool has no reason to send anything
     * but a tier-key request in the first place (this tool never announces
     * itself as accepting posts *from* peers, only as a holder to browse),
     * so this is a defensive backstop, not an expected hot path.
     */
    private fun receiveLoop(channel: PeerChannel) {
        while (true) {
            val envelope = try {
                channel.receiveEnvelope()
            } catch (e: EOFException) {
                onLog("Peer connection closed by remote; ending receive loop")
                return
            } catch (e: Exception) {
                onLog("Peer connection receive error: ${e.message}; ending receive loop")
                return
            }
            try {
                when (envelope.type) {
                    WirePayloadType.TIER_KEY_REQUEST -> handleTierKeyRequest(envelope.payload, channel)
                    else -> onLog(
                        "Ignoring a ${envelope.type} envelope -- this tool only offers pre-seeded content and " +
                            "answers tier-key requests, it is not a full relay node (no custody-taking, no " +
                            "fanout, no messaging support)"
                    )
                }
            } catch (e: Exception) {
                onLog("Error handling a received envelope: ${e.message}")
            }
        }
    }

    /**
     * Mirrors `EnvelopeDispatcher.dispatch`'s own `TIER_KEY_REQUEST` branch
     * (see that function's own doc) -- decode failures are deliberately left
     * to propagate to [receiveLoop]'s own catch block, same as that real
     * dispatcher; what must never happen is a *decoded* request going
     * unanswered, so every path below produces a response, granted or
     * denied, never a throw once decoding succeeds.
     */
    private fun handleTierKeyRequest(payload: ByteArray, channel: PeerChannel) {
        val request = TierKeyRequestEnvelope.decode(payload)
        val contentIdHex = request.contentId.toHexString()
        val clip = byContentId[contentIdHex]

        val response = when {
            clip == null -> TierKeyResponseEnvelope.denied(request.contentId)
            clip.reachTier != request.claim.reachTier -> TierKeyResponseEnvelope.denied(request.contentId)
            clip.originGeohashPrefix.isEmpty() -> TierKeyResponseEnvelope.denied(request.contentId)
            else -> {
                val wrappedCek = ReachTierKeyDistribution.releaseKeyFor(
                    claim = request.claim,
                    contentId = contentIdHex,
                    originGeohashPrefix = clip.originGeohashPrefix,
                    decayKeyStore = decayKeyStore,
                )
                if (wrappedCek != null) {
                    TierKeyResponseEnvelope.granted(request.contentId, wrappedCek)
                } else {
                    TierKeyResponseEnvelope.denied(request.contentId)
                }
            }
        }

        try {
            channel.sendEnvelope(WireEnvelope(WirePayloadType.TIER_KEY_RESPONSE, response.encode()))
        } catch (e: Exception) {
            onLog("Failed to send a tier-key response back to a connected peer: ${e.message}")
        }
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
}
