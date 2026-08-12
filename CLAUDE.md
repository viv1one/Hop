# HOP

Serverless, proximity-first short-form video. No company-owned servers, no user accounts, no centralized algorithmic feed. Clips move phone-to-phone via BLE discovery + WiFi Direct (and, in later phases, a P2P/DHT internet mode) and decay over time/distance unless re-shared.

Stage: concept / pre-build. No code exists yet.

- Full product/business context: [docs/memo.md](docs/memo.md)
- Execution sequence and phase-by-phase scope: [BUILD_PLAN.md](BUILD_PLAN.md)

## Non-negotiable constraints

- No HOP-owned server ever sits in the content or discovery path.
- No accounts — identity is local-device-only.
- Ephemeral decay and community "don't relay" propagation control are protocol primitives designed in from Phase 1, not retrofitted later (see BUILD_PLAN.md Phase 2 and memo §7).

## Working on this repo

See the `hop-dev` skill for architecture/module conventions and the `hop-engineer` agent for delegating mesh/protocol implementation work once code exists.
