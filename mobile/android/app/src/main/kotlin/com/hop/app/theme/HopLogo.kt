package com.hop.app.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hop.app.R

/**
 * HOP's brand mark: an origin node, a hop arc, and a smaller faded node --
 * one phone-to-phone hop, losing reach as it travels.
 *
 * The artwork itself lives in `res/drawable/ic_hop_logo.xml` (single-color by
 * design) so the launcher icon and this share one geometry; this composable is
 * the only thing in app code that should reference that drawable, so a future
 * mark change has exactly one Compose-side caller to check.
 *
 * [tint] defaults to the theme's `primary`, which is what makes the mark work
 * unchanged in both light and dark -- pass an explicit color only when the
 * mark sits on something that isn't a theme surface (e.g. over feed media).
 */
@Composable
fun HopLogo(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Icon(
        painter = painterResource(R.drawable.ic_hop_logo),
        // Decorative in every current placement: the wordmark or an adjacent
        // heading always carries the name for screen readers, so announcing
        // "HOP logo" here would just be a duplicate stop.
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/**
 * The mark plus the "HOP" wordmark, for places that need the app to name
 * itself (first-run being the only one today -- the feed is deliberately
 * full-bleed with no chrome to hang a wordmark on).
 *
 * The wordmark is set in the theme's own type scale rather than a bundled
 * brand face: HOP ships no custom font yet, and adding one for three letters
 * isn't worth the APK weight until there's a real type decision to honor.
 * Heavy weight and wide letterspacing are doing the brand work instead.
 */
@Composable
fun HopWordmark(
    modifier: Modifier = Modifier,
    markSize: Dp = 40.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HopSpacing.sm),
    ) {
        HopLogo(size = markSize, tint = tint)
        Text(
            text = "HOP",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
            ),
            color = tint,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HopWordmarkPreview() {
    HopTheme { HopWordmark() }
}

@Preview(showBackground = true, backgroundColor = 0xFF121218)
@Composable
private fun HopWordmarkDarkPreview() {
    HopTheme(useDarkTheme = true) { HopWordmark() }
}
