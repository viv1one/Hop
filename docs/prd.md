# HOP Product Requirements Document

v0.1 — Concept-Stage Draft

## 1. Overview

Hop is a serverless, short-form photo/video and messaging app. A "post" — a photo or a short video — spreads peer-to-peer — in person via Bluetooth discovery and WiFi Direct transfer, and across distances via a distributed peer-to-peer network — with no company-owned servers at any point. Users control how far their content reaches (locality, town, city, or country), and can message each other through end-to-end encrypted 1:1 chats and groups, using the same peer-to-peer transport.

## 2. Goals & Non-Goals

### Goals

- Let a user post a short video or a photo and have it discoverable by nearby people automatically, with no setup
- Let a user choose how far a post travels — from a physical ~100m broadcast up to country-wide reach
- Provide encrypted group and 1:1 messaging with no phone number or account required
- Keep the product operable with zero company-owned server infrastructure
- Feel as simple to use as Instagram Reels + Instagram DMs — no visible complexity from the underlying peer-to-peer mechanics

### Non-Goals (v1)

- In-app camera capture — v1 posting is upload-from-device-media only (existing photos/videos already on the phone); shooting a photo or video directly within HOP is a planned future release, not v1 (§4.1, §8)
- Public profile pages, follower counts, or a global algorithmic "For You" feed
- Photo/video editing suite (filters, effects, multi-clip editing) beyond basic crop/trim
- Payments that leave the app's closed-loop token economy
- Desktop or web client — v1 is mobile-only

## 3. Users & Core Use Cases

- A person at an event/venue wants to see what's being posted right around them, right now
- A person wants their post seen more broadly — their town or city — without posting to a public global platform
- Two people who just met want to keep chatting without exchanging phone numbers
- A group of friends wants a shared, encrypted space to post/react without a company reading their messages

## 4. Feature Specifications

### 4.1 Post & Watch (core feed)

A post is either a short video (target: under 60 seconds) or a photo. In v1, users **upload** an existing photo/video from their device's media library — no in-app camera. On posting, the app tags the post with a geohash of the current location and the sender's chosen reach setting.

- User story: As a user, I can upload a photo or video from my device and post it with one tap, choosing how far it should reach before posting
- User story: As a user, I see a full-screen, swipeable feed of photo and video posts reachable at my current location and radius setting — the same radius set at first-run setup (§4.2), not a separate browse control
- Acceptance: a post set to "locality" stays entirely on local mesh (BLE/WiFi Direct) and never touches the internet-mode DHT — this one is a hard guarantee, not a default. A post set to "country" is published to the internet-mode DHT and, being a public P2P network, discoverable by any peer that queries that tier's topic — access above Locality is enforced by the reference client's key-wrapping (§7 T&S, ADR 0003), which stops casual/default-client access outside the intended tier but is not an absolute cryptographic guarantee against a determined custom client. State it to users as "shown to" rather than "restricted to" for Town/City/Country.
- Acceptance: content visibility/relay priority decays over time and distance from origin unless re-shared, enforced via the decryption-key expiry in ADR 0003 — decay materially raises the cost of a post persisting past its window for the honest client population; it does not delete the underlying ciphertext from every node that may have cached it, the same limit every ephemeral-content product (Stories, Snapchat) has against a determined archiver
- Acceptance: a photo post displays in the feed for a fixed duration before auto-advancing (Stories-style pacing, since a photo has no natural playback length) — a video post plays for its actual length. Default duration is an open question (§9), not fixed here.
- **Future (post-v1):** in-app camera capture — shoot a photo or record a video directly within HOP instead of uploading an existing file. Purely an app-layer UX addition on top of the same upload/post pipeline; no protocol or wire-format dependency, so it can land whenever it's prioritized without gating on any other phase.

### 4.2 Reach Radius Control

One radius setting, chosen by the user at first-run setup, serves two purposes at once: it's the default reach for the user's own posts, and it's the zoom level of their browse feed (§4.1). It is not two separate settings — changing it changes both what the user sees and what their next post defaults to.

- Tiers: Locality (~100m mesh broadcast) → Town → City → Country, implemented as increasing geohash-prefix precision for DHT topic subscription
- User story: As a first-time user, I pick my radius at setup, and can change it later from settings
- User story: As a user, I can override my radius per-post at time of posting, without changing what I'm currently browsing
- Acceptance: choosing a tighter radius never requires internet connectivity — locality-only posts must work fully offline via BLE/WiFi Direct

