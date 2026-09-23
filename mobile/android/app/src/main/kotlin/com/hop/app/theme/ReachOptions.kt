package com.hop.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hop.protocol.ReachTier

/**
 * The four reach choices and the control that renders them, shared by
 * first-run setup and the post composer.
 *
 * These used to be two separate copies of the same list, kept in sync by a
 * comment ("Matches FirstRunScreen's exact labels/copy") rather than by the
 * compiler, and drawn by two different controls -- so the same question
 * looked like two different questions depending on where you met it. One
 * definition now, one control.
 *
 * Plain product language only: no exposed technical vocabulary
 * (geohash/tier/DHT/mesh) per PRD §5 and hop-dev invariant #5.
 */
data class ReachOption(val tier: ReachTier, val label: String, val description: String)

val REACH_OPTIONS: List<ReachOption> = listOf(
    ReachOption(ReachTier.LOCALITY, "Just around me", "The people physically near you right now"),
    ReachOption(ReachTier.TOWN, "My town", "Everyone in your town"),
    ReachOption(ReachTier.CITY, "My city", "Everyone in your city"),
    ReachOption(ReachTier.COUNTRY, "My country", "Everyone in your country"),
)

/**
 * A selectable group of reach cards.
 *
 * Cards rather than a bare radio list: the whole card is the tap target
 * (comfortably past Android's 48dp minimum, where the old text-height rows
 * were not), and selection is carried by fill, border and text colour
 * together rather than by the radio dot alone.
 */
@Composable
fun ReachOptionGroup(
    selectedTier: ReachTier?,
    onTierSelected: (ReachTier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(HopSpacing.sm),
    ) {
        REACH_OPTIONS.forEach { option ->
            ReachOptionCard(
                option = option,
                selected = selectedTier == option.tier,
                onClick = { onTierSelected(option.tier) },
            )
        }
    }
}

@Composable
private fun ReachOptionCard(
    option: ReachOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = HopSpacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HopSpacing.sm),
    ) {
        // onClick = null: the parent Row owns the click and the semantics, so
        // the button must not be a second focusable stop for the same choice.
        RadioButton(selected = selected, onClick = null)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = option.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
