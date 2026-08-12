# HOP

*(working title — subject to trademark clearance)*

**Serverless, Proximity-First Short-Form Video**
No servers. No accounts. No algorithm feed. Content that lives in a place and time.

CONFIDENTIAL INVESTOR MEMO
Prepared for early-stage discussion — concept stage

---

## 1. Executive Summary

HOP is a short-form video app built with no company-owned servers, no user accounts, and no centralized algorithmic feed. Video clips move directly between nearby phones (Bluetooth discovery + WiFi Direct transfer) and, where useful, across the internet through a distributed peer-to-peer network — never through infrastructure HOP owns or controls.

The product thesis: today's short-form platforms concentrate content, data, and monetization inside one company's servers. HOP inverts that — content lives on-device first, spreads through physical and network proximity, and fades naturally over time and distance unless people actively keep it alive by re-sharing. Monetization runs entirely through a closed-loop in-app token, avoiding the data-harvesting and ad-auction model of incumbent platforms.

Stage: Concept / pre-build. This memo lays out the product, technical architecture, monetization design, go-to-market approach, and organizational plan needed to take HOP from concept to a buildable first release.

## 2. The Problem

Short-form video today runs almost entirely on centralized platforms that:

- Harvest behavioral data to power ad targeting, in exchange for free use of the platform
- Decide virality and reach through an opaque, centrally-controlled algorithm
- Store content indefinitely on company infrastructure, with no user control over its permanence
- Depend entirely on internet connectivity — unusable at events, in transit, or in low-connectivity regions

There is no mainstream short-form video product built around physical proximity, ephemeral-by-default content, or a no-data-collection architecture.

## 3. The Solution

HOP is a video-sharing app where content discovery and delivery are driven by where you are and who's around you, not by a company's ranking algorithm.

**Core product pillars**

- Proximity-first feed: what's playing nearby, discovered via Bluetooth and delivered via WiFi Direct
- Global reach when online: the same clip can travel further over a distributed peer-to-peer network — no company server in the path
- Ephemeral by design: a clip's reach and lifespan decay with distance and time unless people keep re-sharing it
- No accounts required: content and reputation are tied to a local device identity, not a company-held profile

## 4. How It Works

### 4.1 Local / offline mode

Bluetooth Low Energy (BLE) is used only for lightweight peer discovery — announcing "who's nearby and what they're carrying." Once two devices agree to exchange a clip, the transfer itself hands off to WiFi Direct (or the platform equivalent), which offers real throughput for video where BLE alone would be far too slow.

### 4.2 Multi-hop relay

A clip can travel beyond a single device's radio range by relaying hop-to-hop across other users' phones, using an app-layer store-and-forward protocol — similar in spirit to how offline mesh messaging apps extend reach beyond a single connection.

### 4.3 Internet mode — still no company servers

When connectivity is available, the same clip can also be published into a distributed peer-to-peer network: content is addressed by its own hash, and a Distributed Hash Table (DHT) — the same class of technology underlying BitTorrent and IPFS — lets peers find who currently holds it, with no central index owned by HOP.

The one technical honesty point: two devices both behind restrictive (symmetric) NAT occasionally need a third party to broker the initial handshake. HOP addresses this with IPv6-first connections (which sidestep NAT entirely wherever supported), peer-assisted hole-punching, and volunteer-operated relay nodes — community-run infrastructure, not company-owned, in the same spirit as Tor relays.

### 4.4 Ephemeral decay

Reach and visibility fade the further (in hops) and longer (in time) a clip travels from its origin unless people actively keep re-sharing it — making the feed feel tied to a real place and moment, rather than a permanent global archive.

## 5. Product Differentiation

| Dimension | Incumbent short-form platforms | HOP |
|---|---|---|
| Infrastructure | Centralized company servers | No company servers — P2P mesh + DHT |
| Identity | Account + phone number/email | Local device identity, no account |
| Discovery | Opaque ranking algorithm | Proximity + re-share driven |
| Data model | Behavioral tracking for ad targeting | No server-side data collection possible |
| Content lifespan | Permanent, platform-owned | Decays with time/distance by default |
| Monetization | Ad auctions, platform-owned | Closed-loop in-app token economy |

## 6. Monetization: Closed-Loop Token Economy

Because there is no server to run ads through or take a payment cut on, monetization runs through an in-app token that is deliberately non-tradeable outside the app — closer in legal and product character to in-game currency (e.g. Robux, V-Bucks) than to an open cryptocurrency.

**Design principle**

The token is transfer-restricted at the smart-contract level, not merely "unlisted." It can move only between a user's in-app balance and specific in-app actions — it cannot be sent to an arbitrary external wallet. This keeps the product outside KYC/exchange territory entirely, since there is no cash-out path to secure or regulate.