### 4.3 Chat Groups

- User story: As a user, I can create a group, add people I've connected with via a post or in-person hop, and message the group
- Acceptance: group messages are end-to-end encrypted; no plaintext content is ever readable by a relay node
- Acceptance: groups work offline within local mesh range and sync via relay when any member has connectivity
- **Phasing note:** a full sender-key/group-ratchet scheme with consistent membership over a partitioned, intermittently-connected network is a hard distributed-systems problem (comparable to what MLS/RFC 9420 solves with a central Delivery Service for ordering — which a serverless network doesn't have). v1 groups use per-member pairwise Double Ratchet fan-out instead (each message individually encrypted to each member's existing 1:1 session) — higher bandwidth cost, but no ordering-service dependency. Migrate to a proper group ratchet once a serverless-compatible delivery/ordering design exists as its own spike (BUILD_PLAN.md Phase 2). Keep v1 group size small by design to bound the fan-out cost.

### 4.4 One-on-One Encrypted Chat

- User story: As a user, I can start a private chat with someone directly from their post or profile, without needing their phone number
- Acceptance: 1:1 messages use Double Ratchet-style E2E encryption (Signal Protocol pattern); forward secrecy holds even if a device is later compromised
- Acceptance: message delivery falls back to store-and-forward via volunteer relay nodes when the recipient is offline, with the relay unable to read message content
- Acceptance: relay-held messages expire after a bounded retention window (consistent with the product's ephemeral-by-default posture); if a recipient has been offline long enough that the ratchet's skipped-message-key cache would be exceeded, the session falls back to a fresh handshake (re-establish, don't silently drop messages forever)
- Acceptance: a user can block another user's identity from a chat or their post; the block is enforced against that identity's hardware-attested device key (ADR 0004), so it survives an app reinstall on the same device — it does not survive the blocked party acquiring a new physical device, which is a stated limit, not a gap to hide

### 4.5 Token Economy (Hop Points)

Recap from the monetization design — included here for completeness against the product surface:

- Earned via watching/engaging, relaying/caching others' content, and creating content that gets watched or relayed
- Spent on boosting a post's reach/priority, extending how long a post persists, and tipping other creators
- Transfer-restricted at the contract level — no external cash-out, no KYC exposure

### 4.6 Trust & Safety Controls

- On-device hash-matching against known-illegal-content databases (applies to both photo and video hashes — image hash-matching, e.g. PhotoDNA-style, is the more mature of the two technologies), checked both before a device relays a post and again on receipt — so a modified client skipping its own check still gets caught by the next honest device downstream
- Report / block user, surfaced directly from a post or chat (see §5) — reporting a post triggers the "don't relay" signal below; blocking a user is enforced against their attested device key (§4.4)
- "Don't relay" signal that suppresses further propagation once enough nearby peers flag content, where "enough peers" means distinct hardware-attested devices (ADR 0004) that can show local proof of having received/viewed the post — not raw signal count, which would be trivially Sybil-able with free identity
- Per-device rate limiting on broadcast volume, backed by the same device attestation, with local (non-identity-linked, decaying) peer-reputation scoring so misbehaving devices get deprioritized by neighbors without a central ban list
- **Explicit limit, stated for users, investors, and engineers alike:** these controls bind the stock HOP client; they cannot stop a custom client built to ignore them, only raise the cost of running one. See memo §7 for the same caveat applied to the business/investor framing.

## 5. UX Requirements

- Two primary tabs: a full-screen vertical photo/video feed (Reels/Stories-equivalent) and an inbox (DM-equivalent) — no more navigation complexity than that at launch
- Posting flow in v1 opens the device's media picker (photo/video library), not a camera — see §4.1. Don't build camera-shaped UI (shutter button, viewfinder) until in-app capture is actually prioritized; a picker-first flow is simpler and ships faster.
- No account creation flow. First run has exactly one step before the feed: pick a default reach radius (§4.2) — a local device preference, not an account, so it carries none of the setup cost the "no accounts" goal is meant to avoid. Device attestation (ADR 0004) happens silently in the background as part of this step — it's a hardware check, not a user-facing prompt, and needs no UI beyond a fallback message on devices that fail attestation (rooted/de-Googled — see open questions)
- Reach-radius control surfaced as a simple, single control at time of posting (not buried in settings), pre-filled with the user's default from setup
- Report and block are one tap away from any post or chat message — not buried in a menu; this is the safety-critical counterpart to "no accounts" removing the usual account-suspension lever, so it needs to be at least as easy to reach as the content itself
- All peer-to-peer/mesh/relay mechanics are invisible to the user — no exposed technical language in the UI

## 6. Technical Requirements Summary

| Layer | Requirement |
|---|---|
| Local discovery | BLE for peer discovery only; never used for payload transfer. On Android, scan using the `neverForLocation` flag (API 31+) so peer discovery doesn't require granting Location permission |
| Local transfer | WiFi Direct (or platform equivalent) for photo, video, and message payloads |
| Wide-area reach | DHT-based P2P network; content addressed by hash; geohash-tagged for locality/town/city/country topic subscription, resolved against the target cell plus its neighbor cells to avoid boundary-edge misses; payloads key-wrapped per tier and decay window (ADR 0003), not access-controlled by topic secrecy alone |
| Bootstrap/rendezvous | A narrowly-scoped, address-only bootstrap node set (never sees content or topics) for first-contact cold start, phased out via peer exchange once a device has any live peer (ADR 0002) |
| Identity | Local device identity bound to a hardware attestation token (Play Integrity / App Attest) — proves "one real device," never who owns it; no HOP-operated verification server required (ADR 0004) |
| NAT traversal | IPv6-first; peer-assisted hole-punching; volunteer relay nodes as last resort |
| Messaging encryption | Signal Protocol pattern — Double Ratchet (1:1); pairwise Double Ratchet fan-out for groups in v1, migrating to a proper group ratchet once a serverless-compatible delivery/ordering design exists (§4.3) |
| Offline delivery | Store-and-forward via volunteer relay nodes, bounded retention window; relays cannot read encrypted payloads; ratchet desync past the skipped-key cache falls back to re-handshake |
| Token ledger | Smart contract on an existing low-fee public chain; transfer-restricted; governed by a time-locked multisig with no pause/freeze/blacklist power over user balances |

## 7. Non-Functional Requirements

- Local video transfer (post to visible-on-recipient-device) should complete in low single-digit seconds over WiFi Direct for a compressed short clip
- Local photo transfer should feel near-instant (sub-second to low single-digit seconds) over WiFi Direct — photo payloads are orders of magnitude smaller than video, so this should be the easy case, not a separate risk to de-risk
- Background battery drain from discovery scanning must respect each platform's background-execution limits (notably iOS) — app should not be expected to run true background mesh discovery on iOS
- Locality-tier posting and viewing must function with zero internet connectivity
- Message delivery should feel near-instant when both parties are in mesh range or both online; store-and-forward delay is acceptable when either party is offline

## 8. Out of Scope for v1

- In-app camera capture (shoot a photo/video directly within HOP) — v1 is upload-from-device-media only; a planned future release, not a rejected idea (§2, §4.1)
- Country-tier reach at true nationwide scale (v1 should validate at town/city tier first, per the go-to-market density strategy)
- Photo/video editing beyond crop/trim
- Any external payment or exchange integration for the token

## 9. Open Questions

- Should group chats support photo/video posts directly, or remain text/media-share only in v1?
- What's the minimum viable set of launch venues for the Phase 1 density strategy (see investor memo, Section 8)?
- Devices that fail hardware attestation (rooted/de-Googled Android, jailbroken iOS) — block them from the app entirely, or let them browse/post with reduced trust weight (excluded from token faucets and "don't relay" flag-counting)? The latter avoids excluding privacy-conscious users who are otherwise this product's target audience, but needs a Trust & Safety call (ADR 0004).
- What's the relay-message retention window (§4.4) — tied to the same decay-window parameters as content, or set independently for messages?
- What's the default display duration for a photo post in the feed (§4.1)? Stories-style conventions typically use ~5 seconds — reasonable starting default, but worth a UX call rather than treating it as settled here.
- When in-app camera capture ships (post-v1, §4.1), does it replace the upload picker or sit alongside it? Worth deciding before building the UI, not after.
