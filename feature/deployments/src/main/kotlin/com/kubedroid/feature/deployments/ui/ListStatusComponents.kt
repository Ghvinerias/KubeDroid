package com.kubedroid.feature.deployments.ui

import android.text.format.DateUtils
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kubedroid.feature.deployments.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun StaleDataBanner(
    lastFetchedAtEpochMillis: Long?,
    modifier: Modifier = Modifier,
    nowEpochMillis: Long = System.currentTimeMillis(),
) {
    val containerShape = RoundedCornerShape(dimensionResource(id = R.dimen.stale_data_banner_corner_radius))
    val verticalPadding = dimensionResource(id = R.dimen.stale_data_banner_vertical_padding)
    val horizontalPadding = dimensionResource(id = R.dimen.stale_data_banner_horizontal_padding)

    val ageText = lastFetchedAtEpochMillis?.let { formatDataAge(it, nowEpochMillis) }
        ?: stringResource(id = R.string.stale_data_banner_age_unknown)
    val lastUpdatedText = lastFetchedAtEpochMillis?.let(::formatUserDateTime)
        ?: stringResource(id = R.string.stale_data_banner_last_updated_unknown)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(containerShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(
            dimensionResource(id = R.dimen.stale_data_banner_content_spacing),
        ),
    ) {
        Text(
            text = stringResource(id = R.string.stale_data_banner_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(id = R.string.stale_data_banner_message, ageText, lastUpdatedText),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ListShimmerPlaceholder(
    modifier: Modifier = Modifier,
    itemCount: Int = 6,
) {
    val loadingContentDescription = stringResource(id = R.string.list_shimmer_loading_content_description)
    val sectionSpacing = dimensionResource(id = R.dimen.list_shimmer_section_spacing)
    val itemSpacing = dimensionResource(id = R.dimen.list_shimmer_item_spacing)
    val titleHeight = dimensionResource(id = R.dimen.list_shimmer_title_height)
    val subtitleHeight = dimensionResource(id = R.dimen.list_shimmer_subtitle_height)
    val subtitleWidth = dimensionResource(id = R.dimen.list_shimmer_subtitle_width)
    val chipHeight = dimensionResource(id = R.dimen.list_shimmer_chip_height)
    val chipWidth = dimensionResource(id = R.dimen.list_shimmer_chip_width)
    val dividerHeight = dimensionResource(id = R.dimen.list_shimmer_divider_height)
    val itemVerticalPadding = dimensionResource(id = R.dimen.list_shimmer_item_vertical_padding)
    val shape = RoundedCornerShape(dimensionResource(id = R.dimen.list_shimmer_shape_radius))

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = loadingContentDescription
            },
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        repeat(itemCount) {
            Column(verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = itemVerticalPadding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(itemSpacing),
                        modifier = Modifier.weight(1f),
                    ) {
                        ShimmerBlock(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(titleHeight),
                            shape = shape,
                        )
                        ShimmerBlock(
                            modifier = Modifier
                                .width(subtitleWidth)
                                .height(subtitleHeight),
                            shape = shape,
                        )
                    }
                    ShimmerBlock(
                        modifier = Modifier
                            .width(chipWidth)
                            .height(chipHeight),
                        shape = shape,
                    )
                }
                ShimmerBlock(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dividerHeight),
                    shape = shape,
                )
            }
        }
    }
}

@Composable
private fun ShimmerBlock(
    modifier: Modifier,
    shape: RoundedCornerShape,
) {
    val transition = rememberInfiniteTransition(label = "list_shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "list_shimmer_progress",
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        start = Offset.Zero,
        end = Offset(x = progress * 1000f, y = progress * 1000f),
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(shimmerBrush),
    )
}

private fun formatDataAge(lastFetchedAtEpochMillis: Long, nowEpochMillis: Long): String {
    val safeNowEpochMillis = maxOf(nowEpochMillis, lastFetchedAtEpochMillis)
    return DateUtils.getRelativeTimeSpanString(
        lastFetchedAtEpochMillis,
        safeNowEpochMillis,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

private fun formatUserDateTime(epochMillis: Long): String {
    val formatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(epochMillis))
}
