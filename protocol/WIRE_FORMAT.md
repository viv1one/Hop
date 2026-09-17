# HOP Wire Format

Status: **`Frame` version 3, current; socket wire envelope version 1,
current.** This is BUILD_PLAN.md's "open decision #4" output for Phase 0,
extended by Phase 1's encrypted-content-carrying work and, later in Phase 1,
by the 1:1 encrypted-messaging slice's socket envelope — a versioned frame
format every device on the mesh (and every independent relay-node
implementation, once those exist in Phase 2+) must agree on to interoperate.

These are two independently versioned things, on purpose (see "Socket-level
wire envelope" below for why): `Frame` is the WiFi Direct **transfer frame**
(the thing that goes *inside* the envelope for a post); the **wire envelope**
is the outermost socket-level framing that wraps every blob sent over a WiFi
Direct transfer socket, `Frame`-carrying or not. A `Frame` version bump does
not imply an envelope version bump, and vice versa.

Fields, byte layout, and encoding may still change before Phase 2+ relay and
decay logic locks this down for real. That is expected. What is **not**
allowed is a silent, unversioned change: any change to field layout, field
set, or encoding rules MUST bump `version`. A decoder that receives a frame
with a `version` it does not understand MUST reject it (throw / return an
error), never guess at the layout. Backward-compatible parsing across
versions is a later concern once more than one version exists in the field
concurrently; for now, version 3 is the only defined version and there is no
compatibility shim — versions 0, 1, and 2 are superseded and are rejected on
decode, not silently upgraded.

**Version 1 superseded version 0** to add a `contentType` field
distinguishing photo posts from video posts, per BUILD_PLAN.md open decision
#4's updated wording ("a versioned wire format from day one... **content-type
flag distinguishing photo from video**...") and PRD §4.1/§8, which added
photo as a first-class v1 post type alongside video.

**Version 2 supersedes version 1** to carry encrypted content, per
BUILD_PLAN.md's Phase 1 line: "Content is encrypted at rest with a key on the
decay/tier schedule from ADR 0003... get the key-wrapping shape right now so
it doesn't need a breaking wire-format change later." This added two new
fields (`keyIncluded`, `contentEncryptionKey`) and changed `payload`'s
semantics from plaintext to ciphertext — both a field-set change and a
semantic change to an existing field, hence the version bump.

**Version 3 supersedes version 2** to add `originGeohashPrefix` and to make
`keyIncluded`/`contentEncryptionKey` do real work for the first time: Phase 4
Slice 9 turns Town/City/Country's `keyIncluded = false` path (built as
forward-compatible shape in version 2, but never actually exercised — Phase
1-3 traffic was Locality-only) into the live path it was always meant to be.
A non-Locality poster's device now needs a way to tell a later holder of the
post *which cell* to check an inbound tier-membership claim against, without
ever putting raw origin latitude/longitude on the wire (see
`com.hop.app.location.LocationProvider`'s "raw lat/lon never hits the wire"
invariant) — `originGeohashPrefix` (the origin cell's geohash prefix string,
at the post's own `reachTier`'s precision) is that field. This is a new
field with a variable-length encoding (see below), hence the version bump.
Rejected outright, not silently supported alongside version 3, per this
document's own "no compatibility shim" policy above — the same treatment
version 2 gave versions 0 and 1.

## Scope: what this frame is (and isn't)

This is the **WiFi Direct transfer frame** — the payload that goes over the
socket once two peers have already connected via WiFi Direct. It is not a
BLE payload.

BLE in HOP is **discovery-only**: it advertises presence (a service UUID) so
nearby peers can find each other, and it never carries clip content or this
frame. Two reasons, both intentional:

1. BLE advertisement payloads are far too small to carry a clip hash, header
   metadata, and a clip (PRD §6).
2. Keeping BLE strictly to discovery keeps its background-scanning /
   advertising battery cost bounded and independent of transfer size, which
   matters for the NFR in PRD §7 around discovery-scanning battery drain.

Once discovery hands off to a WiFi Direct connection, the two peers exchange
one or more `Frame`s (this spec) over that socket to move a clip (and, in
later phases, relay metadata for a clip neither peer originated).