**Faucets (how it's earned)**

- Watching and engaging with content (capped daily, to prevent farming)
- Relaying or caching other users' clips ("proof-of-relay" reward, similar in spirit to how Helium Network rewards hotspot operators for providing coverage)
- Creating content that gets watched or relayed by others

**Sinks (how it's spent)**

- Boosting a clip's relay priority or reach radius
- Extending how long a clip persists before it decays
- Tipping other creators directly, peer-to-peer
- Sponsored local-channel access (e.g. a venue or event sponsoring visibility in its area)

**Ledger integrity**

A closed-loop currency still needs a tamper-proof balance sheet. Rather than building company-run infrastructure to track balances (reintroducing a server), the token is minted on an existing low-fee public chain, so the chain's own distributed validator set handles consensus — borrowed decentralization, not owned infrastructure. A portion of every "boost" spend is burned rather than redistributed, to keep supply from inflating over time.

## 7. Trust & Safety by Design

A decentralized, no-server architecture removes the ability to centrally take down harmful content after the fact — this has to be designed for from day one, not patched in later.

- On-device hash-matching against known-illegal-content databases before a clip is ever relayed, so devices simply refuse to forward known-bad hashes — no server-side check required
- Community-driven propagation control: a "don't relay" signal from enough nearby peers halts a clip's spread without any central takedown authority
- Rate-limited, proof-of-work-gated broadcasting per device, to make mass spam or flooding costly even without account-based penalties
- Ephemeral decay as a built-in circuit breaker: harmful content that isn't actively re-shared loses reach quickly by design

This area is treated as a first-class workstream, led by a dedicated Trust & Safety function from day one — not an afterthought bolted on before launch.

## 8. Go-to-Market Strategy

A proximity-first feed only feels alive with sufficient local density — a single early user in an otherwise empty app has nothing to see. Two comparable offline-mesh messaging apps (FireChat, Bridgefy) saw sharp usage spikes during specific dense events but struggled to retain users once that moment passed, underscoring that density — not broad reach — is the right early metric to optimize.

**Phased rollout**

- Phase 1: single closed, dense environments — a campus, a festival, a gym chain — chosen for guaranteed physical density
- Phase 2: internet-mode pre-seeding — push a starter batch of trending clips into a new city or venue ahead of user arrival, so early local users still see an active feed on day one
- Phase 3: expand to additional dense venues via direct partnerships (sponsored local channels), rather than a general public launch

## 9. Team & Organization

A 28-person founding structure, organized so every major risk area identified in this memo has a clear owner:

**Leadership (3)** — Founder/CEO (product vision), CTO (architecture owner), COO (operations)

**Engineering & Protocol (7)** — Distributed Systems Lead (DHT/Kademlia), P2P Networking Engineer (WebRTC, NAT traversal, IPv6-first routing), Android Mesh Engineer (BLE + WiFi Direct), iOS Platform Engineer (Multipeer Connectivity, background-scanning limits), Video Codec Engineer, Relay Node Tooling Engineer, Reliability/QA Engineer (low-density mesh stress-testing)

**Blockchain & Tokenomics (3)** — Smart Contract Engineer (transfer-restricted token), Tokenomics Designer (faucet/sink balance, inflation control), Smart Contract Security Auditor (independent)

**Trust & Safety / Legal (5)** — Head of Trust & Safety, Content Policy Lead, General Counsel, Regulatory Counsel, Privacy Counsel/DPO

**Product & Growth (4)** — Head of Product, UX/UI Designer, Growth Lead, Partnerships Lead

**Business & Finance (3)** — CFO, Business Development Lead, People Ops Lead

**Advisory (3)** — Decentralized systems (Tor/IPFS/BitTorrent), Consumer growth (retention beyond novelty), Telecom (carrier IPv6 rollout, NAT policy)

## 10. Key Risks & Mitigations

| Risk | Mitigation |
|---|---|
| iOS restricts background BLE/WiFi scanning | Design UX around "open app to see what's nearby" rather than promising ambient background discovery |
| Cold-start: empty app has no content | Launch into single dense venues first; pre-seed via internet mode ahead of local arrival |
| NAT traversal can block direct P2P connection | IPv6-first, peer-assisted hole-punching, volunteer relay nodes as last resort |
| No server means no central content takedown | On-device hash-matching, community propagation control, ephemeral decay as a circuit breaker |
| Regulatory attention toward decentralized, hard-to-monitor networks | Transparent moderation design; legal workstream engaged pre-launch, not post-incident |
| Token could be treated as a security or trigger KYC exposure | Hard transfer-restriction at the contract level; no cash-out path by design |
| Naming collision with existing "BitChat" product | Treat as an architectural reference only; finalize a distinct, cleared name before any public step |

## 11. Financial Overview

No detailed financial model has been built yet at this concept stage. Once the initial engineering and go-to-market plan is scoped, this section should include: build-cost estimate for the 28-person team over an MVP timeline, projected launch-venue costs (Phase 1 partnerships), and a token-economy simulation showing faucet/sink balance over time. Recommend building this out as its own dedicated financial model once Phase 1 venue targets are chosen.

## 12. The Ask

This memo is intended to open discussion, not to solicit a specific round at this stage. Suggested next step: validate the Phase 1 venue partnership approach and produce a detailed engineering build plan and cost estimate before sizing any raise.
