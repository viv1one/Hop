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
 * Shown when [com.hop.repository.PostRepository.DecryptResult.Decayed] comes
 * back for a post -- this device's copy of the decay key has expired per
 * ADR 0003's key-expiry enforcement, so it can no longer decrypt this post's
 * ciphertext (which is still on disk, just permanently opaque now).
 *
 * Plain-language only, no mesh/crypto jargon exposed ("key expired"/"decay
 * window"/etc. never surface here -- PRD §5). Deliberately does not
 * auto-advance the pager on its own, unlike the photo/video pages: this is a
 * real, intentional page the user swipes past themselves, not a transient
 * loading/error state to rush past.
 */
@Composable
fun DecayedPostPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "This post has expired",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
    }
}