## Design notes

- All multi-byte integers are **big-endian** (network byte order).
- The frame has a header of at least 103 bytes (every fixed-size field plus
  the empty-`originGeohashPrefix` case), up to 5 bytes larger for a
  non-empty `originGeohashPrefix`, followed by a variable-length payload —
  see "`originGeohashPrefix` (Phase 4 Slice 9)" below for why this field
  specifically isn't fixed-size like the rest of the header.
- `clipHash` is a content-addressed identifier (SHA-256 of the **plaintext**
  clip/photo payload — see the dedicated section below), not a server-issued
  ID — there is no server to issue one.
- `senderDeviceId` is a random, ephemeral, per-install identifier. It is
  **not** an account or persistent cross-device identity — per HOP's
  no-accounts constraint, a device may (and eventually should) regenerate
  this. It exists so relay/decay logic can reason about "who sent this hop,"
  not "who is this person."
- `contentType` distinguishes a photo post from a video post. It is an
  explicit wire field, set by the sender at encode time — never inferred by
  a receiver from file extension or payload sniffing (BUILD_PLAN.md decision
  #4, PRD §4.1).
- `hopCount`, `originatedAtMs`, `ttlSeconds`, `reachTier`, and `dontRelay`
  are decay/propagation-control metadata. This frame only carries them —
  the decay math and "don't relay" suppression logic that *act* on these
  fields land in Phase 1/2 relay code. Carrying the fields from Phase 0
  (rather than adding them once relay logic exists) is deliberate: per
  CLAUDE.md and BUILD_PLAN.md, decay and "don't relay" are protocol-layer
  primitives designed in from the start, not retrofitted later.
- `reachTier` mirrors the four PRD §4.2 reach tiers (Locality → Town → City
  → Country). At Locality tier this frame travels over a direct WiFi Direct
  hop between physically nearby devices; higher tiers are what `reachTier`
  will help gate once DHT/internet-mode (Phase 4) and multi-hop relay
  (Phase 2) exist. This field does not itself implement tier resolution —
  that's geohash-prefix logic that lives elsewhere in `protocol/`.
- `ttlSeconds` doubles as the decay window duration: it's fed directly to
  `DecayKeyStore.store()`'s `decayWindow` parameter at receive time (see
  `EncryptedFrameCodec.decode()`). No separate decay-window field was added
  for this — `ttlSeconds` already means "how long from `originatedAtMs`
  this content stays valid," which is exactly what the decay window is.

## Encryption: `payload` is ciphertext, `clipHash` is over plaintext

Starting at version 2, `payload` carries **ciphertext**, not plaintext —
specifically the exact blob `ContentEncryption.encrypt()` produces:
`iv (12 bytes) || ciphertext-with-appended-GCM-tag`. `payloadLength`'s
meaning (length of what follows) is unchanged; only what's inside changed.

`clipHash` is computed over the **plaintext** content, never the ciphertext.
This is deliberate, not an oversight: `clipHash` is HOP's content-addressing
/ dedup identifier — two people who independently re-share the same photo or
clip, each encrypting it under their own freshly generated random CEK, must
still produce the *same* `clipHash` so the network can recognize it as the
same content. If `clipHash` covered ciphertext instead, every independent
encryption of identical plaintext would produce a different hash and dedup
would break. Ciphertext tamper-detection is already covered by AES-GCM's own
authentication tag (see `ContentEncryption`), so there's no integrity reason
for `clipHash` to cover ciphertext.

### `keyIncluded` and `contentEncryptionKey`

