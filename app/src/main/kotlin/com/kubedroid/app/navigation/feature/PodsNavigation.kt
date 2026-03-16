package com.kubedroid.app.navigation.feature

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kubedroid.app.R
import com.kubedroid.app.navigation.LocalScopedTokenCache
import com.kubedroid.app.navigation.Routes
import com.kubedroid.app.pods.PodResourceViewModel
import com.kubedroid.app.ui.adaptive.AdaptiveLayoutState
import com.kubedroid.feature.pods.exec.PodExecRequest
import com.kubedroid.feature.pods.exec.ui.ExecRoute
import com.kubedroid.feature.pods.exec.ui.ExecViewModel
import com.kubedroid.feature.pods.logs.PodLogRequest
import com.kubedroid.feature.pods.logs.ui.LogViewerRoute
import com.kubedroid.feature.pods.logs.ui.LogViewerViewModel
import com.kubedroid.feature.pods.portforward.ui.PortForwardRoute
import com.kubedroid.feature.pods.portforward.ui.PortForwardRouteRequest
import com.kubedroid.feature.pods.portforward.ui.PortForwardViewModel
import com.kubedroid.feature.pods.resources.ResourceListIntent
import com.kubedroid.feature.pods.resources.ResourceListItem
import com.kubedroid.feature.pods.resources.ResourceListRoute
import com.kubedroid.feature.pods.resources.ResourceListViewModel
import com.kubedroid.feature.resources.detail.ResourceDetailScreen
import com.kubedroid.feature.resources.detail.ResourceDetailUiState
import com.kubedroid.feature.resources.detail.YAMLEditorScreen
import java.util.UUID

