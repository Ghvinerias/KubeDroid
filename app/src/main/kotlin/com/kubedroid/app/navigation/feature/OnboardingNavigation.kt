package com.kubedroid.app.navigation.feature

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.kubedroid.app.navigation.Routes
import com.kubedroid.feature.onboarding.ui.OnboardingRoute
import com.kubedroid.feature.onboarding.ui.OnboardingViewModel

fun NavGraphBuilder.onboardingGraph(navController: NavController) {
    composable(Routes.EntryGate.root) {
        val viewModel: OnboardingViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(state.isCheckingCompletion, state.isOnboardingComplete) {
            if (!state.isCheckingCompletion) {
                val destination = if (state.isOnboardingComplete) {
                    Routes.Dashboard.root
                } else {
                    Routes.Onboarding.root
                }
                navController.navigate(destination) {
                    popUpTo(Routes.EntryGate.root) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }

    composable(Routes.Onboarding.root) {
        val viewModel: OnboardingViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(state.isOnboardingComplete) {
            if (state.isOnboardingComplete) {
                navController.navigate(Routes.Dashboard.root) {
                    popUpTo(Routes.Onboarding.root) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }

        OnboardingRoute(
            state = state,
            onStepChanged = viewModel::onStepChanged,
            onContinueFromWelcome = viewModel::continueFromWelcome,
            onImportOptionSelected = viewModel::onImportOptionSelected,
            onPickFileClick = viewModel::onPickFileClick,
            onScanQrClick = viewModel::onScanQrClick,
            onKubeconfigInputChange = viewModel::onKubeconfigInputChange,
            onSaveKubeconfig = viewModel::saveKubeconfig,
            onTestConnection = viewModel::testConnection,
            onFinish = viewModel::finishOnboarding,
        )
    }
}
