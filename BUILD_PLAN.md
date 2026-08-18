# HOP Build Plan

Status: draft, pre-Phase 0. This turns the [investor memo](docs/memo.md) into an execution sequence. Nothing here is code yet — it's the order of operations and the decisions that have to be made before code starts.

## Guiding constraints (non-negotiable, carried from the memo)

- No company-owned servers in the content or discovery path, ever — not even "temporarily, for the MVP." **Narrow carve-out (ADR 0002):** address-only bootstrap/rendezvous nodes for cold-start peer discovery are permitted — they hold no content, no clip hashes, no topic subscriptions, and are phased out via peer exchange once a device has any live peer. This is the one exception to the constraint, and it's scoped tightly enough that it can't become a discovery or content chokepoint; see the ADR before treating any other network call as covered by this exception.
- No accounts. Identity is local-device-only from the first line of code, bound to a hardware attestation token (Play Integrity / App Attest) rather than left as free-floating soft state — attestation proves "one real device," never who owns it, and needs no HOP-operated verification server (ADR 0004). This exists because several other primitives below (faucet caps, "don't relay" signal counting, blocking) don't work at all against a free identity.
- Ephemeral decay and "don't relay" propagation control are protocol-layer primitives, not features bolted on later — the memo itself flags that retrofitting takedown/safety behavior into a no-server architecture is far more expensive than designing it in from day one (memo §7). Decay is enforced by decryption-key expiry, not client politeness alone, and "don't relay" signal-counting is gated by attested device identity plus proof of local receipt — both are Sybil-able otherwise (ADR 0003, ADR 0004).

## Open decisions before Phase 0 starts

These need an explicit answer — each has real downstream cost if revisited later.

