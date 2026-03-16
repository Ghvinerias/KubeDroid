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
import com.kubedroid.feature.nodes.ui.NodeDetailRoute
import com.kubedroid.feature.nodes.ui.NodeDetailViewModel
import com.kubedroid.feature.nodes.ui.NodeListRoute
import com.kubedroid.feature.nodes.ui.NodeListViewModel

fun NavGraphBuilder.nodesGraph(
    navController: NavController,
    adaptiveLayoutState: AdaptiveLayoutState,
) {
    composable(Routes.Nodes.list) {
        val viewModel: NodeListViewModel = hiltViewModel()
        var selectedNodeName by rememberSaveable { mutableStateOf<String?>(null) }
        NodeListRoute(
            viewModel = viewModel,
            onNodeClick = { nodeName ->
                if (adaptiveLayoutState.isTwoPanePreferred) {
                    selectedNodeName = nodeName
                } else {
                    navController.navigate(nodeDetailRoute(nodeName))
                }
            },
            useTwoPane = adaptiveLayoutState.isTwoPanePreferred,
            hingePadding = adaptiveLayoutState.hingePadding,
            detailPane = {
                val nodeName = selectedNodeName
                if (nodeName.isNullOrBlank()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = stringResource(id = R.string.two_pane_select_node))
                    }
                } else {
                    val detailViewModel: NodeDetailViewModel = hiltViewModel()
                    NodeDetailRoute(
                        nodeName = nodeName,
                        viewModel = detailViewModel,
                        onBackClick = { selectedNodeName = null },
                    )
                }
            },
        )
    }

    composable(
        route = Routes.Nodes.detail,
        arguments = listOf(navArgument("name") { type = NavType.StringType }),
    ) { backStackEntry ->
        val nodeName = Uri.decode(backStackEntry.arguments?.getString("name").orEmpty())
        val viewModel: NodeDetailViewModel = hiltViewModel(backStackEntry)
        NodeDetailRoute(
            nodeName = nodeName,
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
        )
    }
}

fun nodeDetailRoute(name: String): String {
    val encodedName = Uri.encode(name)
    return "node_detail/$encodedName"
}
