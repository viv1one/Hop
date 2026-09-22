# `tools/relay-node/` -- volunteer relay-node fallback

## What this is

A standalone process implementing BUILD_PLAN.md Phase 4's volunteer
relay-node fallback: the last-resort bridge for two peers who could not
reach each other by direct dial or by NAT hole-punching, typically because
both are behind symmetric NAT. It bridges two TCP connections byte-for-byte,
blind -- it never inspects, decodes, or logs anything it forwards -- and
separately announces its own address to a bootstrap/rendezvous node so real
clients can discover it. Read
[`RelayNode`](src/main/kotlin/com/hop/relaynode/RelayNode.kt)'s own class
doc in full before touching anything here; its trust-model section is the
same honesty this file carries forward, not something to soften.

## Who should run this -- explicitly NOT HOP

**This process must be run by a volunteer or an independent third-party
operator, never by HOP itself.** HOP's non-negotiable constraint is "no
HOP-owned server ever sits in the content, discovery, or message-delivery
path." A dedicated relay node is architecturally the same relay role every
ordinary mesh peer already plays opportunistically -- see the trust-model
section below for exactly why that's true and exactly where it stops being
true -- but running it as HOP-operated, always-on infrastructure would still
put a HOP-controlled node squarely in the message-delivery path, which the
non-negotiable forbids regardless of what that node can or can't read.

## Building and running

From the `mobile/android/` Gradle build root (`:relay-node` is included
there as a subproject -- see `mobile/android/settings.gradle.kts`):

```
./gradlew :relay-node:run --args="--port 8902 --rendezvous bootstrap.example:8901 --advertise-host relay.example"
```

Or build a runnable classpath and invoke the class directly:

```
./gradlew :relay-node:build
java -cp <assembled classpath> com.hop.relaynode.RelayNodeCliKt --port 8902 --rendezvous bootstrap.example:8901 --advertise-host relay.example
```

### Arguments

Required:

- `--port <int>` -- TCP port this relay's bridge server binds to. Clients
  dial this port to request a bridge.
- `--rendezvous <host:port>` -- the bootstrap/rendezvous node (ADR 0002,
  see `rendezvous/README.md`) this relay announces itself to, so clients
  can discover it via a relay query. This tool's whole purpose depends on
  being discoverable, so this is required.
- `--advertise-host <host>` -- the externally-reachable hostname/IP this
  relay announces as its own bridge address. **This cannot be
  auto-discovered.** This codebase has no NAT/external-address-discovery
  mechanism anywhere -- if you're running this behind NAT or port
  forwarding, you must supply the address clients can actually reach from
  outside, not this machine's local network address.

Optional:

- `--advertise-port <int>` -- the externally-reachable port paired with
  `--advertise-host` (default: same as `--port`; override this if your NAT
  or port forwarding remaps the port number).
- `--seed-file <path>` -- where this relay's identity seed is persisted
  (default `relay-node-seed.bin` in the current working directory). Less
  critical than the equivalent in `rendezvous/` -- a stale directory entry
  simply expires on its own TTL rather than poisoning a long-lived peer
  cache -- but there's no reason for this relay's announced identity to
  churn on every restart either.
- `--announce-udp-port <int>` -- UDP bind port for the announcement
  side-channel to the rendezvous node (default `0`, ephemeral). Entirely
  separate from `--port`, the TCP bridging port -- relay announcement is a
  DHT-wire (UDP) RPC, unrelated to the raw-byte TCP bridging this process
  also does.
- `--max-concurrent-slots <int>` -- resource-exhaustion cap on total
  waiting + bridged connections (default 256 -- see `RelayNode`'s own doc
  for what this is and, just as importantly, what it is *not*: a basic
  resource floor, not real abuse-resistance).
- `--wait-timeout-ms <int>` -- how long an unmatched connection waits before
  being closed (default 30000).
- `--reannounce-interval-ms <int>` -- how often this process re-announces
  itself to `--rendezvous` after the initial startup announcement (default:
  a third of the rendezvous directory's own entry TTL, so an announced entry
  never expires while this process keeps running).

### Network access this process needs

- **Inbound TCP on `--port`**, reachable at whatever address/port
  `--advertise-host`/`--advertise-port` claim -- if you're behind NAT, you
  need a port forward, and `--advertise-host`/`--advertise-port` must
  describe the *external* side of that forward.
- **Outbound UDP** to reach `--rendezvous` for announcement (a NAT'd
  outbound connection is normally fine without any special configuration,
  since this side only ever initiates).
- No HTTP, no other ports.

## The trust model -- read this before running or recommending this tool

This section restates, and must not soften, the reasoning already laid out
in [`RelayNode`](src/main/kotlin/com/hop/relaynode/RelayNode.kt)'s own class
doc.

**Confidentiality is not at risk.** Every byte this relay ever forwards is
already end-to-end ciphertext by the time it arrives -- this app's entire
content/message pipeline is encrypted before it ever touches any transport.
A dishonest relay operator gains nothing by inspecting traffic, because
there is nothing readable to inspect. In that specific sense, a dedicated
relay is not a new risk -- it's the same relay role any ordinary mesh peer
already plays when it opportunistically forwards someone else's content.

**Two real, distinct limitations this DOES introduce -- do not describe
these away:**

1. **Availability.** This relay can go offline, be slow, or simply refuse to
   forward, with no guarantee and no retry on its behalf. Best-effort, same
   as the rest of this codebase's networking.
2. **Traffic-analysis / metadata exposure.** Unlike incidental mesh relay
   (where a peer only sees whatever happens to flow through it as ordinary
   flood propagation), a dedicated relay bridge is a *targeted* pairing: two
   specific peers both deliberately connect here asking to be bridged to
   each other. That is a sharper metadata signal -- "these two peers wanted
   to talk, at this time, for this long" -- than incidental mesh relay ever
   exposes. **This is not the same as ordinary mesh relay. Do not describe
   it that way.**

**A third limitation this tool deliberately does NOT solve:** this is an
**unauthenticated open relay with no real rate-limiting or abuse-resistance.**
Anyone could point arbitrary TCP traffic at it, not just this app's mesh --
a genuine cost/liability concern for whoever runs one. What it does include
(`--max-concurrent-slots`, `--wait-timeout-ms`) is only a basic
resource-exhaustion floor so a trivial flood of connection attempts can't
exhaust this process's own memory/threads -- it is explicitly **not**
abuse-resistance against a determined attacker. Real abuse-resistance (e.g.
attested-device-identity gating, per-source-IP limits, bandwidth caps) is
unsolved future work. If you operate one of these, understand that you are
running an open relay and accept the operational/legal exposure that
implies in your jurisdiction.
