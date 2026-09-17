package com.hop.app.feed

import com.hop.repository.PostRepository
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plain-logic JVM test for [dontRelayActionEnabled] -- the "proof of local
 * receipt" gate behind "Stop sharing this post" (Phase 2 Slice 2, test-plan
 * case 8). Tests the extracted predicate directly rather than rendering
 * [PostPagerItem] itself: this repo has no Compose UI test harness set up
 * yet (no `ui-test-junit4`/`ui-test-manifest` androidTest dependency), so
 * this is deliberately a logic-level test of the gating decision the
 * Composable's `enabled = dontRelayActionEnabled(result)` call delegates to,
 * not a full rendered-UI assertion.
 */
class PostPagerItemTest {

    @Test
    fun `disabled while decrypt result is null (still decrypting)`() {
        assertFalse(dontRelayActionEnabled(null))
    }

    @Test
    fun `disabled once decrypt result is Decayed`() {
        assertFalse(dontRelayActionEnabled(PostRepository.DecryptResult.Decayed))
    }

    @Test
    fun `disabled while decrypt result is AwaitingKey`() {
        // Phase 4 Slice 10: a post this device hasn't obtained a key for yet
        // is just as un-decrypted as Decayed from this gate's point of view
        // -- "proof of local receipt" requires an actual Decrypted result.
        assertFalse(dontRelayActionEnabled(PostRepository.DecryptResult.AwaitingKey))
    }

    @Test
    fun `enabled once decrypt result is Decrypted`() {
        assertTrue(dontRelayActionEnabled(PostRepository.DecryptResult.Decrypted(byteArrayOf(1, 2, 3))))
    }
}
