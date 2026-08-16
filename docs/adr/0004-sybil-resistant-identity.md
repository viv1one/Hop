# ADR 0004: Device-attestation Sybil resistance, without accounts

Status: Accepted

## Context

"No accounts, identity is local-device-only" is a non-negotiable (CLAUDE.md, memo §3). But free identity creation breaks three things that were specified as if identity had some cost:

1. **Token faucets** (memo §6, PRD §4.5): "watching/engaging capped daily, to prevent farming" — capped per what? A free local identity can be reset or multiplied (emulators, burner installs) at near-zero cost, making any per-identity daily cap trivially farmable at scale.
2. **"Don't relay" propagation control** (memo §7, PRD §4.6): "a signal from enough nearby peers halts a clip's spread" — with free identity, one person with five phones (or scripted BLE presence) satisfies "enough nearby peers," turning the one moderation primitive into a harassment/censorship tool as easily as an abuse deterrent.
3. **Blocking/safety** (identified separately, see PRD update): a device-local block list is meaningless if the blocked party can just reinstall and get a new identity for free.

The three problems share a root cause: "no accounts" was implemented as "no identity cost at all," when what the product actually needs is "no *company-held, cross-app-linkable* identity" — a narrower and achievable property.

## Decision

Bind each local identity to a **hardware attestation token** — Android Play Integrity API / iOS App Attest — instead of leaving it free-floating:

- **What it is:** a cryptographic attestation, signed by the OS/platform vendor (Google/Apple), that a given key pair was generated in a genuine device's secure hardware and hasn't been tampered with. It proves "this is one real, non-emulated physical device," not who owns it — no name, phone number, or email involved, and HOP never sees anything beyond a pass/fail attestation plus a device-bound public key.
- **Why this doesn't violate the non-negotiable:** the constraint is "no accounts" and "no server-held profile" — attestation requires no HOP-operated verification server (Play Integrity/App Attest are verified against Google/Apple's infrastructure, or verified locally via signed tokens depending on API), creates no persistent cross-app or cross-reinstall identity (a factory-reset or new device gets a new attestation, same as today), and stores nothing about the user, only a proof about the hardware. It raises the cost of Sybil identity from "free" to "own another physical device," without reintroducing accounts.
- **Where it's used:**
  - Token faucet caps are enforced per attested-device-key, not per soft local identity, closing the trivial-farming gap.
  - "Don't relay" suppression requires flags from a minimum number of distinct attested devices, combined with a proof-of-local-play requirement (a flag must come from a device that can show it actually received/played the clip locally, not a remote/scripted flag) — raising Sybil-brigading cost from "free" to "N real, physically-present phones."
  - The local block list (PRD update, harassment mitigation) keys off the attested device identity: blocking survives app reinstall (same hardware re-attests to related state where platform APIs allow it) but not a new physical device — an explicit, acceptable limit, stated plainly rather than oversold as unbreakable.
- **Explicit non-goals:** this is not identity verification, not a real-name system, and not a mechanism HOP or anyone else can use to deanonymize a user. It answers "is this one real device," nothing more.

## Future direction, deliberately deferred: weighted trust instead of a flat threshold

v1's "don't relay" rule is a flat count: N distinct attested devices with local-receipt-proof suppresses a clip. That's deliberately simple and easy to reason about, but every flag counts equally regardless of the flagger's track record.

A better long-run version weights flags by an earned trust signal, closer to Wikipedia's trusted-editor tiers or a PGP-style web of trust: trust accrues from longevity plus reciprocal trust from other already-trusted peers, **not from raw post/activity volume** (that metric was already rejected earlier in this workstream — it's gameable by exactly the same low-effort-spam pattern that threatens the token faucets) and **not as a privileged override role** (no peer gets unilateral suppression power — it only adjusts how much a given flag counts toward the existing threshold, advisory weighting, not authority). This keeps it from recreating the "central-ish takedown authority" problem a tier of privileged "leaders" would.

This is explicitly **not specified further here on purpose** — the right weighting curve depends on real flagging patterns (false-positive rates, brigading attempts, how trust actually correlates with accuracy) that don't exist yet at concept stage. Design it once Phase 2's flat-threshold version is live and has real usage data to tune against, not by guessing now. The flat threshold is forward-compatible with this: weighting is a local scoring change on top of the same attested-flag data, not a wire-format change, unless reciprocal-trust computation ends up needing peers to exchange trust assertions over the wire — if it does, that follows the same protocol-versioning discipline as any other `protocol/` change.

## Limits

- A motivated bad actor can still buy multiple physical devices — attestation raises the cost of abuse, it doesn't make it impossible. State this in the same places ADR 0003 states its limits, rather than implying a solved problem.
- Both Play Integrity and App Attest require Google Play Services / genuine Apple hardware respectively — rooted/jailbroken or de-Googled devices may fail attestation. Decide (open question, not resolved here) whether such devices get reduced trust weight (e.g., excluded from faucet rewards and "don't relay" flag-counting but still allowed to browse/post) rather than being blocked outright, to avoid excluding privacy-conscious users who are exactly this product's target audience.
- Attestation tokens themselves need periodic refresh and have platform-specific rate limits/quotas — a Phase 1/2 engineering spike item, not a given.

## Consequences

- `crypto/` (or a new `identity/` submodule under it) now owns device-key generation and attestation-token handling, in addition to the ratchet implementations from ADR 0001.
- Token faucet design (Phase 6) and "don't relay"/rate-limiting design (Phase 2) both now have a stated Sybil-resistance dependency — call it out explicitly in BUILD_PLAN so neither phase ships its abuse controls without it.
- The rooted/de-Googled-device tradeoff above is an explicit open question for the Trust & Safety workstream, not silently resolved.
