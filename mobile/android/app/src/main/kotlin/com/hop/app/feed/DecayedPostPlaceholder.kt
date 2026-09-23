package com.hop.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hop.app.R
import com.hop.app.theme.HopSpacing

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
 *
 * Rendered dark and self-contained rather than on the theme surface: it sits
 * inside the full-bleed black feed, where a light-mode surface color would
 * flash white between two media pages. [detail] is the second line -- the
 * bare headline alone left a user with no idea whether this was a failure,
 * something to wait on, or something permanent.
 */
@Composable
fun DecayedPostPlaceholder(
    message: String = "This post has faded",
    detail: String = "Posts don't stick around. This one is gone for good.",
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF15151C))
            .padding(HopSpacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HopSpacing.md),
        ) {
            // The brand mark at low opacity, standing in for the missing
            // media. A faded hop for a faded post -- the same idea the logo
            // already encodes, reused rather than a generic error glyph.
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                // Icon, not Image: the mark's vector is a single hardcoded
                // indigo, which reads as muddy at low alpha on this
                // backdrop. Tinting re-colors it outright.
                Icon(
                    painter = painterResource(R.drawable.ic_hop_logo),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.38f),
                    modifier = Modifier.size(38.dp),
                )
            }

            Text(
                text = message,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.92f),
                textAlign = TextAlign.Center,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                // 0.65 white on #15151C clears 4.5:1 comfortably; the old
                // gray-on-gray secondary text did not.
                color = Color.White.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
