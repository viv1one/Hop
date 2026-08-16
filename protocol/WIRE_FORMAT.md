# HOP Wire Format

Status: **version 1, current.** This is BUILD_PLAN.md's "open decision #4"
output for Phase 0 — a versioned frame format every device on the mesh (and
every independent relay-node implementation, once those exist in Phase 2+)
must agree on to interoperate.

Fields, byte layout, and encoding may still change before Phase 1/2 relay and
decay logic locks this down for real. That is expected. What is **not**
allowed is a silent, unversioned change: any change to field layout, field
set, or encoding rules MUST bump `version`. A decoder that receives a frame
with a `version` it does not understand MUST reject it (throw / return an
error), never guess at the layout. Backward-compatible parsing across
versions is a Phase 1+ concern once more than one version exists in the
field concurrently; for now, version 1 is the only defined version and there
is no compatibility shim — version 0 is superseded and is rejected on
decode, not silently upgraded.

**Version 1 supersedes version 0** specifically to add a `contentType` field
distinguishing photo posts from video posts, per BUILD_PLAN.md open decision
#4's updated wording ("a versioned wire format from day one... **content-type
flag distinguishing photo from video**...") and PRD §4.1/§8, which added
photo as a first-class v1 post type alongside video. Version 0 had no way to
represent a photo post at all, so this was a field-set change, not a
cosmetic one — hence the version bump rather than an in-place edit.

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
- The frame has a fixed-size header (69 bytes) followed by a variable-length
  payload.
- `clipHash` is a content-addressed identifier (SHA-256 of the clip/photo
  payload), not a server-issued ID — there is no server to issue one.
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
  fields land in Phase 1/2 relay code, not here. Carrying the fields now
  (rather than adding them once relay logic exists) is deliberate: per
  CLAUDE.md and BUILD_PLAN.md, decay and "don't relay" are protocol-layer
  primitives designed in from the start, not retrofitted later.
- `reachTier` mirrors the four PRD §4.2 reach tiers (Locality → Town → City
  → Country). At Locality tier this frame travels over a direct WiFi Direct
  hop between physically nearby devices; higher tiers are what `reachTier`
  will help gate once DHT/internet-mode (Phase 4) and multi-hop relay
  (Phase 2) exist. This field does not itself implement tier resolution —
  that's geohash-prefix logic that lives elsewhere in `protocol/`.

## Byte layout (version 1)

Fixed header is 69 bytes, followed by `payloadLength` bytes of payload.

| Offset | Length (bytes) | Field             | Type            | Description                                                                 |
|-------:|----------------:|--------------------|-----------------|------------------------------------------------------------------------------|
| 0      | 1               | `version`          | uint8           | Wire format version. `1` for this cut.                                      |
| 1      | 32              | `clipHash`         | bytes[32]       | SHA-256 hash of the clip/photo payload; content-addressed identifier.       |
| 33     | 16              | `senderDeviceId`   | bytes[16]       | Random ephemeral per-install device ID. Not a persistent account identity.  |
| 49     | 1               | `contentType`      | uint8 (enum)    | `0`=PHOTO, `1`=VIDEO (BUILD_PLAN.md decision #4, PRD §4.1).                 |
| 50     | 1               | `hopCount`         | uint8           | Number of relay hops so far. `0` at origin.                                 |
| 51     | 8               | `originatedAtMs`   | int64           | Epoch millis when the post was first made. Input to future decay logic.     |
| 59     | 4               | `ttlSeconds`       | uint32           | Time-to-live in seconds, measured from `originatedAtMs`.                    |
| 63     | 1               | `reachTier`        | uint8 (enum)    | `0`=LOCALITY, `1`=TOWN, `2`=CITY, `3`=COUNTRY (PRD §4.2).                    |
| 64     | 1               | `dontRelay`        | uint8 (bool)    | Community propagation-control signal. `0`=false, `1`=true.                  |
| 65     | 4               | `payloadLength`    | uint32           | Length in bytes of `payload`.                                               |
| 69     | `payloadLength` | `payload`          | bytes           | The photo/clip bytes (or a placeholder/pointer, for Phase 0 spike purposes).|

Total frame size = `69 + payloadLength` bytes.

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

Any other value is invalid for version 1 and decoders should reject it
rather than guess — this applies to `contentType` as well as `reachTier`
and `dontRelay`.

## Non-goals of this first cut

Explicitly out of scope for this document/version, per BUILD_PLAN.md Phase 0
scope (do not implement these against this frame yet):

- Relay/forwarding logic that acts on `hopCount`, `ttlSeconds`, or
  `dontRelay` (Phase 1/2).
- Geohash-tier resolution logic (Phase 1+; `reachTier` here is just a
  transported value).
- Any encryption of `payload` — Phase 1 introduces `crypto/` for messaging
  (Double Ratchet / sender-key group ratchet). Clip payload confidentiality
  and message E2E encryption are separate concerns from this transport
  frame; this frame does not itself specify how/whether `payload` bytes are
  encrypted. When encryption applies, encryption happens in `crypto/` before
  bytes are handed to `protocol/` for framing — `protocol/` depends on
  `crypto/`, never the reverse (ADR 0001).
- Multi-frame chunking for large clips (today's Phase 0 spike sends one
  frame with the whole clip as `payload`).

## Reference implementation

`protocol/src/main/kotlin/com/hop/protocol/Frame.kt` in this repo is the
Kotlin reference implementation of this spec (`Frame.encode()` /
`Frame.decode()`). It is intentionally a plain JVM Kotlin module with no
Android dependency, so it can be reused by any future platform or
relay-node implementation that runs on the JVM, and so `protocol/` stays
free of platform-specific concerns per BUILD_PLAN.md's module boundaries.
