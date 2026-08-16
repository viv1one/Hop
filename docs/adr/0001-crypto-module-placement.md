# ADR 0001: A dedicated `/crypto/` module for messaging encryption

Status: Accepted

## Context

The PRD (docs/prd.md §4.3-4.4, §6) adds 1:1 and group messaging as a core v1 surface, end-to-end encrypted with a Signal Protocol pattern — Double Ratchet for 1:1, sender-key group ratchet for groups — such that no relay node, volunteer-operated or otherwise, can ever read plaintext.

BUILD_PLAN.md's proposed repo structure predates this scope and has no named home for the encryption layer. The candidate was folding it into `/protocol/`, since both are cross-platform-agreed code that every platform and every independent relay-node implementation must interoperate with bit-for-bit, and both get versioned explicitly for the same reason.

Two problems with that: `/protocol/` already carries wire format, relay/decay logic, and geohash-tier resolution — code that engineers touch routinely as mesh/relay features evolve. Encryption code has a different risk profile: a mistake there is a plaintext-exposure incident, not a functional bug, and it's the kind of surface that should get independent security review before it ships (the same posture the org design already applies to the token contract, memo §9). Mixing it into a directory that sees routine relay/decay churn makes it easy for that code to get touched incidentally, and harder to scope a security audit cleanly around just the crypto surface.

## Decision

Encryption lives in its own top-level module, `/crypto/`:

- Owns the Double Ratchet (1:1) and sender-key group ratchet (groups) implementations, plus local key management (identity keys, ratchet state) — shared across platforms exactly like `/protocol/`, versioned explicitly, and requiring every platform/relay-node implementation to agree bit-for-bit.
- `/protocol/` depends on `/crypto/` to encrypt/decrypt message payloads before they hit the wire and to determine what a relay is permitted to touch (ciphertext only). `/crypto/` has no dependency back on `/protocol/` — it should be testable and auditable in isolation, with its own test-vector suite (forward secrecy after simulated compromise, out-of-order/dropped delivery under store-and-forward) independent of relay/decay tests.
- `/crypto/` gets the same extra-scrutiny bar as `/contracts/`: prefer a second pair of eyes on any change, and treat an independent security review before shipping messaging as a gate, not a nice-to-have — mirroring the audit gate already planned for the token contract (memo §9).

### Phase sequencing

BUILD_PLAN.md's phases don't currently mention messaging at all; resolving where the code lives only matters once it's placed on the timeline. Recommendation, to keep messaging development in phase order rather than floating:

- **Phase 1 (MVP):** `/crypto/` work starts here, scoped to 1:1 only. Direct-peer 1:1 chat (Double Ratchet) can ship alongside video in Phase 1 because it only needs the existing direct-peer transport, not multi-hop relay.
- **Phase 2 (multi-hop relay):** group messaging (sender-key ratchet) and store-and-forward message delivery via relay land here, since both depend on the relay infrastructure this phase builds — group ratchet state distribution and offline delivery are relay-shaped problems, not direct-peer ones.

This is a recommendation, not a settled fact the way the module boundary above is — flag it for confirmation before Phase 1 scope is locked, the same way BUILD_PLAN.md's "Open decisions before Phase 0" items are recommendations pending sign-off.

## Consequences

- `/crypto/` and `/protocol/` are separate directories with a one-way dependency (`protocol/` → `crypto/`); a PR that adds a reverse dependency is a design smell worth stopping on.
- Security review process needs to name `/crypto/` explicitly as in scope before Phase 1 messaging ships, not discover it needs review after the fact.
- BUILD_PLAN.md's proposed repo structure and Phase 1/2 scope are updated to reflect this (see BUILD_PLAN.md).
