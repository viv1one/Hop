# ADR 0002: A narrow, explicit carve-out for DHT/mesh bootstrap rendezvous

Status: Accepted

## Context

The non-negotiable stated in CLAUDE.md, memo.md, and BUILD_PLAN.md is "no HOP-owned server ever sits in the content or discovery path." Taken literally, this is unbuildable: every DHT-based P2P network needs a small set of well-known nodes to solve first-contact — Kademlia-style DHTs (used by BitTorrent, IPFS) need bootstrap peers, and even Tor, the reference example this plan cites for volunteer-operated infrastructure, relies on a small set of centrally-run directory authorities to bootstrap its otherwise-volunteer relay network. Without an equivalent, Phase 4 (internet mode) and any fresh install of the local-mesh client have no way to find a first peer.

This is a real conflict between the stated constraint and physical necessity, not a detail to paper over. Resolving it requires being precise about what "in the discovery path" means, because a bootstrap node and a discovery/content server are not the same thing.

## Decision

A small, explicit exception is carved out: HOP may operate (or fund third parties to operate) **bootstrap/rendezvous nodes**, strictly scoped as follows:

- **What they hold:** peer addresses only (IP/transport info of currently-reachable peers). Zero content, zero clip hashes, zero geohash-topic subscriptions, zero message routing. A bootstrap node cannot answer "what's near me" or "who has this clip" — only "here are some peers to talk to."
- **What they don't do:** they are never consulted after a client has any live peer connection. Once a device has one peer (via bootstrap, BLE, or prior session), it uses ordinary peer exchange (PEX) — asking connected peers for more peers — the same way BitTorrent clients wean off trackers. Bootstrap nodes are a cold-start convenience, not a standing dependency.
- **Why this isn't a constraint violation:** the non-negotiable exists to prevent HOP from being a point of control or surveillance over content, discovery results, or relay decisions — a takedown lever or a data-harvesting choke point. A peer-address rendezvous list is neither: it can't see or influence what content exists, what's discoverable, or what gets relayed, and it's trivially replaceable (see below). Compare to Bitcoin's DNS seed nodes or IPFS's bootstrap list — both are widely accepted as compatible with those networks' decentralization claims for the same reason.
- **Reduce reliance over time, and diversify who runs it:** as the local mesh (Phase 1-2) matures, PEX through already-connected mesh peers should reduce how often bootstrap nodes are hit at all. Before Phase 4 (internet mode) ships publicly, recruit at least one independent third-party operator for a bootstrap node (mirroring Bitcoin's multiple independent DNS seed operators) so HOP is not the sole source of first contact — this is a launch condition for Phase 4, not a someday-nice-to-have.

## Consequences

- CLAUDE.md's and memo.md's non-negotiable wording is amended to state the carve-out explicitly, rather than leaving a literal reading that the architecture already violates on day one.
- Bootstrap-node code is a new, narrowly-scoped piece of infrastructure — track it separately from `protocol/`/`dht/` content logic so its minimal blast radius (addresses only) is enforced by code structure, not just policy. Any change that would let a bootstrap node answer a content or topic query is a design violation of this ADR, not a feature.
- Phase 4 (internet mode) gets an explicit launch condition: at least one non-HOP-operated bootstrap node exists before public rollout.
