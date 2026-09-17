package com.hop.app.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown when [com.hop.repository.PostRepository.DecryptResult.Decayed] (this
 * device's copy of the decay key has genuinely expired per ADR 0003's
 * key-expiry enforcement -- the ciphertext is still on disk, just
 * permanently opaque now) or [com.hop.repository.PostRepository.DecryptResult.AwaitingKey]
 * (a Town/City/Country post this device hasn't obtained a key for *yet* --
 * see that case's own doc, and [com.hop.app.feed.FeedViewModel.decrypt] for
 * the best-effort request it fires on this outcome) comes back for a post.
 * [message] defaults to the `Decayed` copy; [PostPagerItem] passes a
 * different, non-overclaiming string for `AwaitingKey` -- "expired" would be
 * simply wrong for a post that hasn't actually decayed, it's just missing a
 * key this device hasn't asked for (or received) yet.
 *
 * Plain-language only, no mesh/crypto jargon exposed ("key expired"/"decay
 * window"/"tier"/etc. never surface here -- PRD §5). Deliberately does not
 * auto-advance the pager on its own, unlike the photo/video pages: this is a
 * real, intentional page the user swipes past themselves, not a transient
 * loading/error state to rush past.
 */
@Composable
fun DecayedPostPlaceholder(message: String = "This post has expired") {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
    }
}