Per ADR 0003, reach-tier limits above Locality are enforced by per-tier
key-wrapping, not by topic-key secrecy (geohash prefixes aren't secret). At
Locality tier — the only tier that never touches the DHT — "the ciphertext
and key both stay on local mesh only," so the content-encryption key (CEK)
travels inline in the same frame as the ciphertext it unwraps. `keyIncluded`
(a boolean flag) and `contentEncryptionKey` (32 fixed-size bytes, raw
AES-256 key material) exist to carry that.

`EncryptedFrameCodec.encode()` sets `keyIncluded` from `reachTier` itself:
`true` (CEK inlined) only for Locality; `false` for Town/City/Country, which
instead distribute the unwrap key separately over the socket-level envelope
(`TIER_KEY_REQUEST`/`TIER_KEY_RESPONSE`, see "Socket-level wire envelope"
below), gated by a tier-membership proof — a client without a valid tier
claim must not receive that key. Version 2 added this flag while only ever
setting it `true` (a deliberate design bet against a specific future need,
not speculative logic at the time); Phase 4 Slice 9 is what actually turns
it `false` on real frames and wires the separate key-distribution path
end-to-end.

`contentEncryptionKey` is a fixed-size 32-byte field, always reserved in the
header regardless of `keyIncluded` — even when unused (`keyIncluded=false`),
the field's 32 bytes are still present, zero-filled on encode, and simply
not read by consumers on decode. This is a deliberate simplicity tradeoff
over variable-length encoding: 32 bytes of overhead per frame is negligible,
and it keeps this particular field a fixed-size structure that's simple to
reason about and simple to parse (no length-prefix branching for this
field).

Reminder of the actual guarantee here (state plainly, per ADR 0003, do not
imply more): none of this makes reach-tier access control or decay
cryptographically absolute against a determined custom client. It raises the
cost of casual out-of-tier or post-decay access for the stock/reference
client. A client that captured a live key (inline, or via the separate
tier-key-distribution path) can keep decrypting after the fact; this
mechanism does not — and is not meant to — prevent that.

### `originGeohashPrefix` (Phase 4 Slice 9)

Empty string for Locality — it never touches the DHT and never needs this
field at all. For Town/City/Country, the post's own origin cell, encoded at
that tier's own geohash precision (`ReachTierGeohash.precisionFor`, at most
5 ASCII characters for TOWN) — computed once at post time by the poster's
device, which is the only device that ever reads a real latitude/longitude
for this post. This field is what lets any *other* device holding this post
answer a `TIER_KEY_REQUEST` for it later: it can recompute the same
target-cell-plus-neighbors set a poster's own device would
(`ReachTierGeohash.targetCellPrefixes(originGeohashPrefix)`, or equivalently
`Geohash.neighbors(originGeohashPrefix) + originGeohashPrefix`) and check an
inbound `TierMembershipClaim` against it, without that responding device
ever having (or needing) the post's raw origin coordinates — those never
travel on the wire anywhere in this codebase.

Unlike `contentEncryptionKey`, this field is length-prefixed (1 byte — the
longest tier precision, TOWN, is only 5 characters, so a uint8 length is
ample) rather than fixed-size: a fixed 5-byte reservation would cost every
single Locality frame (the overwhelming majority of traffic historically)
5 bytes for a field Locality never uses, whereas a length-prefixed empty
string costs only the 1-byte length prefix itself.

## Byte layout (version 3)

