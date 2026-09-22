# `rendezvous/` -- ADR 0002 bootstrap/rendezvous node

## What this is

A standalone process implementing ADR 0002's narrow, explicitly-scoped
bootstrap/rendezvous node: the well-known, address-only "how do I find my
first peer" mechanism every DHT-based P2P network needs
(`docs/adr/0002-bootstrap-node-carveout.md`). It answers ordinary Kademlia
PING/FIND_NODE, address-reflection, rendezvous-relayed introduction, and
volunteer-relay-discovery RPCs -- and **nothing else**. See
[`RendezvousNode`](src/main/kotlin/com/hop/rendezvous/RendezvousNode.kt)'s
own class doc for the full, load-bearing explanation of why it is
*structurally* incapable (not just policy-incapable) of answering a
content-hash or topic query: it never wires `DhtUdpTransport`'s
`onStoreRequested`/`onFindValueRequested` callbacks at all.

## Who should run this -- explicitly NOT HOP

**This process must be run by a volunteer or an independent third-party
operator, never by HOP itself.** This is not a stylistic preference -- it is
the condition ADR 0002's whole carve-out depends on. HOP's non-negotiable
constraint is "no HOP-owned server ever sits in the content, discovery, or
message-delivery path." ADR 0002 explains carefully why a *narrowly-scoped,
address-only* bootstrap node doesn't violate that constraint's actual intent
(it can't see or influence content, discovery results, or relay decisions,
and it's trivially replaceable) -- but that reasoning only holds if HOP isn't
the one running it, or the sole source of it. ADR 0002 is explicit: *"Before
Phase 4 (internet mode) ships publicly, recruit at least one independent
third-party operator for a bootstrap node ... this is a launch condition for
Phase 4, not a someday-nice-to-have."* This `README.md` and the CLI it
documents exist so there is finally something concrete to hand that
operator -- running this yourself as "the HOP bootstrap node" defeats the
entire point.

If you are that third-party operator: thank you, and please read
[`RendezvousNode`](src/main/kotlin/com/hop/rendezvous/RendezvousNode.kt)'s
class doc in full before running this in a way that isn't the stock,
unmodified reference implementation -- the guarantee this whole module
provides depends on the exact wiring in that file staying exactly as
written.

## Building and running

From the `mobile/android/` Gradle build root (`:rendezvous` is included
there as a subproject -- see `mobile/android/settings.gradle.kts`):

```
./gradlew :rendezvous:run --args="--port 8901"
```

Or build a runnable classpath and invoke the class directly:

```
./gradlew :rendezvous:build
java -cp <assembled classpath> com.hop.rendezvous.RendezvousCliKt --port 8901
```

### Arguments

Required:

- `--port <int>` -- the UDP port this node binds to and is reachable at.
  Required (unlike some of this codebase's other CLI tools that default to
  an ephemeral port) because a bootstrap node's entire purpose is being
  dialable at a **known, stable** address an operator has told others about.

Optional:

- `--seed-file <path>` -- where this node's identity seed is persisted
  (default `rendezvous-node-seed.bin` in the current working directory). If
  it doesn't exist, a fresh random seed is generated and written there on
  first run. **Preserve this file across restarts/redeploys.** This node's
  `NodeId` is deterministically derived from it; every peer that ever
  bootstrapped through this node or received its contact info via peer
  exchange caches that contact under this `NodeId`. Losing the seed (or
  running with a fresh one every restart) silently breaks every one of those
  cached entries.
- `--response-cap <int>` -- how many peer contacts a single FIND_NODE
  response hands out (default 20).

### Network access this process needs

- **Inbound UDP on `--port`**, reachable from the public internet (or
  whatever network real clients/other operators are expected to reach it
  from). If you're behind NAT, you need a port forward.
- No outbound-only requirements beyond ordinary UDP replies to whoever
  contacts it.
- No TCP, no HTTP, no other ports.

## What this node can and cannot do -- stated plainly

**Can:**

- Answer "are you alive" (PING).
- Hand out a bounded, random subset of other peer addresses it has recently
  observed (FIND_NODE) -- cold-start peer discovery only, never a
  precision "closest to X" routing answer (see `RendezvousNode`'s own doc
  for why it deliberately uses a flat registry, not a real Kademlia routing
  table).
- Reflect back "here's the address I see you at" (address self-discovery).
- Relay an introduction between two peers that both already know this node
  (NAT hole-punching coordination) -- again, address-only.
- Record and hand out self-reported volunteer relay-node addresses
  (`tools/relay-node/`'s discovery mechanism) -- same address-only shape.

**Cannot, by construction:**

- Answer "what clips exist near me" or "who has this content" -- it never
  wires the RPCs that would let it.
- Verify that any address it hands out (including a self-reported relay
  address) is real, reachable, or honest -- this is unauthenticated
  discovery, same trust level as the rest of this codebase's address-only
  RPCs.

**A real, stated limit on all of the above:** this only describes what the
*stock, unmodified* `RendezvousNode` wiring does. Nothing in the wire
protocol itself stops a dishonest operator from running modified code that
answers content/topic queries anyway -- that would be a violation of ADR
0002 by that operator, not something this module's wire format can prevent.
What this module provides is "the reference implementation cannot do this,"
not "no implementation could ever do this." State this plainly to anyone
relying on this node's privacy properties.

## Phased out as peers accumulate

Per ADR 0002: once a device has any live peer (via this node, BLE, or a
prior session), it should stop depending on a bootstrap node at all and use
ordinary peer exchange through already-connected peers instead. This node is
a cold-start convenience, never meant to be a standing dependency for an
established client.