fun NavGraphBuilder.podsGraph(
    navController: NavController,
    adaptiveLayoutState: AdaptiveLayoutState,
) {
    composable(Routes.Pods.list) {
        val scopedTokenCache = LocalScopedTokenCache.current
        val listViewModel: ResourceListViewModel = hiltViewModel()
        val detailViewModel: PodResourceViewModel = hiltViewModel()
        var selectedResource by rememberSaveable { mutableStateOf<ResourceListItem?>(null) }

        LaunchedEffect(selectedResource?.id) {
            val selected = selectedResource ?: return@LaunchedEffect
            detailViewModel.load(
                kind = selected.kind,
                name = selected.name,
                namespace = selected.namespace,
            )
            detailViewModel.refreshAccess()
        }

        val detailState by detailViewModel.uiState.collectAsStateWithLifecycle()
        ResourceListRoute(
            viewModel = listViewModel,
            onResourceClick = { item ->
                if (adaptiveLayoutState.isTwoPanePreferred) {
                    selectedResource = item
                } else {
                    navController.navigate(resourceDetailRoute(item.kind, item.namespace, item.name))
                }
            },
            useTwoPane = adaptiveLayoutState.isTwoPanePreferred,
            hingePadding = adaptiveLayoutState.hingePadding,
            detailPane = {
                val selected = selectedResource
                if (selected == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = stringResource(id = R.string.two_pane_select_resource))
                    }
                } else {
                    ResourceDetailScreen(
                        state = detailState.detailState,
                        selectedTab = detailState.selectedTab,
                        onTabSelected = detailViewModel::setTab,
                        onEditYamlClick = {
                            navController.navigate(resourceYamlEditorRoute(selected.kind, selected.namespace, selected.name))
                        },
                        onLogsClick = {
                            if (selected.kind.equals("Pod", ignoreCase = true)) {
                                detailState.access?.let { access ->
                                    access.bearerToken?.let(scopedTokenCache::store)
                                    navController.navigate(
                                        podLogsRoute(
                                            api = access.apiServer,
                                            namespace = selected.namespace,
                                            name = selected.name,
                                        ),
                                    )
                                }
                            }
                        },
                        onExecClick = {
                            if (selected.kind.equals("Pod", ignoreCase = true)) {
                                detailState.access?.let { access ->
                                    access.bearerToken?.let(scopedTokenCache::store)
                                    navController.navigate(
                                        podExecRoute(
                                            api = access.apiServer,
                                            namespace = selected.namespace,
                                            name = selected.name,
                                        ),
                                    )
                                }
                            }
                        },
                        onPortForwardClick = {
                            if (selected.kind.equals("Pod", ignoreCase = true)) {
                                detailState.access?.let { access ->
                                    access.bearerToken?.let(scopedTokenCache::store)
                                    navController.navigate(
                                        podPortForwardRoute(
                                            api = access.apiServer,
                                            namespace = selected.namespace,
                                            name = selected.name,
                                        ),
                                    )
                                }
                            }
                        },
                        onBackClick = { selectedResource = null },
                        onRetryClick = {
                            detailViewModel.load(
                                kind = selected.kind,
                                name = selected.name,
                                namespace = selected.namespace,
                            )
                        },
                        onRefreshClick = {
                            listViewModel.onIntent(ResourceListIntent.Retry)
                            detailViewModel.load(
                                kind = selected.kind,
                                name = selected.name,
                                namespace = selected.namespace,
                            )
                        },
                    )
                }
            },
        )
    }

    composable(
        route = Routes.Pods.resourceDetail,
        arguments = listOf(
            navArgument("kind") { type = NavType.StringType },
            navArgument("namespace") { type = NavType.StringType },
            navArgument("name") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val scopedTokenCache = LocalScopedTokenCache.current
        val kind = Uri.decode(backStackEntry.arguments?.getString("kind").orEmpty())
        val namespace = Uri.decode(backStackEntry.arguments?.getString("namespace").orEmpty())
        val name = Uri.decode(backStackEntry.arguments?.getString("name").orEmpty())

        val viewModel: PodResourceViewModel = hiltViewModel(backStackEntry)
        val state by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(kind, namespace, name) {
            viewModel.load(
                kind = kind,
                name = name,
                namespace = namespace,
            )
            viewModel.refreshAccess()
        }

        ResourceDetailScreen(
            state = state.detailState,
            selectedTab = state.selectedTab,
            onTabSelected = viewModel::setTab,
            onEditYamlClick = {
                navController.navigate(resourceYamlEditorRoute(kind, namespace, name))
            },
            onLogsClick = {
                if (kind.equals("Pod", ignoreCase = true)) {
                    state.access?.let { access ->
                        access.bearerToken?.let(scopedTokenCache::store)
                        navController.navigate(
                            podLogsRoute(
                                api = access.apiServer,
                                namespace = namespace,
                                name = name,
                            ),
                        )
                    }
                }
            },
            onExecClick = {
                if (kind.equals("Pod", ignoreCase = true)) {
                    state.access?.let { access ->
                        access.bearerToken?.let(scopedTokenCache::store)
                        navController.navigate(
                            podExecRoute(
                                api = access.apiServer,
                                namespace = namespace,
                                name = name,
                            ),
                        )
                    }
                }
            },
            onPortForwardClick = {
                if (kind.equals("Pod", ignoreCase = true)) {
                    state.access?.let { access ->
                        access.bearerToken?.let(scopedTokenCache::store)
                        navController.navigate(
                            podPortForwardRoute(
                                api = access.apiServer,
                                namespace = namespace,
                                name = name,
                            ),
                        )
                    }
                }
            },
            onBackClick = { navController.popBackStack() },
            onRetryClick = { viewModel.load(kind = kind, name = name, namespace = namespace) },
        )
    }

    composable(
        route = Routes.Pods.resourceYamlEditor,
        arguments = listOf(
            navArgument("kind") { type = NavType.StringType },
            navArgument("namespace") { type = NavType.StringType },
            navArgument("name") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val kind = Uri.decode(backStackEntry.arguments?.getString("kind").orEmpty())
        val namespace = Uri.decode(backStackEntry.arguments?.getString("namespace").orEmpty())
        val name = Uri.decode(backStackEntry.arguments?.getString("name").orEmpty())
        val viewModel: PodResourceViewModel = hiltViewModel(backStackEntry)
        val state by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(kind, namespace, name) {
            if (state.detailState !is ResourceDetailUiState.Content) {
                viewModel.load(kind = kind, name = name, namespace = namespace)
            }
        }

        val content = state.detailState as? ResourceDetailUiState.Content
        YAMLEditorScreen(
            yamlDraft = content?.yamlDraft.orEmpty(),
            isApplying = content?.isApplyingYaml == true,
            lastYamlEditResult = content?.lastYamlEditResult,
            applyError = state.yamlApplyError,
            onYamlDraftChange = viewModel::updateYamlDraft,
            onApplyClick = viewModel::applyYaml,
            onBackClick = { navController.popBackStack() },
        )
    }

    composable(
        route = Routes.Pods.logs,
        arguments = listOf(
            navArgument("api") { type = NavType.StringType },
            navArgument("namespace") { type = NavType.StringType },
            navArgument("name") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val scopedTokenCache = LocalScopedTokenCache.current
        val apiServer = Uri.decode(backStackEntry.arguments?.getString("api").orEmpty())
        val namespace = Uri.decode(backStackEntry.arguments?.getString("namespace").orEmpty())
        val name = Uri.decode(backStackEntry.arguments?.getString("name").orEmpty())
        val token = scopedTokenCache.get()
        val viewModel: LogViewerViewModel = hiltViewModel()

        LogViewerRoute(
            request = PodLogRequest(
                streamId = "$name-${UUID.randomUUID()}",
                apiServer = apiServer,
                namespace = namespace,
                podName = name,
                bearerToken = token,
            ),
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
        )
    }

    composable(
        route = Routes.Pods.exec,
        arguments = listOf(
            navArgument("api") { type = NavType.StringType },
            navArgument("namespace") { type = NavType.StringType },
            navArgument("name") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val scopedTokenCache = LocalScopedTokenCache.current
        val apiServer = Uri.decode(backStackEntry.arguments?.getString("api").orEmpty())
        val namespace = Uri.decode(backStackEntry.arguments?.getString("namespace").orEmpty())
        val name = Uri.decode(backStackEntry.arguments?.getString("name").orEmpty())
        val token = scopedTokenCache.get()
        val viewModel: ExecViewModel = hiltViewModel()

        ExecRoute(
            request = PodExecRequest(
                apiServer = apiServer,
                namespace = namespace,
                podName = name,
                command = listOf("sh"),
                bearerToken = token,
            ),
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
        )
    }

    composable(
        route = Routes.Pods.portForward,
        arguments = listOf(
            navArgument("api") { type = NavType.StringType },
            navArgument("namespace") { type = NavType.StringType },
            navArgument("name") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val scopedTokenCache = LocalScopedTokenCache.current
        val apiServer = Uri.decode(backStackEntry.arguments?.getString("api").orEmpty())
        val namespace = Uri.decode(backStackEntry.arguments?.getString("namespace").orEmpty())
        val name = Uri.decode(backStackEntry.arguments?.getString("name").orEmpty())
        val token = scopedTokenCache.get()
        val viewModel: PortForwardViewModel = hiltViewModel()

        PortForwardRoute(
            request = PortForwardRouteRequest(
                apiServer = apiServer,
                namespace = namespace,
                bearerToken = token,
                pods = listOf(name),
            ),
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
        )
    }
}

fun resourceDetailRoute(kind: String, namespace: String, name: String): String {
    val encodedKind = Uri.encode(kind)
    val encodedNamespace = Uri.encode(namespace)
    val encodedName = Uri.encode(name)
    return "resource_detail/$encodedKind/$encodedNamespace/$encodedName"
}

fun resourceYamlEditorRoute(kind: String, namespace: String, name: String): String {
    val encodedKind = Uri.encode(kind)
    val encodedNamespace = Uri.encode(namespace)
    val encodedName = Uri.encode(name)
    return "resource_yaml_editor/$encodedKind/$encodedNamespace/$encodedName"
}

fun podLogsRoute(api: String, namespace: String, name: String): String {
    val encodedApi = Uri.encode(api)
    val encodedNamespace = Uri.encode(namespace)
    val encodedName = Uri.encode(name)
    return "pod_logs/$encodedApi/$encodedNamespace/$encodedName"
}

fun podExecRoute(api: String, namespace: String, name: String): String {
    val encodedApi = Uri.encode(api)
    val encodedNamespace = Uri.encode(namespace)
    val encodedName = Uri.encode(name)
    return "pod_exec/$encodedApi/$encodedNamespace/$encodedName"
}

fun podPortForwardRoute(api: String, namespace: String, name: String): String {
    val encodedApi = Uri.encode(api)
    val encodedNamespace = Uri.encode(namespace)
    val encodedName = Uri.encode(name)
    return "pod_port_forward/$encodedApi/$encodedNamespace/$encodedName"
}
