# ADR 0003: Decay and reach-tier limits enforced by key rotation, not politeness alone

Status: Accepted

## Context

Two of the product's headline promises turn out to be unenforceable as originally specified, once you account for how content-addressed DHT storage actually behaves:

1. **"Ephemeral decay"** (memo §4.4, §7) is described as content fading over time/distance. But content is addressed by hash and stored in a DHT — any node that has cached it can keep serving it indefinitely regardless of what the reference client's feed shows. "Decay" as specified is a default-client UX convention, not a guarantee, which also undermines the "don't relay" moderation story (memo §7): halting propagation in the reference client doesn't remove a hash from the DHT.
2. **Reach-tier restriction above Locality** (PRD §4.2, §4.1 acceptance) relies on geohash-prefix DHT topics. Topic keys aren't secret — any client, honest or not, can subscribe to any topic. A post tagged "Town" is technically fetchable by anyone worldwide who queries that topic, making the tier boundary a convention respected by the stock app, not an access-controlled guarantee.

Both gaps share a root cause: the plan conflated "the reference client won't offer this" with "this cannot be obtained." Those are different claims, and only the first is true of a P2P DHT architecture by default.

## Decision

Close the gap the way DRM-adjacent systems always do — imperfectly, but with real teeth — by moving from *behavioral* enforcement to *key-based* enforcement:

- **Clip payloads are encrypted at rest**, not just tagged with a geohash. The key needed to decrypt a clip is wrapped separately per reach tier (Locality/Town/City/Country) and per decay window, not attached to the clip itself.
- **Decay window:** the tier-appropriate decryption key is only distributed by honest clients for a bounded time/hop-count window (the existing decay parameters, now load-bearing instead of cosmetic). Past that window, no honest client will hand out the unwrap key for a fresh viewer — the ciphertext may still exist in the DHT, but it's opaque to anyone who didn't fetch the key while it was live. Re-sharing within the window re-extends key availability, consistent with the existing "decay unless re-shared" model.
- **Reach tier:** a viewer must present a valid tier-membership claim (a locally-verifiable geohash-prefix + timestamp assertion, not a server-verified one — no server exists to ask) to a peer before that peer will hand over the unwrap key for a Town/City/Country-tagged clip. This stops casual/default-client scraping of out-of-tier content even though it can't stop a determined attacker who fabricates a location claim — see Limits below.
- **Locality is unaffected** — it already never touches the DHT (ADR 0001, ADR 0002) and needs no key-wrapping scheme; the ciphertext and key both stay on local mesh only.

## Limits — state these in every doc that references decay or reach tiers

This is a deterrence mechanism, not a cryptographic impossibility proof, and the docs should say so plainly rather than imply an absolute guarantee:

- A determined attacker who captured the unwrap key while it was live can keep decrypting after the decay window closes — same as screen-recording a Snapchat story. Decay raises the cost of persistence; it doesn't make it impossible.
- A determined attacker who fabricates a location claim can obtain out-of-tier content — location self-assertion has no server to verify it against. Reach-tier enforcement raises the cost of casual/automated scraping (a stock client won't do it, and doing it manually doesn't scale for free); it is not equivalent to server-side access control.
- PRD acceptance criteria for §4.1/§4.2 should be reworded from absolute ("visible only to...") to accurate ("the reference client will only surface/decrypt... for peers presenting a valid tier claim within the decay window").

## Consequences

- Clip payload format now needs a key-wrapping layer — this is protocol-level (`protocol/` for the topic/tier/decay logic that decides key distribution, `crypto/` for the actual wrapping primitive, consistent with ADR 0001's boundary).
- Decay stops being a pure feed-ranking heuristic and becomes a real distributed key-lifecycle problem: key rotation schedule, re-share key re-extension, and clock/hop-count sync across a partitioned mesh all need design work before Phase 1's "basic time/distance-based fade rule" can honestly be called anything more than a placeholder. Treat the crude Phase 1 version explicitly as a placeholder, not the target design.
- PRD §4.1, §4.2, and memo §4.4/§7 acceptance language should be corrected to match the Limits section above rather than overpromise.
