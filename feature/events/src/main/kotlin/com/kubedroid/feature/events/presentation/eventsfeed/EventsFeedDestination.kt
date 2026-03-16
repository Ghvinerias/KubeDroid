package com.kubedroid.feature.events.presentation.eventsfeed

import androidx.compose.runtime.Composable

object EventsFeedDestination {
    @Composable
    fun Content(
        viewModel: EventsFeedViewModel,
    ) {
        EventsFeedRoute(viewModel = viewModel)
    }
}