Header is at least 103 bytes (empty `originGeohashPrefix`) and at most 108
bytes (`originGeohashPrefix` at TOWN's 5-character precision), followed by
`payloadLength` bytes of payload.

| Offset | Length (bytes) | Field                   | Type            | Description                                                                 |
|-------:|----------------:|--------------------------|-----------------|------------------------------------------------------------------------------|
| 0      | 1               | `version`                | uint8           | Wire format version. `3` for this cut.                                      |
| 1      | 32              | `clipHash`               | bytes[32]       | SHA-256 hash of the **plaintext** clip/photo payload; content-addressed identifier. See "Encryption" above for why this hashes plaintext, not ciphertext. |
| 33     | 16              | `senderDeviceId`         | bytes[16]       | Random ephemeral per-install device ID. Not a persistent account identity.  |
| 49     | 1               | `contentType`            | uint8 (enum)    | `0`=PHOTO, `1`=VIDEO (BUILD_PLAN.md decision #4, PRD §4.1).                 |
| 50     | 1               | `hopCount`               | uint8           | Number of relay hops so far. `0` at origin.                                 |
| 51     | 8               | `originatedAtMs`         | int64           | Epoch millis when the post was first made. Input to decay logic.            |
| 59     | 4               | `ttlSeconds`             | uint32          | Time-to-live in seconds from `originatedAtMs`; also the decay-key window fed to `DecayKeyStore.store()`. |
| 63     | 1               | `reachTier`              | uint8 (enum)    | `0`=LOCALITY, `1`=TOWN, `2`=CITY, `3`=COUNTRY (PRD §4.2).                    |
| 64     | 1               | `dontRelay`               | uint8 (bool)    | Community propagation-control signal. `0`=false, `1`=true.                  |
| 65     | 1               | `keyIncluded`             | uint8 (bool)    | Whether `contentEncryptionKey` below is populated. `0`=false, `1`=true. `1` only for LOCALITY; `0` for TOWN/CITY/COUNTRY, which distribute the key separately (see "`keyIncluded` and `contentEncryptionKey`" above). |
| 66     | 32              | `contentEncryptionKey`    | bytes[32]       | Raw AES-256 content-encryption key (CEK) if `keyIncluded`; zero-filled and ignored otherwise. Always reserved (fixed-size) even when unused — see "Encryption" above. |
| 98     | 1               | `originGeohashPrefixLength` | uint8        | Byte length of `originGeohashPrefix` below. `0` for LOCALITY.               |
| 99     | `originGeohashPrefixLength` | `originGeohashPrefix` | UTF-8 string | Post's origin cell at its own tier's geohash precision. Empty for LOCALITY. See "`originGeohashPrefix`" above. |
| 99 + `originGeohashPrefixLength` | 4 | `payloadLength` | uint32 | Length in bytes of `payload`. |
| 103 + `originGeohashPrefixLength` | `payloadLength` | `payload` | bytes | Ciphertext: `iv (12 bytes) || ciphertext-with-appended-GCM-tag`, the exact blob produced by `ContentEncryption.encrypt()`. |

Total frame size = `103 + originGeohashPrefixLength + payloadLength` bytes.

### `contentType` enum values

| Value | Type  |
|------:|-------|
| 0     | PHOTO |
| 1     | VIDEO |

### `reachTier` enum values

| Value | Tier     |
|------:|----------|
| 0     | LOCALITY |
| 1     | TOWN     |
| 2     | CITY     |
| 3     | COUNTRY  |

Any other value is invalid for version 2 and decoders should reject it
rather than guess — this applies to `contentType` as well as `reachTier`,
`dontRelay`, and `keyIncluded`.

## Non-goals of this cut

Explicitly out of scope for this document/version, per BUILD_PLAN.md phase
scope (do not implement these against this frame yet):

- Relay/forwarding logic that acts on `hopCount`, `ttlSeconds`, or
  `dontRelay` (Phase 1/2).
- Geohash-tier resolution logic (Phase 1+; `reachTier` here is just a
  transported value).
- Multi-frame chunking for large clips (today's implementation sends one
  frame with the whole clip as `payload`).
- **The requesting side** of `TIER_KEY_REQUEST`/`TIER_KEY_RESPONSE`: a device
  proactively deciding to *send* a `TIER_KEY_REQUEST` when it's missing a key
  for a post it's holding (who to ask, retry/timeout policy, which
  connection to use) is still open design work for a follow-up slice.
  `EncryptedFrameCodec.decryptFromStore` simply returns `null` for a tiered
  post whose key hasn't arrived yet, same as an already-decayed post from
  the caller's point of view — see `ReachTierKeyDistribution`'s own "Explicitly
  out of scope" note. **The responding side** (answering an inbound
  `TIER_KEY_REQUEST` for a post this device holds) is wired end-to-end as of
  Phase 4 Slice 9 — see `com.hop.transport.EnvelopeDispatcher.dispatch`'s
  `TIER_KEY_REQUEST` branch.
- Messaging (1:1/group Double Ratchet) — that's a separate `crypto/`
  concern from post/clip content encryption; `Frame` itself carries post
  content only, never chat messages. (The socket-level wire envelope below
  *does* now transport messaging ciphertext and prekey bundles alongside
  `Frame`, but it does so as an opaque sibling payload type — it never wraps
  messaging content inside a `Frame`, and `Frame`'s own byte layout above is
  completely unaffected.)

