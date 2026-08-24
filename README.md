# HOP

**Serverless, proximity-first short-form photo/video and messaging.**

No company-owned servers, no user accounts, no centralized algorithmic feed. Posts (photo or video) and end-to-end encrypted messages move phone-to-phone over Bluetooth LE discovery + WiFi Direct — and, in later phases, over a peer-to-peer DHT internet mode — with reach chosen per-post across geohash-based tiers (Locality → Town → City → Country), decaying over time and distance unless re-shared.

> Concept-stage project. Architecture and phased build plan are locked; implementation is in progress (currently mid-way through Phase 4, "internet mode" — see [Status](#status)).

---

## Table of contents

- [Why HOP exists](#why-hop-exists)
- [Non-negotiable constraints](#non-negotiable-constraints)
- [How it works](#how-it-works)
- [Repository layout](#repository-layout)
- [Status](#status)
- [Getting started](#getting-started)
- [Testing](#testing)
- [Documentation map](#documentation-map)
- [Build plan / roadmap](#build-plan--roadmap)

---

## Why HOP exists

Short-form video today runs almost entirely on centralized platforms that harvest behavioral data for ad targeting, decide reach through an opaque algorithm, store content indefinitely, and stop working the moment you lose connectivity. HOP inverts that model: content lives on-device first, spreads through physical and network proximity, and fades naturally over time and distance unless people actively keep it alive by re-sharing. There is no HOP-operated backend that could be subpoenaed, breached, sold, or quietly start harvesting data — because it doesn't exist.

The full product and business rationale is in [docs/memo.md](docs/memo.md); the feature-level spec is in [docs/prd.md](docs/prd.md).

## Non-negotiable constraints

These carry through every phase of the build and are treated as hard architectural boundaries, not aspirations:

- **No HOP-owned server in the content, discovery, or message-delivery path — ever.** The one narrow, explicitly scoped exception is address-only bootstrap/rendezvous nodes for cold-start peer discovery (zero content, zero topic visibility, phased out via peer exchange once a device has a live peer) — see [ADR 0002](docs/adr/0002-bootstrap-node-carveout.md).
- **No accounts.** Identity is local-device-only, bound to a hardware attestation token (Play Integrity on Android / App Attest on iOS) that proves "one real device," never who owns it — see [ADR 0004](docs/adr/0004-sybil-resistant-identity.md). This is what makes faucet caps, "don't relay" signal-counting, and blocking mean anything against free reinstalls.
- **Ephemeral decay and "don't relay" community propagation control are protocol primitives**, designed in from Phase 1, not retrofitted later — enforced by decryption-key expiry and attested-identity-gated signal counting ([ADR 0003](docs/adr/0003-cryptographic-decay-and-reach-enforcement.md), [ADR 0004](docs/adr/0004-sybil-resistant-identity.md)), not client politeness alone. This is a strong deterrent for the honest-client population, not an absolute guarantee against a determined custom client — the docs say so plainly rather than implying otherwise.
- **1:1 and group messages are end-to-end encrypted** using a Signal Protocol pattern (Double Ratchet for 1:1, per-member pairwise fan-out for groups), such that no relay node — volunteer-operated or otherwise — can ever read plaintext content.

## How it works

1. **Post.** A user picks an existing photo or short video (~15s) from their device media library — v1 has no in-app camera, see [docs/prd.md §4.1](docs/prd.md) — and chooses a reach tier: Locality (~100m), Town, City, or Country.
2. **Local discovery.** Nearby devices are found via Bluetooth LE advertising/scanning.
3. **Local transfer.** The actual photo/video payload moves peer-to-peer over WiFi Direct once two devices have discovered each other.
4. **Wider reach (Phase 4+).** For tiers above Locality, content is published into a Kademlia-style DHT addressed by geohash-prefix topics, so it's discoverable by anyone subscribed to the target cell (and its neighbor cells, to avoid boundary misses) without any central index.
5. **Decay.** Content is encrypted at rest with a key on a decay/reach-tier schedule — access fades as the key expires, not just as a feed-ranking convention (ADR 0003).
6. **Messaging.** From any post, a user can start an end-to-end encrypted 1:1 chat with no phone number, or create an encrypted group — both over the same peer-to-peer transport, with store-and-forward relay (Phase 2+) so offline recipients still get delivery once a relay node has passed the ciphertext along.
7. **Moderation without a server.** A "don't relay" signal from enough distinct, hardware-attested nearby peers halts a clip's further propagation client-side (Phase 2+) — a deterrent, not a takedown authority, since no server exists to enforce removal from every cached copy.

See [protocol/WIRE_FORMAT.md](protocol/WIRE_FORMAT.md) for the exact, versioned byte-level frame and envelope formats every device (and every independent relay-node implementation) must agree on.

## Repository layout

```text
/mobile/android/    Native Android app (Kotlin + Jetpack Compose). The actual product build.
/mobile/ios/         Native iOS app (Swift) — Phase 3+, not started.
/protocol/           Wire format (Frame, envelopes), geohash-tier resolution, relay policy,
                      "don't relay" flag envelope — cross-platform-agreed, versioned, plain-Kotlin JVM module.
/crypto/             Messaging encryption: Double Ratchet sessions, content encryption,
                      decay-key store, device-attestation identity, prekey-bundle codec.
                      One-way dependency from /protocol/ (ADR 0001).
/dht/                Kademlia-style DHT: routing table, k-buckets, node IDs, PING/FIND_NODE/
                      FIND_VALUE/STORE RPCs over UDP, iterative lookup. Phase 4 (internet mode).
/topics/             Geohash-prefix topic keys and DHT topic subscription — the bridge
                      between /protocol/'s geohash-tier logic and /dht/'s content-addressed storage.
/rendezvous/         Bootstrap/rendezvous nodes (ADR 0002) — address-only, zero content/topic
                      visibility. Not started; tracked separately so its minimal scope stays
                      enforced by code structure, not just policy.
/contracts/          Token contract (transfer-restricted faucets/sinks) — Phase 6+, not started.
/tools/relay-node/   Volunteer relay-node tooling — Phase 4+, not started.
/tools/preseed/      Internet-mode content pre-seeding tooling — Phase 4+, not started.
/docs/               Investor memo, PRD, and architecture decision records (see below).
BUILD_PLAN.md        Execution sequence and phase-by-phase scope — the source of truth for
                      what's next and why phases are ordered the way they are.
```

`mobile/android/app` is a standard Gradle multi-module Android project that depends on `:protocol`, `:crypto`, `:dht`, and `:topics` as local project modules (see `mobile/android/settings.gradle.kts`).

## Status

Stage: **concept-to-implementation, mid-build.** Nothing here is a finished product — treat every phase past what's listed below as design-complete but uncoded.

Implemented so far (see `git log` for the authoritative, incremental history):

- **Phase 0 — Feasibility spikes:** done. BLE discovery and WiFi Direct throughput/density spikes validated the core transport assumptions and produced the first cut of the wire format (real-device measurements are recorded in [BUILD_PLAN.md](BUILD_PLAN.md#open-decisions-before-phase-0-starts) — e.g. ~120-150 Mbps real WiFi Direct throughput between two phones, settling v1 clip length at ~15s).
- **Phase 1 — MVP (single device pair to small local mesh):** done. Real Compose UI (proximity-ordered feed + inbox, not the Phase 0 spike harness), device-attestation identity at first run, BLE/WiFi Direct post transfer, content encrypted at rest on the ADR 0003 decay schedule, and 1:1 Double Ratchet encrypted messaging with a local block list.
- **Phase 2 — Multi-hop relay:** done. App-layer store-and-forward relay, "don't relay" attested-signal propagation control, a non-tradeable local points counter (bridging to the real token ledger once Phase 6 ships), and group messaging via per-member pairwise Double Ratchet fan-out.
- **Phase 4 — Internet mode:** in progress. Geohash-tier resolution, a from-scratch Kademlia DHT (routing table, k-buckets, PING/FIND_NODE/FIND_VALUE/STORE over UDP, iterative lookup, bootstrap-join), and geohash-prefix topic subscription (publish/browse across the target cell plus neighbor cells) are wired into the Android app. Reach-tier key-wrapping for Town/City/Country and a real (non-HOP-operated) bootstrap node are still outstanding before this phase is launch-complete.
- **Phase 3 (iOS)**, **Phase 5 (trust & safety hardening)**, **Phase 6 (token economy)**, and **Phase 7 (GTM pilot)**: not started — `mobile/ios/`, `rendezvous/`, and `contracts/` are empty placeholders per the planned repo structure.

For the detailed, dated reasoning behind what's done and what's next, read [BUILD_PLAN.md](BUILD_PLAN.md) — it is the living execution plan, not just historical record.

## Getting started

The only buildable target today is the Android app.

**Prerequisites**
- Android Studio (or the Gradle CLI) with JDK 17
- Two physical Android devices for any BLE/WiFi Direct testing — local discovery and transfer cannot be exercised on an emulator

**Build**

```bash
cd mobile/android
./gradlew :app:assembleDebug
```

**Install to a connected device**

```bash
./gradlew :app:installDebug
```

Key `defaultConfig` values (see `mobile/android/app/build.gradle.kts`): `minSdk 26`, `targetSdk`/`compileSdk 34`. BLE requires API 18+ and WiFi Direct requires API 14+, so `minSdk 26` is a deliberate "modern devices only" choice, not a platform floor.

To manually point a debug build at a local DHT bootstrap node during multi-device testing:

```bash
./gradlew :app:assembleDebug -PdhtBootstrapHost=192.168.1.23 -PdhtBootstrapPort=41234
```

## Testing

Each module carries its own JVM unit tests; `:app` additionally has instrumented (`androidTest`) tests for anything touching Room persistence or real Android APIs.

```bash
cd mobile/android

# Fast, JVM-only unit tests across every module
./gradlew test

# Instrumented tests (needs a connected device or emulator)
./gradlew :app:connectedAndroidTest

# A single module's tests, e.g. the DHT layer
./gradlew :dht:test
```

`protocol/`, `crypto/`, `dht/`, and `topics/` are plain-Kotlin JVM modules and can be tested independently of the Android app via `./gradlew :<module>:test` from `mobile/android/` (they're wired in as local project dependencies in `settings.gradle.kts`).

## Documentation map

| Doc | What it's for |
|---|---|
| [docs/memo.md](docs/memo.md) | Full product/business context — investor memo covering problem, thesis, monetization, GTM, org design, and risks |
| [docs/prd.md](docs/prd.md) | Feature-level spec, acceptance criteria, and non-functional requirements |
| [BUILD_PLAN.md](BUILD_PLAN.md) | Execution sequence, phase-by-phase scope, and the reasoning behind open decisions |
| [protocol/WIRE_FORMAT.md](protocol/WIRE_FORMAT.md) | Versioned byte-level wire format for the WiFi Direct transfer frame and socket envelope |
| [docs/adr/0001](docs/adr/0001-crypto-module-placement.md) | Why messaging encryption is its own `/crypto/` module, not folded into `/protocol/` |
| [docs/adr/0002](docs/adr/0002-bootstrap-node-carveout.md) | The narrow, scoped exception allowing address-only bootstrap/rendezvous nodes |
| [docs/adr/0003](docs/adr/0003-cryptographic-decay-and-reach-enforcement.md) | Why decay and reach-tier limits are enforced by key rotation, not client politeness |
| [docs/adr/0004](docs/adr/0004-sybil-resistant-identity.md) | Device-attestation Sybil resistance without accounts |

## Build plan / roadmap

The full phase-by-phase sequence — including which decisions are settled, which are deliberately deferred, and why — lives in [BUILD_PLAN.md](BUILD_PLAN.md). In short:

`Phase 0` feasibility spikes → `Phase 1` MVP (local mesh) → `Phase 2` multi-hop relay → `Phase 3` iOS → `Phase 4` internet mode (DHT) → `Phase 5` trust & safety hardening → `Phase 6` token economy → `Phase 7` GTM pilot.

Phases are sequenced deliberately — e.g. Sybil-resistant identity ships in Phase 1 because faucet caps and moderation signals in later phases are meaningless without it, and the token economy is deliberately last because it has no product value until there's a live relay network and real usage to meter.
