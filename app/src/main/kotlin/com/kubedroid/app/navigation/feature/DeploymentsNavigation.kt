package com.kubedroid.app.navigation.feature

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kubedroid.app.R
import com.kubedroid.app.navigation.Routes
import com.kubedroid.app.ui.adaptive.AdaptiveLayoutState
import com.kubedroid.feature.deployments.ui.DeploymentDetailRoute
import com.kubedroid.feature.deployments.ui.DeploymentDetailViewModel
import com.kubedroid.feature.deployments.ui.DeploymentListRoute
import com.kubedroid.feature.deployments.ui.DeploymentListViewModel

fun NavGraphBuilder.deploymentsGraph(
    navController: NavController,
    adaptiveLayoutState: AdaptiveLayoutState,
) {
    composable(Routes.Deployments.list) {
        val viewModel: DeploymentListViewModel = hiltViewModel()
        var selectedDeploymentNamespace by rememberSaveable { mutableStateOf<String?>(null) }
        var selectedDeploymentName by rememberSaveable { mutableStateOf<String?>(null) }
        DeploymentListRoute(
            viewModel = viewModel,
            onDeploymentClick = { namespace, deploymentName ->
                if (adaptiveLayoutState.isTwoPanePreferred) {
                    selectedDeploymentNamespace = namespace
                    selectedDeploymentName = deploymentName
                } else {
                    navController.navigate(deploymentDetailRoute(namespace, deploymentName))
                }
            },
            useTwoPane = adaptiveLayoutState.isTwoPanePreferred,
            hingePadding = adaptiveLayoutState.hingePadding,
            detailPane = {
                val namespace = selectedDeploymentNamespace
                val name = selectedDeploymentName
                if (namespace.isNullOrBlank() || name.isNullOrBlank()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = stringResource(id = R.string.two_pane_select_deployment))
                    }
                } else {
                    val detailViewModel: DeploymentDetailViewModel = hiltViewModel()
                    DeploymentDetailRoute(
                        namespace = namespace,
                        deploymentName = name,
                        viewModel = detailViewModel,
                        onBackClick = {
                            selectedDeploymentNamespace = null
                            selectedDeploymentName = null
                        },
                    )
                }
            },
        )
    }

    composable(
        route = Routes.Deployments.detail,
        arguments = listOf(
            navArgument("namespace") { type = NavType.StringType },
            navArgument("name") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val namespace = Uri.decode(backStackEntry.arguments?.getString("namespace").orEmpty())
        val name = Uri.decode(backStackEntry.arguments?.getString("name").orEmpty())
        val viewModel: DeploymentDetailViewModel = hiltViewModel(backStackEntry)
        DeploymentDetailRoute(
            namespace = namespace,
            deploymentName = name,
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
        )
    }
}

fun deploymentDetailRoute(namespace: String, name: String): String {
    val encodedNamespace = Uri.encode(namespace)
    val encodedName = Uri.encode(name)
    return "deployment_detail/$encodedNamespace/$encodedName"
}