## Socket-level wire envelope (version 1)

Everything above this section describes `Frame` — the payload for a *post*.
This section describes a separate, outer layer: the framing actually written
to and read from the WiFi Direct transfer socket by
`mobile/android/.../transport/WifiDirectTransport.kt`.

**Before this section existed** (i.e. everywhere in this document above),
that socket framing was an unversioned implementation detail, not itself
part of this spec: `[4-byte big-endian length][Frame bytes]`, nothing else.
Every blob on the socket was assumed to be a `Frame`.

**As of envelope version 1**, the socket framing is tagged:

```
[1-byte WirePayloadType][4-byte big-endian length][payload bytes]
```

This is a version bump under this document's own discipline ("any change to
field layout, field set, or encoding rules MUST bump version") — applied
here to the envelope layer specifically, not to `Frame`. **This is an
intentional breaking change to the socket wire format.** Old bare
`[length][Frame bytes]` bytes are no longer valid input to the envelope
decoder: there is no other wire-format consumer or interoperating relay-node
implementation yet at this stage of the project, so there is no compatibility
shim and none is planned until one exists.

Why a new layer instead of folding this into `Frame` itself, per ADR 0001:
`Frame`'s 102-byte layout is completely unchanged by this — a
`WirePayloadType.POST_FRAME`-typed envelope's payload is exactly what
`Frame.encode()` already produced, byte-for-byte, just now prefixed with a
type tag before it hits the socket. This is purely additive at the `Frame`
level. The reason a post frame needs a sibling at the socket layer at all is
the 1:1 encrypted-messaging slice (PRD §4.3-4.4): prekey bundle announcements
and Double Ratchet ciphertext now also travel over the same WiFi Direct
socket a device already has open to a peer, and a receiver needs to know
which of the three it's looking at before it can decode it.

### `WirePayloadType` enum values

| Value | Type                 | Payload shape                                                              |
|------:|----------------------|------------------------------------------------------------------------------|
| 0     | `POST_FRAME`         | Exactly a `Frame.encode()` output, unchanged. See "Byte layout (version 2)" above. |
| 1     | `PREKEY_BUNDLE`      | A `PreKeyBundleEnvelope.encode()` output — see below.                       |
| 2     | `MESSAGE_CIPHERTEXT` | A `MessageCiphertextEnvelope.encode()` output — see below.                  |
| 3     | `DONT_RELAY_FLAG`    | A `DontRelayFlagEnvelope.encode()` output — see below.                      |
| 4     | `TIER_KEY_REQUEST`   | A `TierKeyRequestEnvelope.encode()` output — see below.                     |
| 5     | `TIER_KEY_RESPONSE`  | A `TierKeyResponseEnvelope.encode()` output — see below.                    |

Any other value is invalid for envelope version 1 and decoders must reject it
rather than guess, matching `Frame`'s own decode discipline.

### `PreKeyBundleEnvelope` and `MessageCiphertextEnvelope` payload shapes

Both are opaque-payload wrappers with their own simple length-prefixed
encoding, independent of `Frame`'s fixed-header design (these payloads are
inherently variable-shaped, so there's no equivalent fixed-header win here):

