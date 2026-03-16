package com.kubedroid.app.navigation.feature

import android.net.Uri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kubedroid.app.navigation.Routes
import com.kubedroid.feature.crd.model.CustomResourceDefinition
import com.kubedroid.feature.crd.ui.CrdListRoute
import com.kubedroid.feature.crd.ui.CrdListViewModel
import com.kubedroid.feature.crd.ui.CrdResourceDetailRoute
import com.kubedroid.feature.crd.ui.CrdResourceDetailViewModel
import com.kubedroid.feature.crd.ui.CrdResourceListRoute
import com.kubedroid.feature.crd.ui.CrdResourceListViewModel

fun NavGraphBuilder.crdGraph(navController: NavController) {
    composable(Routes.Crds.list) {
        val viewModel: CrdListViewModel = hiltViewModel()
        CrdListRoute(
            viewModel = viewModel,
            onCrdClick = { crd ->
                navController.navigate(crdResourcesRoute(crd))
            },
        )
    }

    composable(
        route = Routes.Crds.resources,
        arguments = listOf(
            navArgument("name") { type = NavType.StringType },
            navArgument("group") { type = NavType.StringType },
            navArgument("version") { type = NavType.StringType },
            navArgument("scope") { type = NavType.StringType },
            navArgument("kind") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val crd = backStackEntry.toCrdDefinition()
        val viewModel: CrdResourceListViewModel = hiltViewModel(backStackEntry)

        CrdResourceListRoute(
            crd = crd,
            viewModel = viewModel,
            onResourceClick = { item ->
                val namespace = if (crd.scope.equals("Cluster", ignoreCase = true)) {
                    ""
                } else {
                    item.namespace
                }
                navController.navigate(
                    crdResourceDetailRoute(
                        crd = crd,
                        namespace = namespace,
                        resourceName = item.name,
                    ),
                )
            },
        )
    }

    composable(
        route = Routes.Crds.resourceDetail,
        arguments = listOf(
            navArgument("name") { type = NavType.StringType },
            navArgument("group") { type = NavType.StringType },
            navArgument("version") { type = NavType.StringType },
            navArgument("scope") { type = NavType.StringType },
            navArgument("kind") { type = NavType.StringType },
            navArgument("resourceNamespace") { type = NavType.StringType },
            navArgument("resourceName") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val crd = backStackEntry.toCrdDefinition()
        val namespace = Uri.decode(backStackEntry.arguments?.getString("resourceNamespace").orEmpty())
        val resourceName = Uri.decode(backStackEntry.arguments?.getString("resourceName").orEmpty())
        val viewModel: CrdResourceDetailViewModel = hiltViewModel(backStackEntry)

        CrdResourceDetailRoute(
            crd = crd,
            name = resourceName,
            namespace = namespace,
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
        )
    }
}

fun crdResourcesRoute(crd: CustomResourceDefinition): String {
    val name = Uri.encode(crd.name)
    val group = Uri.encode(crd.group)
    val version = Uri.encode(crd.version)
    val scope = Uri.encode(crd.scope)
    val kind = Uri.encode(crd.kind)
    return "crd_resources/$name/$group/$version/$scope/$kind"
}

fun crdResourceDetailRoute(
    crd: CustomResourceDefinition,
    namespace: String,
    resourceName: String,
): String {
    val crdName = Uri.encode(crd.name)
    val crdGroup = Uri.encode(crd.group)
    val crdVersion = Uri.encode(crd.version)
    val crdScope = Uri.encode(crd.scope)
    val crdKind = Uri.encode(crd.kind)
    val encodedNamespace = Uri.encode(namespace)
    val encodedResourceName = Uri.encode(resourceName)
    return "crd_resource_detail/$crdName/$crdGroup/$crdVersion/$crdScope/$crdKind/$encodedNamespace/$encodedResourceName"
}

private fun NavBackStackEntry.toCrdDefinition(): CustomResourceDefinition {
    val args = checkNotNull(arguments)
    return CustomResourceDefinition(
        name = Uri.decode(args.getString("name").orEmpty()),
        group = Uri.decode(args.getString("group").orEmpty()),
        version = Uri.decode(args.getString("version").orEmpty()),
        scope = Uri.decode(args.getString("scope").orEmpty()),
        kind = Uri.decode(args.getString("kind").orEmpty()),
    )
}
