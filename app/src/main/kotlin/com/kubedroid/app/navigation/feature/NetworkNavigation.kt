package com.kubedroid.app.navigation.feature

import android.net.Uri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kubedroid.app.navigation.Routes
import com.kubedroid.feature.network.ui.IngressDetailRoute
import com.kubedroid.feature.network.ui.NetworkRoute
import com.kubedroid.feature.network.ui.NetworkViewModel

fun NavGraphBuilder.networkGraph(navController: NavController) {
    composable(Routes.Network.list) {
        val viewModel: NetworkViewModel = hiltViewModel()
        NetworkRoute(
            viewModel = viewModel,
            onIngressClick = { namespace, ingressName ->
                navController.navigate(
                    ingressDetailRoute(
                        namespace = namespace,
                        ingressName = ingressName,
                    ),
                )
            },
        )
    }

    composable(
        route = Routes.Network.ingressDetail,
        arguments = listOf(
            navArgument("namespace") { type = NavType.StringType },
            navArgument("ingressName") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val namespace = Uri.decode(backStackEntry.arguments?.getString("namespace").orEmpty())
        val ingressName = Uri.decode(backStackEntry.arguments?.getString("ingressName").orEmpty())
        val viewModel: NetworkViewModel = hiltViewModel(backStackEntry)
        IngressDetailRoute(
            namespace = namespace,
            ingressName = ingressName,
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
        )
    }
}

fun ingressDetailRoute(namespace: String, ingressName: String): String {
    val encodedNamespace = Uri.encode(namespace)
    val encodedIngressName = Uri.encode(ingressName)
    return "ingress_detail/$encodedNamespace/$encodedIngressName"
}