- `PreKeyBundleEnvelope`: `[4-byte peerId UTF-8 byte length][peerId UTF-8
  bytes][1-byte hopCount][8-byte originatedAtMs][remaining bytes = opaque
  serialized PreKeyBundle bytes]`. `hopCount`/`originatedAtMs` were added for
  prekey-bundle mesh flood relay (the "prekey-bundle relay/discovery"
  follow-up to Phase 2's four original slices: post relay, "don't relay"
  flags, 1:1 store-and-forward, group messaging) — a breaking change to this
  envelope's previous shape, accepted under the same "no real users yet"
  justification that covered `MessageCiphertextEnvelope`'s own
  `hopCount`/`originatedAtMs` addition below and `WireEnvelope`'s
  introduction as a breaking change to the socket framing (see below). They
  mirror `MessageCiphertextEnvelope.hopCount`/`MessageCiphertextEnvelope.originatedAtMs`
  exactly (same uint8/int64 encoding, same semantics: `hopCount` is `0` at a
  genuine direct announce and increments by one per relay hop; `originatedAtMs`
  is epoch millis at first announce) so a bundle relay-custody row can be
  evaluated against the same `RelayPolicy.isEligibleForRelay`/
  `RelayPolicy.isExpired` already used for posts and messages, unmodified.
  The TTL these are checked against is `PreKeyBundleEnvelope.DEFAULT_TTL_SECONDS`
  (6 hours) — a propagation-hygiene knob, not a safety control: a stale
  bundle already fails safely on its own (see
  `com.hop.repository.BundleRepository`'s own "Limits" doc for the traced
  libsignal-client call chain establishing that). **`hopCount == 0` is
  load-bearing on the receiving end**, not just a relay-eligibility input: it
  is what `com.hop.transport.EnvelopeDispatcher.dispatch`'s `PREKEY_BUNDLE`
  branch uses to decide whether the connection an envelope arrived on
  genuinely belongs to `peerId` (direct announce, safe to tag as this
  device's line to that peer) or merely carried `peerId`'s bundle on their
  behalf (a relayed copy, `hopCount >= 1`) — conflating the two would let a
  later message send believe it reached the bundle's owner directly when it
  only reached a carrier, skipping durable relay custody with nothing to
  fall back on.
- `MessageCiphertextEnvelope`: `[4-byte senderPeerId UTF-8 byte
  length][senderPeerId UTF-8 bytes][4-byte recipientPeerId UTF-8 byte
  length][recipientPeerId UTF-8 bytes][1-byte hopCount][8-byte
  originatedAtMs][remaining bytes = opaque Double Ratchet ciphertext]`.
  `hopCount`/`originatedAtMs` were added in Phase 2 Slice 3 (store-and-forward
  for offline 1:1 recipients) — a breaking change to this envelope's
  previous shape, accepted under the same "no real users yet" justification
  that covered `WireEnvelope`'s own introduction as a breaking change to the
  socket framing (see below). They mirror `Frame.hopCount`/
  `Frame.originatedAtMs` exactly (same uint8/int64 encoding, same semantics:
  `hopCount` starts at `0` and increments by one per carrier hop;
  `originatedAtMs` is epoch millis at first send) so a message relay-custody
  row can be evaluated against the same `RelayPolicy.isEligibleForRelay`/
  `RelayPolicy.isExpired` used for posts, unmodified. The TTL these are
  checked against is `MessageCiphertextEnvelope.DEFAULT_TTL_SECONDS` — a
  fixed constant every implementation must independently agree on,
  deliberately **not** itself carried on the wire (unlike posts'
  reach-tier-driven `Frame.ttlSeconds`): no per-message configurability is
  needed, and messages are more sensitive to lingering carrier-side metadata
  exposure than posts are, so this placeholder is deliberately shorter than
  posts' own 24h TTL placeholder — see `com.hop.repository.PendingMessageRepository`'s
  own "Limits" doc for why.

`DontRelayFlagEnvelope` (Phase 2 Slice 2, PRD §4.6/ADR 0004's "don't relay"
signal-counting) is shaped differently from the two opaque-payload wrappers
above — every field is meaningful to `protocol/` itself (there is no opaque
blob here), so it gets its own fixed-plus-one-length-prefixed layout instead:

`[32-byte clipHash][4-byte attestedDeviceKey byte length][attestedDeviceKey
bytes][8-byte flaggedAtMs][8-byte originatedAtMs][4-byte ttlSeconds]`.

- `clipHash` identifies which post this flag refers to (same 32-byte
  content-addressed hash `Frame.clipHash` uses).
- `attestedDeviceKey` is the flagging device's attested public key
  (ADR 0004) — length-prefixed, not fixed-size, since a real Play
  Integrity/App Attest key will differ in size from
  `StubAttestationProvider`'s nonce-sized stand-in; length-prefixing now
  avoids a future wire-format bump once real attestation lands.
- `flaggedAtMs` is when this device raised the flag (diagnostic only, not
  used in any eligibility decision).
- `originatedAtMs`/`ttlSeconds` are **denormalized off the post this flag
  refers to**, not looked up locally: mesh delivery order isn't guaranteed,
  so a device can receive a flag for a clipHash before it ever receives the
  post itself. A flag has no other source for a TTL to bound its own
  propagation against in that case, so the flagging device (which already
  has these values from its own locally-held post) carries them on the flag
  itself. This is what lets flag expiry reuse `RelayPolicy.isExpired`/
  `expiresAtMs` exactly as posts already do.
- Nothing that decodes this envelope ever rewrites a `Frame`'s own on-wire
  `dontRelay` bit — see `DontRelayFlagEnvelope`'s own doc and
  `com.hop.repository.DontRelayRepository`'s doc for the local,
  per-device-only state this actually mutates.

`TierKeyRequestEnvelope`/`TierKeyResponseEnvelope` (Phase 4, ADR 0003's
key-distribution half — see `ReachTierKeyDistribution.kt`) carry a
tier-membership claim and its answer between two peers over the same WiFi
Direct socket, so the requester never needs a DHT/topic query to learn
whether it gets the unwrap key:

`TierKeyRequestEnvelope`: `[32-byte contentId][1-byte reachTier][4-byte
geohashPrefix UTF-8 byte length][geohashPrefix UTF-8 bytes][8-byte
claimedAtMs]`.

- `contentId` is the same 32-byte content-addressed hash as `Frame.clipHash`
  — the post whose key is being requested.
- `reachTier`/`geohashPrefix`/`claimedAtMs` are `TierMembershipClaim`'s three
  fields, transported flat rather than as an opaque blob (every field is
  meaningful to `protocol/` itself, same reasoning as `DontRelayFlagEnvelope`
  above). `geohashPrefix` is length-prefixed rather than fixed-size even
  though `ReachTierGeohash.precisionFor(reachTier)` determines its length —
  this keeps the decoder from needing that mapping just to find the header
  boundary.
- A decoded claim that fails `TierMembershipClaim`'s own construction-time
  validation (`reachTier == LOCALITY`, since Locality never needs a claim; or
  a `geohashPrefix` length that doesn't match `reachTier`'s precision) is
  rejected as a decode failure (`TierKeyRequestEnvelopeDecodeException`), not
  silently coerced.

`TierKeyResponseEnvelope`: `[32-byte contentId][1-byte granted][4-byte
wrappedCek byte length][wrappedCek bytes]`.

- `contentId` echoes the request's `contentId`.
- `granted` is an explicit flag byte (`0`=denied, `1`=granted), not inferred
  from `wrappedCek`'s length — matching `Frame.dontRelay`/`Frame.keyIncluded`'s
  own explicit-flag-byte convention, and avoiding the ambiguity a zero-length
  key would otherwise create.
- `wrappedCek` must be empty when `granted` is `0` — a denial never carries
  key bytes, even stale ones. It is length-prefixed rather than fixed-size
  like `Frame.contentEncryptionKey`, since ADR 0003 describes the key as
  "wrapped separately per reach tier," implying real wrap overhead beyond
  today's raw 32-byte CEK is still to come; length-prefixing now avoids a
  future wire-format bump once that lands.
- The decision logic that produces a `granted`/`denied` answer —
  `ReachTierKeyDistribution.releaseKeyFor` — checks
  `TierClaimVerifier.isWithinTier`, the claim's own staleness (reusing
  `RelayPolicy.isExpired`, a *separate* bound from the post's own decay
  window), and whether the tier-specific `DecayKeyStore` entry (keyed by
  `"$contentId:${reachTier.wireValue}"`, composed in `protocol/` rather than
  teaching `crypto/`'s `DecayKeyStore` about `ReachTier` — see that class's
  own doc for why) has itself decayed. See "Limits" reminders throughout this
  document and ADR 0003: this is a deterrent against casual/stock-client
  scraping, not server-side access control — a determined custom client can
  fabricate a claim outright.

