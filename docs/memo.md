# HOP

*(working title — subject to trademark clearance)*

**Serverless, Proximity-First Short-Form Photo & Video**
No servers. No accounts. No algorithm feed. Content that lives in a place and time.

CONFIDENTIAL INVESTOR MEMO
Prepared for early-stage discussion — concept stage

---

## 1. Executive Summary

HOP is a short-form photo/video app built with no company-owned servers, no user accounts, and no centralized algorithmic feed. Photo and video posts move directly between nearby phones (Bluetooth discovery + WiFi Direct transfer) and, where useful, across the internet through a distributed peer-to-peer network — never through infrastructure HOP owns or controls. v1 posting is upload-from-device-media; in-app camera capture (shoot directly within HOP) is a planned post-v1 addition, not a launch requirement.

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

HOP is a photo/video-sharing app where content discovery and delivery are driven by where you are and who's around you, not by a company's ranking algorithm.

**Core product pillars**

- Proximity-first feed: what's playing nearby, discovered via Bluetooth and delivered via WiFi Direct
- Global reach when online: the same clip can travel further over a distributed peer-to-peer network — no company server in the path
- Ephemeral by design: a clip's reach and lifespan decay with distance and time unless people keep re-sharing it
- No accounts required: content and reputation are tied to a local device identity, not a company-held profile

## 4. How It Works

### 4.1 Local / offline mode

Bluetooth Low Energy (BLE) is used only for lightweight peer discovery — announcing "who's nearby and what they're carrying." Once two devices agree to exchange a post (photo or video), the transfer itself hands off to WiFi Direct (or the platform equivalent), which offers real throughput for video where BLE alone would be far too slow — photo transfer is comparatively trivial by the same measure.

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
| Discovery | Opaque ranking algorithm | Proximity + re-share driven, plus a transparent, symmetric-price boost anyone can buy (§6) — not company-selected, but not purely organic either |
| Data model | Behavioral tracking for ad targeting | No server-side data collection possible |
| Content lifespan | Permanent, platform-owned | Decays with time/distance by default, enforced by key expiry (§7) — a strong deterrent for the honest client population, not an absolute deletion guarantee against a determined archiver |
| Monetization | Ad auctions, platform-owned | Closed-loop in-app token economy; includes disclosed, geographically-scoped sponsorship (§6) — no behavioral targeting, but not zero paid placement either |

The middle column of this table is more accurate than "no algorithm" or "no ads" claimed in isolation: HOP removes opaque, data-harvesting ranking and behavioral ad targeting specifically, not the concept of paid visibility. §6 details the caps that keep the boost mechanic from becoming pay-to-dominate.

## 6. Monetization: Closed-Loop Token Economy

Because there is no server to run ads through or take a payment cut on, monetization runs through an in-app token that is deliberately non-tradeable outside the app — closer in legal and product character to in-game currency (e.g. Robux, V-Bucks) than to an open cryptocurrency.

**Design principle**

The token is transfer-restricted at the smart-contract level, not merely "unlisted." It can move only between a user's in-app balance and specific in-app actions — it cannot be sent to an arbitrary external wallet. This keeps the product outside KYC/exchange territory entirely, since there is no cash-out path to secure or regulate.

