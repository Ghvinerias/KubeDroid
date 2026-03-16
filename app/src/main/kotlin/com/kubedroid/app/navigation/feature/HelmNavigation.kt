package com.kubedroid.app.navigation.feature

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.kubedroid.app.navigation.Routes
import com.kubedroid.app.ui.adaptive.AdaptiveLayoutState
import com.kubedroid.feature.helm.ui.HelmRoute
import com.kubedroid.feature.helm.ui.HelmViewModel

fun NavGraphBuilder.helmGraph(adaptiveLayoutState: AdaptiveLayoutState) {
    composable(Routes.Helm.list) {
        val viewModel: HelmViewModel = hiltViewModel()
        HelmRoute(
            viewModel = viewModel,
            useTwoPane = adaptiveLayoutState.isTwoPanePreferred,
            hingePadding = adaptiveLayoutState.hingePadding,
        )
    }
}
