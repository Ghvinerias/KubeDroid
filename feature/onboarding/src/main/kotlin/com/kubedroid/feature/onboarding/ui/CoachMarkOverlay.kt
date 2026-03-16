package com.kubedroid.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kubedroid.feature.onboarding.R

enum class CoachMarkPlacement {
    Top,
    Center,
    Bottom,
}

data class CoachMarkItem(
    val title: String,
    val description: String,
    val placement: CoachMarkPlacement,
)

@Composable
fun CoachMarkOverlay(
    item: CoachMarkItem,
    isLast: Boolean,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alignment = when (item.placement) {
        CoachMarkPlacement.Top -> Alignment.TopCenter
        CoachMarkPlacement.Center -> Alignment.Center
        CoachMarkPlacement.Bottom -> Alignment.BottomCenter
    }

    val padding = when (item.placement) {
        CoachMarkPlacement.Top -> PaddingValues(top = 72.dp, start = 20.dp, end = 20.dp)
        CoachMarkPlacement.Center -> PaddingValues(horizontal = 20.dp)
        CoachMarkPlacement.Bottom -> PaddingValues(bottom = 88.dp, start = 20.dp, end = 20.dp)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f))
            .padding(padding),
        contentAlignment = alignment,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onNext,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        text = stringResource(
                            if (isLast) {
                                R.string.coach_mark_finish
                            } else {
                                R.string.coach_mark_next
                            },
                        ),
                    )
                }
            }
        }
    }
}
