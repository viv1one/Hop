---
name: hop-dev
description: HOP-specific development conventions — architecture invariants, module boundaries, wire-format discipline, and testing approach for this serverless proximity-mesh video app. Load when writing, reviewing, or planning code changes anywhere in this repo.
---

# HOP development conventions

HOP is a no-server, no-account, proximity-first mesh video app (see [../../../docs/memo.md](../../../docs/memo.md) for product context and [../../../BUILD_PLAN.md](../../../BUILD_PLAN.md) for phase sequencing). These conventions exist because this architecture has failure modes that are expensive to fix after the fact — most of this file is about catching those early.

## Invariant checks before any change lands

Treat these as blocking, not stylistic:

1. **No HOP-owned server in the content or discovery path.** If a change adds a network call to any HOP-controlled endpoint for discovery, transfer, feed ranking, or moderation, stop and flag it — that's an architecture violation, not a detail to fix later. Volunteer-operated relay nodes and public DHT infrastructure are fine; anything HOP operates or controls is not.
2. **No accounts.** No phone number/email/password field, no server-held profile, no persistent cross-device identity. Local device identity only.
3. **Decay and "don't relay" are protocol-layer, not app-layer.** If you're implementing relay or feed logic, the decay/propagation-control hooks belong in the wire protocol and relay code, not bolted on in the UI layer — the memo explicitly calls out that retrofitting this later is far more expensive (memo §7).

## Build in phase order

Check [BUILD_PLAN.md](../../../BUILD_PLAN.md) before adding a feature. Don't build Phase N+2 primitives while Phase N is unfinished — e.g., no token/contract code before local mesh + relay (Phases 1-2) are solid, no DHT/internet-mode code before both platforms' local mesh works. Building ahead of the current phase tends to lock in assumptions the earlier phase would have invalidated.

## Module boundaries

Per the proposed repo structure in BUILD_PLAN.md:

- `protocol/` — wire format, relay/decay logic. This is the one thing every platform and every independent relay-node implementation must agree on. Changes here are the highest-cost-to-get-wrong; version the wire format explicitly and never break backward compatibility silently.
- `mobile/android/`, `mobile/ios/` — platform apps. Keep platform-specific radio/background-scanning quirks (BLE limits, Multipeer Connectivity restrictions) isolated here, not leaked into `protocol/`.
- `dht/`, `contracts/`, `tools/relay-node/`, `tools/preseed/` — later-phase modules; don't add dependencies on these from Phase 1-3 code paths.

## Testing approach for mesh/P2P code

- The hard case is **low-density, near-broken mesh** (a relay chain one hop from failing), not the dense-venue happy path — the memo's own org design assigns a QA role specifically to stress-test this. Prioritize tests here over adding more happy-path coverage.
- Prefer integration tests that simulate multiple peers with realistic radio constraints (limited range, intermittent connectivity, asymmetric discovery) over unit tests that mock the transport layer entirely — mocked transport tests won't catch the failure modes that matter for this product.
- Any change to `protocol/` needs a cross-version compatibility test once more than one wire-format version exists.

## Extra scrutiny areas

These touch safety, money, or irreversible protocol decisions — hold changes here to a higher bar and prefer a second pair of eyes:

- On-device hash-matching / content moderation logic (Phase 5).
- Anything in `contracts/` — the token is transfer-restricted by design; a change that weakens that restriction is a regulatory exposure, not just a bug (memo §6, §10).
- NAT traversal / relay-node trust logic (Phase 4) — volunteer-operated infrastructure means you can't assume relay nodes are honest.
