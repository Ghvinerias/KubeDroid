package com.kubedroid.app.navigation.feature

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.kubedroid.app.navigation.Routes
import com.kubedroid.feature.rbac.ui.RbacRoute
import com.kubedroid.feature.rbac.ui.RbacViewModel

fun NavGraphBuilder.rbacGraph() {
    composable(Routes.Rbac.list) {
        val viewModel: RbacViewModel = hiltViewModel()
        RbacRoute(viewModel = viewModel)
    }
}
