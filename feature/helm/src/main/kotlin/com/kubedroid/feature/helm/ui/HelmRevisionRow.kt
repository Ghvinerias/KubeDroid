package com.kubedroid.feature.helm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.kubedroid.feature.helm.R

@Composable
fun HelmRevisionRow(
    revision: HelmRevisionUiModel,
    selected: Boolean,
    onClick: () -> Unit,
    showSelectionControl: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val rowPadding = dimensionResource(id = R.dimen.helm_list_item_padding)
    val contentSpacing = dimensionResource(id = R.dimen.helm_content_spacing)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(rowPadding),
        horizontalArrangement = Arrangement.spacedBy(contentSpacing),
    ) {
        if (showSelectionControl) {
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
        } else {
            Spacer(modifier = Modifier.width(dimensionResource(id = R.dimen.helm_badge_horizontal_padding)))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            Text(
                text = stringResource(id = R.string.helm_revision_title, revision.revision),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(id = revision.status.labelResId),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = revision.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    id = R.string.helm_release_last_deployed_value,
                    revision.deployedAtEpochMillis?.toUserDateTime()
                        ?: stringResource(id = R.string.helm_value_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
