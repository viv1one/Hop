# HOP Wire Format

Status: **version 2, current.** This is BUILD_PLAN.md's "open decision #4"
output for Phase 0, extended by Phase 1's encrypted-content-carrying work — a
versioned frame format every device on the mesh (and every independent
relay-node implementation, once those exist in Phase 2+) must agree on to
interoperate.

Fields, byte layout, and encoding may still change before Phase 2+ relay and
decay logic locks this down for real. That is expected. What is **not**
allowed is a silent, unversioned change: any change to field layout, field
set, or encoding rules MUST bump `version`. A decoder that receives a frame
with a `version` it does not understand MUST reject it (throw / return an
error), never guess at the layout. Backward-compatible parsing across
versions is a later concern once more than one version exists in the field
concurrently; for now, version 2 is the only defined version and there is no
compatibility shim — versions 0 and 1 are superseded and are rejected on
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
- The frame has a fixed-size header (102 bytes) followed by a variable-length
  payload.
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

### `keyIncluded` and `contentEncryptionKey` (Phase-4-forward compatibility)

Per ADR 0003, reach-tier limits above Locality are enforced by per-tier
key-wrapping, not by topic-key secrecy (geohash prefixes aren't secret). At
Locality tier — the only tier Phase 1 has, since DHT/internet-mode doesn't
exist until Phase 4 — "the ciphertext and key both stay on local mesh only,"
so the content-encryption key (CEK) travels inline in the same frame as the
ciphertext it unwraps. `keyIncluded` (a boolean flag) and
`contentEncryptionKey` (32 fixed-size bytes, raw AES-256 key material) exist
to carry that.

Phase 1's only code path (`EncryptedFrameCodec.encode()`) always sets
`keyIncluded = true` and inlines a freshly generated CEK. Town/City/Country
tiers (Phase 4+) will instead set `keyIncluded = false` and distribute the
unwrap key separately, gated by a tier-membership proof — a client without a
valid tier claim must not receive that key. The entire point of adding this
flag *now*, in a phase that only ever sets it to `true`, is so that Phase 4
can turn it `false` on some frames **without another wire-format version
bump**. This is a deliberate design bet against a specific future need, not
speculative Phase 4 logic — no Phase 4 key-distribution mechanism is
implemented in this repo yet.

`contentEncryptionKey` is a fixed-size 32-byte field, always reserved in the
header regardless of `keyIncluded` — even when unused (`keyIncluded=false`),
the field's 32 bytes are still present, zero-filled on encode, and simply
not read by consumers on decode. This is a deliberate simplicity tradeoff
over variable-length encoding: 32 bytes of overhead per frame is negligible,
and it keeps the header a fixed-size structure that's simple to reason about
and simple to parse (no length-prefix branching for this field), consistent
with the rest of this header's fixed-size design.

Reminder of the actual guarantee here (state plainly, per ADR 0003, do not
imply more): none of this makes reach-tier access control or decay
cryptographically absolute against a determined custom client. It raises the
cost of casual out-of-tier or post-decay access for the stock/reference
client. A client that captured a live key (inline today, or via Phase 4's
separate distribution later) can keep decrypting after the fact; this
mechanism does not — and is not meant to — prevent that.

## Byte layout (version 2)

Fixed header is 102 bytes, followed by `payloadLength` bytes of payload.

| Offset | Length (bytes) | Field                   | Type            | Description                                                                 |
|-------:|----------------:|--------------------------|-----------------|------------------------------------------------------------------------------|
| 0      | 1               | `version`                | uint8           | Wire format version. `2` for this cut.                                      |
| 1      | 32              | `clipHash`               | bytes[32]       | SHA-256 hash of the **plaintext** clip/photo payload; content-addressed identifier. See "Encryption" above for why this hashes plaintext, not ciphertext. |
| 33     | 16              | `senderDeviceId`         | bytes[16]       | Random ephemeral per-install device ID. Not a persistent account identity.  |
| 49     | 1               | `contentType`            | uint8 (enum)    | `0`=PHOTO, `1`=VIDEO (BUILD_PLAN.md decision #4, PRD §4.1).                 |
| 50     | 1               | `hopCount`               | uint8           | Number of relay hops so far. `0` at origin.                                 |
| 51     | 8               | `originatedAtMs`         | int64           | Epoch millis when the post was first made. Input to decay logic.            |
| 59     | 4               | `ttlSeconds`             | uint32          | Time-to-live in seconds from `originatedAtMs`; also the decay-key window fed to `DecayKeyStore.store()`. |
| 63     | 1               | `reachTier`              | uint8 (enum)    | `0`=LOCALITY, `1`=TOWN, `2`=CITY, `3`=COUNTRY (PRD §4.2).                    |
| 64     | 1               | `dontRelay`               | uint8 (bool)    | Community propagation-control signal. `0`=false, `1`=true.                  |
| 65     | 1               | `keyIncluded`             | uint8 (bool)    | Whether `contentEncryptionKey` below is populated. `0`=false, `1`=true. Always `1` in Phase 1 (Locality-only); Phase 4 introduces `0` for DHT-gated tiers. |
| 66     | 32              | `contentEncryptionKey`    | bytes[32]       | Raw AES-256 content-encryption key (CEK) if `keyIncluded`; zero-filled and ignored otherwise. Always reserved (fixed-size) even when unused — see "Encryption" above. |
| 98     | 4               | `payloadLength`           | uint32          | Length in bytes of `payload`.                                               |
| 102    | `payloadLength` | `payload`                 | bytes           | Ciphertext: `iv (12 bytes) || ciphertext-with-appended-GCM-tag`, the exact blob produced by `ContentEncryption.encrypt()`. |

Total frame size = `102 + payloadLength` bytes.

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
- Town/City/Country DHT-gated key distribution (`keyIncluded = false` code
  paths, tier-membership proofs) — Phase 4. This wire format is built to
  carry that without another version bump, but the distribution mechanism
  itself isn't implemented yet.
- Multi-frame chunking for large clips (today's implementation sends one
  frame with the whole clip as `payload`).
- Messaging (1:1/group Double Ratchet) — that's a separate `crypto/`
  concern from post/clip content encryption; this frame carries post
  content, not chat messages.

## Reference implementation

The reference implementation now spans two files, split per ADR 0001's
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
