package com.kubedroid.app.navigation.feature

import android.net.Uri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kubedroid.app.navigation.Routes
import com.kubedroid.feature.storage.ui.ConfigMapDetailRoute
import com.kubedroid.feature.storage.ui.ConfigMapDetailViewModel
import com.kubedroid.feature.storage.ui.PvcDetailRoute
import com.kubedroid.feature.storage.ui.PvcDetailViewModel
import com.kubedroid.feature.storage.ui.SecretDetailRoute
import com.kubedroid.feature.storage.ui.SecretDetailViewModel
import com.kubedroid.feature.storage.ui.StorageRoute
import com.kubedroid.feature.storage.ui.StorageViewModel

fun NavGraphBuilder.storageGraph(navController: NavController) {
    composable(Routes.Storage.list) {
        val viewModel: StorageViewModel = hiltViewModel()
        StorageRoute(
            viewModel = viewModel,
            onPvcClick = { namespace, pvcName ->
                navController.navigate(
                    pvcDetailRoute(
                        namespace = namespace,
                        name = pvcName,
                    ),
                )
            },
            onConfigMapClick = { namespace, configMapName ->
                navController.navigate(
                    configMapDetailRoute(
                        namespace = namespace,
                        name = configMapName,
                    ),
                )
            },
            onSecretClick = { namespace, secretName ->
                navController.navigate(
                    secretDetailRoute(
                        namespace = namespace,
                        name = secretName,
                    ),
                )
            },
        )
    }

    composable(
        route = Routes.Storage.pvcDetail,
        arguments = listOf(
            navArgument("namespace") { type = NavType.StringType },
            navArgument("name") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val viewModel: PvcDetailViewModel = hiltViewModel(backStackEntry)
        PvcDetailRoute(
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
        )
    }

    composable(
        route = Routes.Storage.configMapDetail,
        arguments = listOf(
            navArgument("namespace") { type = NavType.StringType },
            navArgument("name") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val viewModel: ConfigMapDetailViewModel = hiltViewModel(backStackEntry)
        ConfigMapDetailRoute(
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
        )
    }

    composable(
        route = Routes.Storage.secretDetail,
        arguments = listOf(
            navArgument("namespace") { type = NavType.StringType },
            navArgument("name") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val viewModel: SecretDetailViewModel = hiltViewModel(backStackEntry)
        SecretDetailRoute(
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
        )
    }
}

fun pvcDetailRoute(namespace: String, name: String): String {
    val encodedNamespace = Uri.encode(namespace)
    val encodedName = Uri.encode(name)
    return "storage_pvc_detail/$encodedNamespace/$encodedName"
}

fun configMapDetailRoute(namespace: String, name: String): String {
    val encodedNamespace = Uri.encode(namespace)
    val encodedName = Uri.encode(name)
    return "storage_config_map_detail/$encodedNamespace/$encodedName"
}

fun secretDetailRoute(namespace: String, name: String): String {
    val encodedNamespace = Uri.encode(namespace)
    val encodedName = Uri.encode(name)
    return "storage_secret_detail/$encodedNamespace/$encodedName"
}
