# HOP Build Plan

Status: draft, pre-Phase 0. This turns the [investor memo](docs/memo.md) into an execution sequence. Nothing here is code yet — it's the order of operations and the decisions that have to be made before code starts.

## Guiding constraints (non-negotiable, carried from the memo)

- No company-owned servers in the content or discovery path, ever — not even "temporarily, for the MVP."
- No accounts. Identity is local-device-only from the first line of code.
- Ephemeral decay and "don't relay" propagation control are protocol-layer primitives, not features bolted on later — the memo itself flags that retrofitting takedown/safety behavior into a no-server architecture is far more expensive than designing it in from day one (memo §7).

## Open decisions before Phase 0 starts

These need an explicit answer — each has real downstream cost if revisited later.

1. **Mobile stack: native vs. cross-platform.** BLE background-scanning limits, WiFi Direct, and Multipeer Connectivity are deep, divergent platform APIs. Recommendation: **native (Kotlin/Android first, Swift/iOS second)** — cross-platform frameworks (RN/Flutter) add an abstraction tax on exactly the APIs that are hardest and most platform-specific. Revisit only if a spike shows the platform plugins are solid.
2. **Launch platform order.** Recommendation: **Android first.** BLE + WiFi Direct is one coherent, well-documented stack; iOS's Multipeer Connectivity plus background-scanning restrictions (memo §10 risk) is a separate integration problem worth isolating rather than solving simultaneously.
3. **Video codec/container.** Needs a spike, not a guess — pick for fast mesh transfer over a slow/flaky link (small clip sizes, seekable-enough for preview), not for compression ratio alone. Likely candidate: H.264 in a fragmented MP4, short fixed max clip length to bound transfer time.
4. **Relay/transport protocol framing.** Needs a versioned wire format from day one (clip hash, hop count, decay metadata, "don't relay" signal, sender's ephemeral device ID) since every device on the mesh has to agree on it — this is the one piece hardest to change after multiple independent relay-node implementations exist.

## Phase 0 — Feasibility spikes (throwaway code, ~2-4 weeks)

Goal: de-risk the two things the whole product depends on, before committing to an MVP scope.

- BLE discovery: real-world range, latency to discover a peer, battery cost of continuous advertising/scanning on a mid-range Android device.
- WiFi Direct: real-world throughput and connection-setup time for a ~10-30MB clip between two phones.
- End-to-end spike: phone A records a clip → BLE discovers phone B → WiFi Direct transfers → phone B plays it. No UI polish, no persistence, one hard-coded pair of devices.
- Output: a go/no-go on the throughput and latency numbers, and a first cut of the wire format from decision #4 above.

## Phase 1 — MVP: single-device-pair to small local mesh

Scope: prove the proximity-first loop is a product, not just a protocol.

- Android only. No accounts, no internet mode, no token, no multi-hop relay yet (direct peer-to-peer only).
- Record a short clip → BLE-discover nearby HOP users → WiFi Direct exchange → clips appear in a local, proximity-ordered feed.
- Local-only ephemeral decay: a basic time/distance-based fade rule, even if crude, so the primitive exists from the start rather than being retrofitted (per the non-negotiable above).
- "Done" bar: two strangers in the same room can open HOP, see each other's clips appear, and watch them, with zero setup and zero network dependency.

## Phase 2 — Multi-hop relay

- App-layer store-and-forward: a clip can reach a phone that never had direct radio contact with the originator, via intermediate devices.
- This is where the "don't relay" community propagation-control signal and per-device rate limiting (memo §7) get built — as part of the relay protocol itself, not after.
- QA focus here is explicitly called out in the memo's own org design (Reliability/QA Engineer owns "stress-tests mesh behavior at low density") — low-density behavior (a relay chain that's one hop from breaking) is the actual hard case, not the dense-venue happy path.

## Phase 3 — Second platform (iOS)

- Multipeer Connectivity implementation, protocol-compatible with the Android wire format from Phase 0.
- Explicit design work around iOS background-scanning restrictions (memo §10): UX should assume "open the app to see what's nearby," not ambient background discovery.
- Cross-platform interop test matrix becomes a permanent CI/QA fixture from here on.

## Phase 4 — Internet mode

- DHT (Kademlia-style) for content addressed by hash, no central index.
- IPv6-first connection attempts, peer-assisted NAT hole-punching, volunteer relay-node fallback for symmetric-NAT cases.
- Internet-mode pre-seeding capability (memo §8 Phase 2 GTM) — the tooling to push a starter batch of clips into a new venue/city ahead of local user arrival — is built here, since it depends on internet-mode publishing existing at all.

## Phase 5 — Trust & safety hardening

- On-device hash-matching against known-illegal-content databases before relay (memo §7) — this is a hard launch-blocker, not a nice-to-have, and should be scoped with legal/T&S input, not engineering alone.
- Proof-of-work-gated broadcasting to make spam/flooding costly without account-based penalties.
- This phase should run partly in parallel with Phase 4, not strictly after — a no-server architecture without a takedown path cannot go in front of real users until this exists.

## Phase 6 — Token economy

- Transfer-restricted smart contract (faucets: watch/engage caps, proof-of-relay, content creation; sinks: boost, decay-extension, tipping, sponsored local-channel access).
- Deliberately sequenced last: it has no product value until there's a live relay network and real usage to meter, and building it earlier risks designing tokenomics around guessed rather than observed usage patterns.
- Independent security audit is a hard gate before any token goes live (memo §9 calls this out as a distinct role from the engineer who builds the contract — keep that separation).

## Phase 7 — GTM Phase 1 pilot

- Maps directly to memo §8 Phase 1: a single closed, dense venue (campus, festival, gym chain), chosen for guaranteed physical density rather than broad reach.
- Success metric is local density and repeat engagement within that venue, not download counts — per the memo's own FireChat/Bridgefy cautionary comparison.

## Proposed repo structure (to create at start of Phase 0)

```
/mobile/android/         # native Android app (Kotlin)
/mobile/ios/              # native iOS app (Swift) — Phase 3+
/protocol/                # wire format, relay/decay logic, shared across platforms where possible
/dht/                      # Phase 4+
/contracts/                # token contract — Phase 6+
/tools/relay-node/         # volunteer relay-node tooling — Phase 4+
/tools/preseed/            # internet-mode pre-seeding tooling — Phase 4+
/docs/                     # memo, ADRs, this plan
```

## Risks carried into engineering (from memo §10)

Tracked here so they stay visible during implementation, not just at the planning stage: iOS background-scanning limits, cold-start density, NAT traversal, no central takedown path, regulatory attention on decentralized networks, token security/KYC classification risk, and the BitChat naming collision (needs resolution before any public-facing step, independent of engineering).