1. **Mobile stack: native vs. cross-platform.** BLE background-scanning limits, WiFi Direct, and Multipeer Connectivity are deep, divergent platform APIs. Recommendation: **native (Kotlin/Android first, Swift/iOS second)** — cross-platform frameworks (RN/Flutter) add an abstraction tax on exactly the APIs that are hardest and most platform-specific. Revisit only if a spike shows the platform plugins are solid.
2. **Launch platform order.** Recommendation: **Android first.** BLE + WiFi Direct is one coherent, well-documented stack; iOS's Multipeer Connectivity plus background-scanning restrictions (memo §10 risk) is a separate integration problem worth isolating rather than solving simultaneously.
3. **Media codec/container: video and photo — SETTLED via real-device spike.** Real camera-recorded videos pulled from two actual Android phones (Motorola Edge 50 Fusion, Moto G45 5G) and inspected with `ffprobe`: both produce 1080p30 MP4 at ~20-22 Mbps combined bitrate, but on *different* video codecs — one HEVC (H.265) Main profile, the other H.264 High profile. Since v1 has no in-app camera (upload-from-device-media only, per PRD §4.1/§8), HOP never controls the source encode — it receives whatever the OS/OEM camera app already produced, so **the app must decode whatever it's handed, not standardize on one codec**. This needs no HOP-side work: Android's stock video player (used as-is for viewing, no custom player in v1) already decodes both natively. Container is a non-issue too — both real samples are already fast-start (the `moov` atom precedes `mdat`), so clips are seekable/streamable with no remux step.

   The spike surfaced a real, scope-relevant finding: at ~20-22 Mbps native bitrate, PRD §4.1's nominal "under 60 seconds" target implies a ~150-165MB file, which transfers in ~9-11s at this repo's own measured real WiFi Direct throughput (120-150 Mbps between two phones, see Phase 0 results below) — missing the §7 NFR of "low single-digit seconds." A real ~15.4s sample measured 42.5MB, transferring in ~2-3s at the same throughput — inside the NFR. **Settled max clip length for v1: ~15 seconds**, enforced client-side by rejecting a picked video that exceeds it at upload time (implemented in the Phase 0 spike's media-pick flow) rather than by transcoding — there's no encode step to bound at capture time without an in-app camera, and re-encoding an already-compressed clip is real complexity worth deferring past v1. **This is a real, data-backed tension with PRD §4.1's stated "under 60 seconds" — needs reconciling there, not just here, before Phase 1 locks the posting flow.**

   Photo: **JPEG**, confirmed without a separate spike. Real picked photos in this repo's own end-to-end testing were 370KB-1MB and transferred in tens of milliseconds — never close to a bottleneck. HEIC/AVIF's smaller size isn't worth its cross-platform/older-Android decode risk for v1.
4. **Relay/transport protocol framing.** Needs a versioned wire format from day one (post hash, content-type flag distinguishing photo from video, hop count, decay metadata, "don't relay" signal, sender's ephemeral device ID) since every device on the mesh has to agree on it — this is the one piece hardest to change after multiple independent relay-node implementations exist.
5. **WiFi Direct at N-peer density.** The GTM strategy (memo §8) specifically targets dense venues, but WiFi Direct group ownership generally supports one active group per device at a time — the actual bottleneck case is untested by a two-device spike. Needs its own de-risking pass in Phase 0 (below) before the dense-venue GTM bet is load-bearing; if the numbers are bad, evaluate Wi-Fi Aware (NAN) or rotating ad-hoc hotspot as a supplement.

## Phase 0 — Feasibility spikes (throwaway code, ~2-4 weeks)

Goal: de-risk the two things the whole product depends on, before committing to an MVP scope.

- BLE discovery: real-world range, latency to discover a peer, battery cost of continuous advertising/scanning on a mid-range Android device.
- WiFi Direct: real-world throughput and connection-setup time for a ~10-30MB clip between two phones. Photo transfer isn't a separate risk here — it's a much smaller payload than video and should fall out of the same spike essentially for free.
- WiFi Direct at density: connection/rotation behavior with 5-20 simultaneous nearby devices in one location, not just a pair — this directly de-risks decision #5 above and the dense-venue GTM thesis (memo §8), and is currently the single biggest untested assumption behind the go-to-market strategy.
- End-to-end spike: phone A uploads a post (photo or clip) from its media library → BLE discovers phone B → WiFi Direct transfers → phone B views it. No UI polish, no persistence, no camera integration (that's a later, app-layer-only addition — PRD §4.1), one hard-coded pair of devices.
- Output: a go/no-go on the throughput and latency numbers, and a first cut of the wire format from decision #4 above.
- **Deliberately not de-risked here:** App Store/Play Store UGC policy risk (Apple guideline 1.2 in particular) — see Phase 7 for the policy spike and the tradeoff this deferral accepts.

## Phase 1 — MVP: single-device-pair to small local mesh

Scope: prove the proximity-first loop is a product, not just a protocol.

- Android only. No accounts, no internet mode, no token, no multi-hop relay yet (direct peer-to-peer only).
- **Real UI, not the Phase 0 spike harness.** The two-tab feed + inbox (PRD §5) should be a genuine Instagram-style build — full-screen swipeable feed modeled closely on Reels, inbox modeled on Instagram DMs, not just "simple like it" in spirit. The Phase 0 spike's button-list screen is diagnostic tooling and gets replaced here, not incrementally reskinned — build the real thing against the mesh/protocol code the spike already validated, rather than layering polish onto throwaway code.
- Device identity is bound to a hardware attestation token (Play Integrity, ADR 0004) as part of first-run setup — silent to the user, but needed from day one since retrofitting it under an installed base later is expensive.
- Upload a short video or photo from the device's media library (no in-app camera yet — see below) → BLE-discover nearby HOP users → WiFi Direct exchange → posts appear in a local, proximity-ordered feed. Content is encrypted at rest with a key on the decay/tier schedule from ADR 0003, even though Phase 1's decay rule is still crude — get the key-wrapping shape right now so it doesn't need a breaking wire-format change later.
- In-app camera capture (shoot a photo/video directly within HOP instead of uploading an existing file) is explicitly **not** in this phase, or pinned to any specific phase — it's a self-contained app-layer UX feature with no protocol dependency, so it can be scheduled whenever it's prioritized post-MVP (PRD §4.1, §8) without blocking or being blocked by the phases below.
- Local-only ephemeral decay: a basic time/distance-based fade rule enforced via key expiry (ADR 0003), even if the schedule itself is crude at this stage — the primitive and its enforcement mechanism both exist from the start rather than being retrofitted (per the non-negotiable above).
- 1:1 encrypted messaging (Double Ratchet, `/crypto/`) starts here, direct-peer only — no relay dependency, per ADR 0001. A user can start a private chat from a post without a phone number (PRD §4.4). Local block list ships alongside it (PRD §4.4) — a report/block UI without a working block mechanism underneath isn't worth shipping.
- **This phase's "done bar" is also the earliest point a real-user soft pilot can run** (memo §8 sequencing note) — it doesn't need to wait for Phase 6's token economy. Treat GTM validation at a single dense venue as unblocked once this phase's done bar is met, not gated behind Phase 7's formal numbering.
- "Done" bar: two strangers in the same room can open HOP, see each other's posts appear, and view them, with zero setup and zero network dependency.

## Phase 2 — Multi-hop relay

- App-layer store-and-forward: a post can reach a phone that never had direct radio contact with the originator, via intermediate devices.
- This is where the "don't relay" community propagation-control signal and per-device rate limiting (memo §7) get built — as part of the relay protocol itself, not after. Signal-counting and rate limits are gated by the attested device identity from Phase 1, plus proof of local receipt/playback for "don't relay" flags — without that, both are free to fake with disposable identities (ADR 0004). Ship this as a **flat threshold** (N distinct attested-and-verified flags suppresses a post) — don't build weighted trust-tiering now. That's a deliberately deferred refinement (ADR 0004) to design once there's real flagging data to tune it against, not a v1 requirement.
- **Relay-incentive gap:** volunteer relay nodes are needed starting this phase, but the token economy that would reward operators (proof-of-relay faucet) isn't built until Phase 6. Close the gap two ways: (a) ship a simple, non-tradeable local points counter now, redeemable/importable into the real token ledger once Phase 6 ships, so relay operators get visible credit from day one; (b) treat early relay capacity as seeded by launch-venue partnerships (a venue hosting a relay node as part of its sponsorship deal, memo §6) rather than assuming organic volunteer supply exists before there's any product traction.
- Group messaging ships as per-member pairwise Double Ratchet fan-out (PRD §4.3), not a full group ratchet — the latter needs a delivery-ordering design this phase doesn't have yet. Store-and-forward delivery for offline 1:1 recipients lands here too, since both depend on the relay infrastructure this phase builds (ADR 0001). Relay nodes carry ciphertext only, and hold undelivered messages only for the bounded retention window from PRD §4.4.
- QA focus here is explicitly called out in the memo's own org design (Reliability/QA Engineer owns "stress-tests mesh behavior at low density") — low-density behavior (a relay chain that's one hop from breaking) is the actual hard case, not the dense-venue happy path.

## Phase 3 — Second platform (iOS)

- Multipeer Connectivity implementation, protocol-compatible with the Android wire format from Phase 0.
- Explicit design work around iOS background-scanning restrictions (memo §10): UX should assume "open the app to see what's nearby," not ambient background discovery.
- Cross-platform interop test matrix becomes a permanent CI/QA fixture from here on.

## Phase 4 — Internet mode

- DHT (Kademlia-style) for content addressed by hash, no central index.
- Bootstrap/rendezvous nodes for cold-start peer discovery (ADR 0002) — address-only, zero content or topic visibility. **Launch condition, not optional:** recruit at least one non-HOP-operated bootstrap node before this phase ships publicly, so HOP isn't the sole source of first contact.
- Geohash-prefix topic resolution checks the target cell plus its neighbor cells (PRD §6) to avoid boundary-edge misses between adjacent cells.
- Reach-tier key-wrapping (ADR 0003) goes live here for Town/City/Country tiers — this is what gives the reach-radius promise real (if not absolute) teeth once content leaves local mesh; ship it alongside DHT publishing, not after.
- IPv6-first connection attempts, peer-assisted NAT hole-punching, volunteer relay-node fallback for symmetric-NAT cases.
- Internet-mode pre-seeding capability (memo §8 Phase 2 GTM) — the tooling to push a starter batch of clips into a new venue/city ahead of local user arrival — is built here, since it depends on internet-mode publishing existing at all.

## Phase 5 — Trust & safety hardening

- On-device hash-matching against known-illegal-content databases, checked on both send and receipt (memo §7) — this is a hard launch-blocker, not a nice-to-have, and should be scoped with legal/T&S input, not engineering alone.
- Proof-of-work-gated broadcasting to make spam/flooding costly without account-based penalties, backed by the device attestation from Phase 1.
- **Legal gate — block on outside counsel sign-off before this phase ships, don't resolve internally:** secondary liability for user-distributed copyrighted content (Napster/Grokster-pattern exposure, memo §7) — get an outside IP counsel opinion before any public launch; the rights-holder hash-block channel and the ≤60s clip-length ceiling are engineering-side mitigations, not a substitute for the legal opinion.
- **Deferred, tracked separately:** illegal-content reporting obligations (e.g. NCMEC CyberTipline, 18 U.S.C. §2258A in the US) — hash-matching alone doesn't establish compliance with mandatory reporting duties (memo §7). This is parked as a named open item to scope with specialist counsel once the team has bandwidth to act on the answer, not a blocker on this phase's engineering scope.
- This phase should run partly in parallel with Phase 4, not strictly after — a no-server architecture without a takedown path cannot go in front of real users until this exists.

## Phase 6 — Token economy

- Transfer-restricted smart contract (faucets: watch/engage caps, proof-of-relay, content creation; sinks: boost, decay-extension, tipping, sponsored local-channel access).
- Faucet caps enforced per hardware-attested device key (ADR 0004), not per soft local identity — otherwise they're free to farm at scale via reinstalls/emulators.
- Boost sink uses a diminishing-returns cost curve with a hard per-post ceiling relative to organic reach, so paid boost can shift ranking but can't fully drown out organic proximity content (memo §5, §6) — this is what keeps "transparent boost, not opaque algorithm" true rather than aspirational.
- Sponsored local-channel access is always disclosed/labeled as sponsored in the UI, never presented as organic (memo §6).
- Contract governance: a time-locked multisig (48-72h public timelock) can upgrade faucet/sink logic; it has no pause, freeze, or blacklist power over existing user balances under any circumstance (memo §6) — this is the concrete answer to "who controls the contract," not left open.
- Deliberately sequenced last: it has no product value until there's a live relay network and real usage to meter, and building it earlier risks designing tokenomics around guessed rather than observed usage patterns. Note the local-points bridge from Phase 2 exists precisely so relay incentives don't sit unaddressed for four phases waiting for this one.
- Independent security audit is a hard gate before any token goes live (memo §9 calls this out as a distinct role from the engineer who builds the contract — keep that separation).

## Phase 7 — GTM Phase 1 pilot (full commercial rollout)

- Maps directly to memo §8 Phase 1: a single closed, dense venue (campus, festival, gym chain), chosen for guaranteed physical density rather than broad reach — now with the token economy, sponsorship, and boost mechanics live.
- Distinct from the **informal soft pilot** that can and should run as early as Phase 1/2's done bar (see Phase 1 above, memo §8 sequencing note) — this phase is the token-economy-complete, multi-venue-ready version, not the first time real users touch the product. Don't read the phase number as "when GTM validation starts."
- App Store / Play Store policy spike: a minimal TestFlight/Play Console submission to surface content-moderation policy objections (Apple's UGC guideline 1.2 in particular). Originally scoped for Phase 0 as a cheap early check ("expensive to discover late," memo §10); deliberately moved here instead — this trades early, cheap de-risking for a submission that reflects the real product, accepting that a late policy rejection is now costlier to redesign around than it would have been in Phase 0.
- Success metric is local density and repeat engagement within that venue, not download counts — per the memo's own FireChat/Bridgefy cautionary comparison.

## Proposed repo structure (to create at start of Phase 0)

```text
/mobile/android/         # native Android app (Kotlin)
/mobile/ios/              # native iOS app (Swift) — Phase 3+
/protocol/                # wire format, relay/decay logic, geohash-tier (+ neighbor-cell) resolution, reach-tier key-wrapping policy, shared across platforms where possible
/crypto/                   # messaging encryption (Double Ratchet 1:1, pairwise fan-out for groups), decay/reach-tier key management, device-attestation identity — shared cross-platform, one-way dependency from protocol/ (ADR 0001, ADR 0003, ADR 0004) — Phase 1+
/rendezvous/               # bootstrap/rendezvous nodes — address-only, zero content/topic visibility (ADR 0002) — Phase 0/4+, tracked separately from dht/ so its minimal scope is enforced by code structure
/dht/                      # Phase 4+
/contracts/                # token contract — Phase 6+
/tools/relay-node/         # volunteer relay-node tooling — Phase 4+
/tools/preseed/            # internet-mode pre-seeding tooling — Phase 4+
/docs/                     # memo, PRD, ADRs, this plan
```

See [docs/adr/0001-crypto-module-placement.md](docs/adr/0001-crypto-module-placement.md) for the reasoning behind splitting `/crypto/` out of `/protocol/`, [docs/adr/0002-bootstrap-node-carveout.md](docs/adr/0002-bootstrap-node-carveout.md) for the `/rendezvous/` carve-out, [docs/adr/0003-cryptographic-decay-and-reach-enforcement.md](docs/adr/0003-cryptographic-decay-and-reach-enforcement.md) for decay/reach-tier key-wrapping, and [docs/adr/0004-sybil-resistant-identity.md](docs/adr/0004-sybil-resistant-identity.md) for device-attestation identity.

## Risks carried into engineering (from memo §10)

Tracked here so they stay visible during implementation, not just at the planning stage: iOS background-scanning limits, cold-start density, NAT traversal, no central takedown path (mitigated but not eliminated by ADR 0003/0004 — see the honesty note in memo §7), regulatory attention on decentralized networks, token security/KYC classification risk, the BitChat naming collision (needs resolution before any public-facing step, independent of engineering), WiFi Direct's per-device group-connection limits at dense-venue density (untested until the Phase 0 N-peer spike), the bootstrap-node carve-out's inherent tension with the "no server in the discovery/content path" non-negotiable (scoped and bounded by ADR 0002, but worth re-reading if any future change touches `/rendezvous/`), mandatory illegal-content reporting obligations that may apply regardless of architecture (deferred — tracked as a named open item to resolve with counsel later, not a current blocker), secondary copyright liability from the Napster/Grokster precedent (legal gate before public launch, tracked alongside Phase 5), and App Store/Play Store UGC policy objections, particularly Apple guideline 1.2 (deliberately deferred from a cheap Phase 0 check to a Phase 7 submission against the real product — see Phase 7 for the tradeoff this accepts).
