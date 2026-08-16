# HOP

Serverless, proximity-first short-form photo/video and messaging. No company-owned servers, no user accounts, no centralized algorithmic feed. Posts (photo or video) and encrypted messages move phone-to-phone via BLE discovery + WiFi Direct (and, in later phases, a P2P/DHT internet mode), with reach chosen per-post across geohash-based tiers (Locality → Town → City → Country), and decay over time/distance unless re-shared. v1 posting is upload-from-device-media only; in-app camera capture is a planned future addition (docs/prd.md §4.1).

Stage: concept stage, early Phase 0/1 scaffolding started under `mobile/android/`.

- Full product/business context: [docs/memo.md](docs/memo.md)
- Feature-level spec, acceptance criteria, and NFRs: [docs/prd.md](docs/prd.md)
- Execution sequence and phase-by-phase scope: [BUILD_PLAN.md](BUILD_PLAN.md)
- Architecture decisions closing known gaps in the above: [docs/adr/](docs/adr/) (0001 crypto module placement, 0002 bootstrap-node carve-out, 0003 cryptographic decay/reach-tier enforcement, 0004 Sybil-resistant identity)

## Non-negotiable constraints

- No HOP-owned server ever sits in the content, discovery, or message-delivery path. **Narrow carve-out (ADR 0002):** address-only bootstrap/rendezvous nodes for cold-start peer discovery are permitted — zero content, zero topic visibility, phased out via peer exchange. Don't treat this as license to add any other network call; read the ADR before assuming something else qualifies.
- No accounts — identity is local-device-only, bound to a hardware attestation token (Play Integrity / App Attest) that proves "one real device," never who owns it (ADR 0004). This is what makes faucet caps, "don't relay" signal-counting, and blocking mean anything against free reinstalls — treat it as part of "no accounts," not a contradiction of it.
- Ephemeral decay and community "don't relay" propagation control are protocol primitives designed in from Phase 1, not retrofitted later (see BUILD_PLAN.md Phase 2 and memo §7) — enforced by decryption-key expiry and attested-identity-gated signal counting (ADR 0003, ADR 0004), not client politeness alone. Both are a strong deterrent for the honest-client population, not an absolute guarantee against a determined custom client — state that limit plainly in anything user- or investor-facing rather than implying it away.
- 1:1 and group messages are end-to-end encrypted (Signal Protocol pattern) such that no relay node — volunteer-operated or otherwise — can ever read plaintext content (see docs/prd.md §4.3-4.4).

## Working on this repo

See the `hop-dev` skill for architecture/module conventions and the `hop-engineer` agent for delegating mesh/protocol implementation work once code exists. See the `mobile-ui-design` skill when building the real UI (Phase 1's Instagram-style feed + inbox, BUILD_PLAN.md Phase 1) — not the Phase 0 spike harness, which is deliberately unpolished throwaway code.