`peerId`/`senderPeerId`/`recipientPeerId` are opaque identifying strings as
far as `protocol/` is concerned (in practice, the hex-encoded
`senderDeviceId` string already used elsewhere on the wire — see that
field's own doc above — but this module doesn't need to know that semantic).
The bundle bytes and ciphertext bytes are **never deserialized or decrypted
by `protocol/`** — per ADR 0001, `protocol/` may depend on `crypto/` (an
existing, already-established dependency via `EncryptedFrameCodec`, not a
new one added here), never the reverse, and this envelope layer specifically
adds **no new dependency on libsignal-client types**. A serialized
`PreKeyBundle` or Double Ratchet ciphertext blob is exactly as opaque to
`protocol/` as `Frame.payload` already is to `Frame` itself.

Reminder of the actual guarantee here, echoing the "Encryption" section
above: none of this envelope layer implies any access-control or
confidentiality property on its own. `PREKEY_BUNDLE`/`MESSAGE_CIPHERTEXT`
payloads are already encrypted (or, for a prekey bundle, meant to be public
key material) by the time they reach this layer — this envelope only tags
*what kind* of bytes follow, it does not itself protect them.

## Reference implementation

The reference implementation now spans several files, split per ADR 0001's
module boundary:

- `protocol/src/main/kotlin/com/hop/protocol/Frame.kt` — the pure wire
  envelope (`Frame.encode()` / `Frame.decode()`). It has **no** dependency
  on `crypto/`: `payload` and `contentEncryptionKey` are opaque `ByteArray`s
  as far as this class is concerned. It is intentionally a plain JVM Kotlin
  module with no Android dependency, so it can be reused by any future
  platform or relay-node implementation that runs on the JVM, and so the
  wire-envelope logic in `protocol/` stays free of platform-specific
  concerns per BUILD_PLAN.md's module boundaries.
