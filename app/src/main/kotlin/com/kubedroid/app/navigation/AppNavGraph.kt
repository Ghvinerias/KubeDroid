package com.kubedroid.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import com.kubedroid.app.R
import com.kubedroid.app.navigation.feature.crdGraph
import com.kubedroid.app.navigation.feature.deploymentsGraph
import com.kubedroid.app.navigation.feature.eventsGraph
import com.kubedroid.app.navigation.feature.helmGraph
import com.kubedroid.app.navigation.feature.networkGraph
import com.kubedroid.app.navigation.feature.nodesGraph
import com.kubedroid.app.navigation.feature.onboardingGraph
import com.kubedroid.app.navigation.feature.podsGraph
import com.kubedroid.app.navigation.feature.rbacGraph
import com.kubedroid.app.navigation.feature.settingsGraph
import com.kubedroid.app.navigation.feature.storageGraph
import com.kubedroid.app.ui.adaptive.rememberAdaptiveLayoutState
import com.kubedroid.app.ui.theme.KubeDroidTheme
import com.kubedroid.feature.settings.ui.DashboardScreen

val LocalScopedTokenCache = compositionLocalOf<ScopedTokenCache> {
    error("ScopedTokenCache not provided")
}

@Composable
fun AppNavGraph(
    navController: NavController,
    modifier: Modifier = Modifier,
    startDestination: String = Routes.EntryGate.root,
) {
    val hostController = navController as NavHostController
    val adaptiveLayoutState = rememberAdaptiveLayoutState()
    val currentBackStackEntry by hostController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    val topLevelDestinations = listOf(
        TopDestination(Routes.Dashboard.root, R.string.nav_clusters),
        TopDestination(Routes.Pods.list, R.string.nav_pods),
        TopDestination(Routes.Crds.list, R.string.nav_crds),
        TopDestination(Routes.Deployments.list, R.string.nav_deployments),
        TopDestination(Routes.Events.list, R.string.nav_events),
        TopDestination(Routes.Helm.list, R.string.nav_helm),
        TopDestination(Routes.Nodes.list, R.string.nav_nodes),
        TopDestination(Routes.Network.list, R.string.nav_network),
        TopDestination(Routes.Rbac.list, R.string.nav_rbac),
        TopDestination(Routes.Storage.list, R.string.nav_storage),
        TopDestination(Routes.Settings.root, R.string.nav_settings),
    )
    var isSideMenuOpen by rememberSaveable { mutableStateOf(true) }
    val inSideMenuScope = topLevelDestinations.any { destination ->
        isRouteSelected(destination.route, currentRoute)
    }
    LaunchedEffect(inSideMenuScope) {
        if (!inSideMenuScope) {
            isSideMenuOpen = true
        }
    }

    Scaffold { innerPadding ->
        val showSideMenu = inSideMenuScope && isSideMenuOpen
        BackHandler(enabled = showSideMenu) {
            isSideMenuOpen = false
        }
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val sideMenuWidth = (maxWidth * 0.45f).coerceAtMost(240.dp)
            if (showSideMenu) {
                AppSideMenu(
                    navController = hostController,
                    destinations = topLevelDestinations,
                    sideMenuWidth = sideMenuWidth,
                    onCloseClick = { isSideMenuOpen = false },
                    onNavigate = { route ->
                        isSideMenuOpen = false
                        hostController.navigate(route) {
                            popUpTo(hostController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.fillMaxHeight(),
                )
            }
            NavHost(
                navController = hostController,
                startDestination = startDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = if (showSideMenu) sideMenuWidth else 0.dp),
            ) {
                onboardingGraph(hostController)
                settingsGraph(hostController)
                podsGraph(hostController, adaptiveLayoutState)
                crdGraph(hostController)
                deploymentsGraph(hostController, adaptiveLayoutState)
                nodesGraph(hostController, adaptiveLayoutState)
                eventsGraph()
                helmGraph(adaptiveLayoutState)
                networkGraph(hostController)
                rbacGraph()
                storageGraph(hostController)
            }
            if (inSideMenuScope && !showSideMenu) {
                IconButton(
                    onClick = { isSideMenuOpen = true },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = stringResource(id = R.string.open_side_menu),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private data class TopDestination(
    val route: String,
    val titleResId: Int,
)

@Composable
private fun AppSideMenu(
    navController: NavHostController,
    destinations: List<TopDestination>,
    sideMenuWidth: androidx.compose.ui.unit.Dp,
    onCloseClick: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    val outlineColor = MaterialTheme.colorScheme.outline
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .then(modifier)
            .width(sideMenuWidth)
            .background(MaterialTheme.colorScheme.surface)
            .drawWithContent {
                drawLine(
                    color = outlineColor,
                    start = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
                drawContent()
            }
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onCloseClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(id = R.string.close_side_menu),
                    tint = inactiveColor,
                )
            }
        }
        destinations.forEach { item ->
            val selected = isRouteSelected(item.route, currentRoute)
            Row(
                modifier = Modifier
                    .clickable {
                        onNavigate(item.route)
                    }
                    .fillMaxWidth()
                    .background(if (selected) activeColor.copy(alpha = 0.12f) else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = null,
                    tint = if (selected) activeColor else inactiveColor,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(id = item.titleResId),
                    color = if (selected) activeColor else inactiveColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun isRouteSelected(
    route: String,
    currentRoute: String?,
): Boolean {
    return when (route) {
        Routes.Deployments.list -> {
            currentRoute == Routes.Deployments.list || currentRoute == Routes.Deployments.detail
        }
        Routes.Crds.list -> {
            currentRoute == Routes.Crds.list ||
                currentRoute == Routes.Crds.resources ||
                currentRoute == Routes.Crds.resourceDetail
        }
        Routes.Nodes.list -> {
            currentRoute == Routes.Nodes.list || currentRoute == Routes.Nodes.detail
        }
        Routes.Network.list -> {
            currentRoute == Routes.Network.list || currentRoute == Routes.Network.ingressDetail
        }
        Routes.Storage.list -> {
            currentRoute == Routes.Storage.list ||
                currentRoute == Routes.Storage.pvcDetail ||
                currentRoute == Routes.Storage.configMapDetail ||
                currentRoute == Routes.Storage.secretDetail
        }
        Routes.Pods.list -> {
            currentRoute == Routes.Pods.list ||
                currentRoute == Routes.Pods.resourceDetail ||
                currentRoute == Routes.Pods.resourceYamlEditor
        }
        Routes.Settings.root -> {
            currentRoute == Routes.Settings.root || currentRoute == Routes.Settings.deployManifest
        }
        else -> currentRoute == route
    }
}

@Preview(showBackground = true)
@Composable
private fun KubeDroidAppPreview() {
    KubeDroidTheme {
        DashboardScreen(
            state = com.kubedroid.feature.settings.ui.DashboardUiState.Empty,
            isRefreshing = false,
            onRefresh = {},
            onClusterClick = {},
            onAddClusterClick = {},
        )
    }
}
