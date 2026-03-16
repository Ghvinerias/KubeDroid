package com.kubedroid.app.navigation.feature

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.kubedroid.app.navigation.Routes
import com.kubedroid.feature.events.presentation.eventsfeed.EventsFeedDestination
import com.kubedroid.feature.events.presentation.eventsfeed.EventsFeedViewModel

fun NavGraphBuilder.eventsGraph() {
    composable(Routes.Events.list) {
        val viewModel: EventsFeedViewModel = hiltViewModel()
        EventsFeedDestination.Content(viewModel = viewModel)
    }
}