- `protocol/src/main/kotlin/com/hop/protocol/EncryptedFrameCodec.kt` — the
  encrypt/decrypt orchestration layer that builds/consumes a `Frame` from
  plaintext content. This file *does* depend on `crypto/`
  (`ContentEncryption`, `DecayKeyStore`), per ADR 0001's explicit direction:
  "`protocol/` depends on `crypto/` to encrypt/decrypt message payloads
  before they hit the wire," never the reverse.
- `protocol/src/main/kotlin/com/hop/protocol/WireEnvelope.kt` — the
  socket-level `[type][length][payload]` framing described above
  (`WirePayloadType`, `WireEnvelope.encode()`/`WireEnvelope.decode()`). Like
  `Frame.kt`, it has no dependency on `crypto/` — `payload` is opaque bytes
  regardless of `type`.
- `protocol/src/main/kotlin/com/hop/protocol/PreKeyBundleEnvelope.kt` and
  `protocol/src/main/kotlin/com/hop/protocol/MessageCiphertextEnvelope.kt` —
  the two opaque-payload wrapper shapes described above. Neither imports any
  `org.signal.libsignal.*` type.
- `protocol/src/main/kotlin/com/hop/protocol/DontRelayFlagEnvelope.kt`
  (Phase 2 Slice 2) — the "don't relay" flag payload shape described above.
  Has no dependency on `crypto/` and imports no libsignal type, same posture
  as `Frame.kt`/`WireEnvelope.kt`.
- `protocol/src/main/kotlin/com/hop/protocol/TierKeyRequestEnvelope.kt` and
  `protocol/src/main/kotlin/com/hop/protocol/TierKeyResponseEnvelope.kt`
  (Phase 4) — the tier-membership-claim request/response payload shapes
  described above. Neither has a `crypto/` dependency; they carry the claim
  and the (possibly denied) key answer as plain fields/bytes.
- `protocol/src/main/kotlin/com/hop/protocol/ReachTierKeyDistribution.kt`
  (Phase 4) — the decision logic a `TierKeyRequestEnvelope` receiver runs to
  decide what `TierKeyResponseEnvelope` to send back. This file *does* depend
  on `crypto/` (`DecayKeyStore`), for the same ADR 0001 reason
  `EncryptedFrameCodec.kt` already does — it is not a new dependency shape,
  just a second file exercising the existing one-way rule.