**Faucets (how it's earned)**

- Watching and engaging with content (capped daily, to prevent farming)
- Relaying or caching other users' clips ("proof-of-relay" reward, similar in spirit to how Helium Network rewards hotspot operators for providing coverage)
- Creating content that gets watched or relayed by others

"No accounts" means a naive per-identity daily cap is free to defeat (reinstall, emulator farm). Faucet caps are enforced per hardware-attested device key (ADR 0004), not per soft local identity — attestation proves "one real, non-emulated device," never who owns it, so this doesn't reintroduce an account. It raises the cost of farming from free to "own another physical device"; it doesn't make farming impossible, and that limit should stay stated plainly rather than implied away.

**Sinks (how it's spent)**

- Boosting a clip's relay priority or reach radius — capped with a diminishing-returns cost curve and a hard per-post ceiling relative to organic reach, so token spend can shift ranking but can't fully drown out organic proximity content. This is what keeps the "transparent boost, not opaque algorithm" distinction in §5 real rather than cosmetic.
- Extending how long a clip persists before it decays — bounded by the same key-expiry mechanism as decay itself (§7); this sink extends the key's validity window, it doesn't resurrect a clip whose window already closed.
- Tipping other creators directly, peer-to-peer
- Sponsored local-channel access (e.g. a venue or event sponsoring visibility in its area) — always disclosed/labeled as sponsored in the UI, never presented as organic, both to preserve the "no ad-auction" positioning honestly and to avoid consumer-protection disclosure exposure.

**Ledger integrity**

A closed-loop currency still needs a tamper-proof balance sheet. Rather than building company-run infrastructure to track balances (reintroducing a server), the token is minted on an existing low-fee public chain, so the chain's own distributed validator set handles consensus — borrowed decentralization, not owned infrastructure. A portion of every "boost" spend is burned rather than redistributed, to keep supply from inflating over time.

**Contract governance**

The contract needs an upgrade path (bugs will happen) without recreating a company-controlled choke point over user balances. Decision: a time-locked multisig (48-72h public timelock on any change) can upgrade faucet/sink logic, but has no pause, freeze, or blacklist capability over existing user balances under any circumstance. This is a deliberate middle point between "no upgrade path, any bug is permanent" and "an admin key that can seize funds" — the latter would undercut the "no server in the path" positioning as much as company-run infrastructure would.

## 7. Trust & Safety by Design

A decentralized, no-server architecture removes the ability to centrally take down harmful content after the fact — this has to be designed for from day one, not patched in later. It also means every check below runs on a device the sender/relayer controls, which bounds what it can guarantee — see the honesty note at the end of this section.

- On-device hash-matching against known-illegal-content databases, checked **both** before a device relays a clip and again on receipt — checking on receipt too means a modified client that skips its own outbound check still gets caught by the next honest device downstream, rather than the whole chain depending on the originator's honesty.
- Community-driven propagation control: a "don't relay" signal from enough nearby peers halts a clip's spread without any central takedown authority. "Enough peers" is gated by hardware-attested device identity plus proof of local receipt/playback (ADR 0004) — without that, free device identity makes this trivially Sybil-able in both directions: as easy to weaponize for harassment/censorship (five burner identities suppress a real post) as to rely on for genuine abuse response.
- Rate-limited, proof-of-work-gated broadcasting per device, backed by the same device attestation — a device that misbehaves (floods, ignores decay) gets locally deprioritized by its peers (a decaying, pairwise, non-identity-linked trust score, not a persistent global ban list) without needing a central authority to enforce it.
- Ephemeral decay as a built-in circuit breaker, made structurally real via key expiry rather than client politeness alone (ADR 0003) — content that isn't re-shared within its decay window stops being decryptable by honest clients, which is what actually gives "don't relay" and decay teeth against the DHT's own persistence.
- **Honesty note, stated for investors and engineers alike:** none of the above stops a determined, custom client from ignoring its own checks or fabricating claims (device attestation raises the cost of a fake identity, it doesn't reach zero). What this design guarantees is that the overwhelming majority of real usage — the stock client — never relays known-bad content, respects decay, and respects propagation-control signals. That's the same honesty bar Tor and other P2P systems hold themselves to, and it should be represented to investors and regulators as "raises the cost of abuse substantially," not "makes abuse impossible."

**Legal workstream gates, not yet resolved by this memo:**

- **Illegal-content reporting obligations — deferred, tracked for later resolution.** Hash-matching only catches previously-known content; it does not detect novel abuse material, and jurisdictions including the US impose mandatory reporting duties (e.g. NCMEC CyberTipline, 18 U.S.C. §2258A) on providers who become aware of CSAM. An architecture designed so the company never sees content may not exempt it from that duty. This is a real open question, but not one this concept-stage plan needs to resolve now — it's parked as a named item to scope with outside counsel once the team and timeline are in place to act on the answer (well ahead of any public launch), rather than a blocker on planning or early engineering work.
- **Secondary liability for user-distributed content.** Content-addressed, hash-based DHT distribution is architecturally similar to the P2P file-sharing systems (Napster, Grokster) that lost secondary-liability suits specifically because decentralization didn't shield the company distributing and profiting from the client. Mitigations worth pursuing: a published rights-holder hash-submission channel reusing the illegal-content block-list mechanism, a short (≤60s) clip-length ceiling that structurally discourages long-form piracy, and clear Terms of Service — but the exposure itself needs an outside IP counsel opinion before public launch, not just engineering mitigation.

This area is treated as a first-class workstream, led by a dedicated Trust & Safety function from day one — not an afterthought bolted on before launch.

## 8. Go-to-Market Strategy

A proximity-first feed only feels alive with sufficient local density — a single early user in an otherwise empty app has nothing to see. Two comparable offline-mesh messaging apps (FireChat, Bridgefy) saw sharp usage spikes during specific dense events but struggled to retain users once that moment passed, underscoring that density — not broad reach — is the right early metric to optimize.

**Phased rollout**

- Phase 1: single closed, dense environments — a campus, a festival, a gym chain — chosen for guaranteed physical density
- Phase 2: internet-mode pre-seeding — push a starter batch of trending clips into a new city or venue ahead of user arrival, so early local users still see an active feed on day one
- Phase 3: expand to additional dense venues via direct partnerships (sponsored local channels), rather than a general public launch

A note on sequencing: this GTM phasing is about audience/venue expansion, not engineering readiness. A real-user soft pilot at a single dense venue should run as soon as BUILD_PLAN.md's engineering Phase 1/2 (local mesh + relay, no token, no DHT) is solid — it doesn't need to wait for the token economy, iOS port, or DHT to exist. Gating the first real user behind the entire engineering build (through Phase 6) would contradict this section's own logic and burn far more capital before any validation than necessary — see BUILD_PLAN.md's phase notes.

## 9. Team & Organization

A 28-person structure covers every major risk area identified in this memo, but should be **hired in phase, not on day one** — de-risk the core mechanic with a small team before committing to full headcount and its burn rate:

- **Phase 0 (feasibility spikes):** Leadership (3) plus 2-3 core engineers only — enough to run the BLE/WiFi Direct spikes. Nothing else is justified until those numbers come back.
- **Phase 1-2 (local mesh MVP + relay):** ramp the Engineering & Protocol bench as those surfaces get built — Android Mesh Engineer, Relay Node Tooling Engineer, Reliability/QA Engineer.
- **Phase 3-4 (iOS, internet mode):** iOS Platform Engineer, P2P Networking Engineer, Distributed Systems Lead join as those phases approach, not before.
- **Phase 5 (trust & safety hardening):** Head of Trust & Safety, Content Policy Lead, and legal counsel (General/Regulatory/Privacy) ramp in ahead of this phase — they're also needed earlier, on a fractional/outside-counsel basis, for the copyright-liability gate called out in §7, which lands well before Phase 5. The illegal-content-reporting question (§7) is deferred and doesn't require counsel ramp-up this early — revisit its timing once this phase is closer.
- **Phase 6 (token economy):** the full Blockchain & Tokenomics bench (Smart Contract Engineer, Tokenomics Designer, independent Security Auditor) joins here, not earlier — there's nothing for them to build until the phases above exist.
- **Advisory (3)** roles are useful from early on precisely because they're fractional, not full-time headcount.

Full listing, by function:

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
| No server means no central content takedown | On-device hash-matching (checked on both send and receipt), attested-identity-gated community propagation control, key-expiry-backed decay as a circuit breaker (ADR 0003, ADR 0004) — a strong deterrent for the honest-client population, not an absolute guarantee against a determined, custom client (§7) |
| Regulatory attention toward decentralized, hard-to-monitor networks | Transparent moderation design; legal workstream engaged pre-launch, not post-incident |
| Token could be treated as a security or trigger KYC exposure | Hard transfer-restriction at the contract level; no cash-out path by design; contract governance is a time-locked multisig with no balance-touching power (§6) |
| Naming collision with existing "BitChat" product | Treat as an architectural reference only; finalize a distinct, cleared name before any public step |
| Mandatory illegal-content reporting obligations (e.g. NCMEC CyberTipline in the US) may apply regardless of "no server sees content" | Deferred: tracked as a named open item (§7), to be scoped with outside counsel later, well ahead of public launch — not resolved by architecture alone, and not a blocker on current planning or engineering work |
| Secondary liability for user-distributed copyrighted content (Napster/Grokster-pattern exposure) | Rights-holder hash-block channel, short clip-length ceiling, outside IP counsel opinion before public launch (§7) |
| App Store / Play Store policy rejection (UGC content-moderation requirements historically expect a working, timely abuse-response mechanism) | Early pre-submission policy consultation and a minimal TestFlight/Play Console spike before full build investment (BUILD_PLAN.md Phase 0); demonstrate the on-device report → suppress → "don't relay" flow as the abuse-response mechanism |
| DHT/mesh cold-start needs bootstrap rendezvous nodes, in tension with "no HOP server in the discovery path" | Narrow carve-out: address-only bootstrap nodes holding zero content/discovery logic, phased out via peer exchange, with a non-HOP-operated bootstrap node recruited before Phase 4 ships publicly (ADR 0002) |
| WiFi Direct's per-device group-connection limits may bottleneck exactly the dense-venue scenario the GTM strategy depends on | De-risk with an N-peer (not just 2-device) WiFi Direct spike in Phase 0, before committing to the dense-venue GTM bet (BUILD_PLAN.md) |

## 11. Financial Overview

No detailed financial model has been built yet at this concept stage. Once the initial engineering and go-to-market plan is scoped, this section should include: build-cost estimate for the 28-person team over an MVP timeline, projected launch-venue costs (Phase 1 partnerships), and a token-economy simulation showing faucet/sink balance over time. Recommend building this out as its own dedicated financial model once Phase 1 venue targets are chosen.

## 12. The Ask

This memo is intended to open discussion, not to solicit a specific round at this stage. Suggested next step: validate the Phase 1 venue partnership approach and produce a detailed engineering build plan and cost estimate before sizing any raise.
