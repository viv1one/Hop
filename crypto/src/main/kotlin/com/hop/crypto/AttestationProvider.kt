package com.hop.crypto

/**
 * Platform-agnostic hardware attestation contract (ADR 0004).
 *
 * Attestation proves "this is one real, non-emulated physical device" — never who
 * owns it, and never anything checked against a HOP-operated server (Play
 * Integrity / App Attest are verified against Google/Apple infrastructure, or
 * locally via signed tokens, depending on platform API). This is what gives
 * faucet caps, "don't relay" signal-counting, per-device rate limiting, and
 * blocking any real teeth against free/resettable local identities — bind all
 * four to the attested key returned here, never to a bare local id.
 *
 * This is silent background plumbing (ADR 0004, hop-dev skill invariant #5):
 * nothing in this interface implies a user-facing prompt, except a fallback
 * message on devices that fail attestation.
 *
 * Real platform implementations (Android Play Integrity, iOS App Attest) are out
 * of scope for this pass — [StubAttestationProvider] below is the only
 * implementation shipped here, and every Phase 1 caller downstream of this
 * module should build against the interface, not the stub's behavior.
 */
interface AttestationProvider {
    /**
     * Requests an attestation for [nonce] (caller-supplied, to prevent replay of a
     * previously captured attestation token). Suspending because real platform
     * attestation APIs are asynchronous (they round-trip to Play Services / Apple's
     * infrastructure).
     */
    suspend fun attest(nonce: ByteArray): AttestationResult
}

/**
 * Result of an attestation attempt.
 *
 * Deliberately doesn't distinguish *why* hardware attestation might be
 * unavailable (rooted device, de-Googled ROM, emulator, transient Play Services
 * failure, etc.) beyond a human-readable [Failed.reason] — the rooted/de-Googled
 * attestation-failure handling policy (reduced trust weight vs. hard block) is an
 * explicitly open question per ADR 0004's Limits section, not resolved by this
 * type. Don't have downstream code branch on [Failed.reason] content to make that
 * policy decision implicitly; that needs its own explicit design once the open
 * question is settled.
 */
sealed class AttestationResult {
    /**
     * [attestedPublicKey] is the device-bound public key the attestation token
     * vouches for — this is what faucet/rate-limit/"don't relay"/block logic keys
     * off, not a raw local identity.
     */
    data class Passed(val attestedPublicKey: ByteArray) : AttestationResult()

    data class Failed(val reason: String) : AttestationResult()
}

/**
 * Always-passing stub, for use by everything downstream until the real Android
 * Play Integrity implementation exists (that needs a Google Cloud/Play Console
 * project the owner hasn't provisioned yet — not built in this pass).
 *
 * NOT a substitute for real attestation in any build that ships to users: every
 * Sybil-resistance guarantee ADR 0004 describes (faucet caps, "don't relay"
 * counting, rate limiting, blocking) is meaningless while this stub is wired in,
 * since it grants a fresh "passing" attestation to literally any caller,
 * indistinguishable from N distinct real devices. Safe only for headless
 * development/tests of code that consumes an [AttestationProvider], not for
 * validating Sybil-resistance itself.
 */
class StubAttestationProvider : AttestationProvider {
    override suspend fun attest(nonce: ByteArray): AttestationResult {
        // The "attested" key here is derived from the nonce only so distinct calls
        // are distinguishable in tests — this is not a real hardware-bound key and
        // provides zero Sybil resistance. See class doc.
        return AttestationResult.Passed(attestedPublicKey = nonce.copyOf())
    }
}
