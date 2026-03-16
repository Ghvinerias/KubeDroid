package com.kubedroid.feature.onboarding.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubedroid.core.network.connection.ClusterConnectionState
import com.kubedroid.feature.onboarding.ImportOption
import com.kubedroid.feature.onboarding.OnboardingState
import com.kubedroid.feature.onboarding.OnboardingStep
import com.kubedroid.feature.onboarding.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val PAGE_COUNT = 4

@Composable
fun OnboardingRoute(
    state: OnboardingState,
    onStepChanged: (OnboardingStep) -> Unit,
    onContinueFromWelcome: () -> Unit,
    onImportOptionSelected: (ImportOption) -> Unit,
    onPickFileClick: () -> Unit,
    onScanQrClick: () -> Unit,
    onKubeconfigInputChange: (String) -> Unit,
    onSaveKubeconfig: () -> Unit,
    onTestConnection: () -> Unit,
    onFinish: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = stepToPage(state.currentStep),
        pageCount = { PAGE_COUNT },
    )

    LaunchedEffect(state.currentStep) {
        val target = stepToPage(state.currentStep)
        if (pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .map(::pageToStep)
            .distinctUntilChanged()
            .collectLatest(onStepChanged)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        PagerIndicator(
            currentPage = pagerState.currentPage,
            pageCount = PAGE_COUNT,
        )

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (pageToStep(page)) {
                OnboardingStep.Welcome -> {
                    WelcomePage(onContinue = onContinueFromWelcome)
                }

                OnboardingStep.ImportKubeconfig -> {
                    ImportPage(
                        selectedImportOption = state.selectedImportOption,
                        kubeconfigInput = state.kubeconfigInput,
                        isSavingKubeconfig = state.isSavingKubeconfig,
                        onImportOptionSelected = onImportOptionSelected,
                        onPickFileClick = onPickFileClick,
                        onScanQrClick = onScanQrClick,
                        onKubeconfigInputChange = onKubeconfigInputChange,
                        onSaveKubeconfig = onSaveKubeconfig,
                    )
                }

                OnboardingStep.TestConnection -> {
                    TestConnectionPage(
                        connectionState = state.connectionState,
                        onTestConnection = onTestConnection,
                    )
                }

                OnboardingStep.Done -> {
                    DonePage(
                        state = state,
                        onFinish = onFinish,
                    )
                }
            }
        }
    }
}

@Composable
private fun PagerIndicator(
    currentPage: Int,
    pageCount: Int,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        repeat(pageCount) { index ->
            val isSelected = currentPage == index
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .weight(1f)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
            )
        }
    }
}

@Composable
private fun WelcomePage(
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.onboarding_logo_text),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = stringResource(R.string.onboarding_tagline),
                style = MaterialTheme.typography.titleLarge,
            )
            FeatureHighlight(title = stringResource(R.string.onboarding_feature_one))
            FeatureHighlight(title = stringResource(R.string.onboarding_feature_two))
            FeatureHighlight(title = stringResource(R.string.onboarding_feature_three))
        }

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.onboarding_continue))
        }
    }
}

@Composable
private fun FeatureHighlight(
    title: String,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ImportPage(
    selectedImportOption: ImportOption,
    kubeconfigInput: String,
    isSavingKubeconfig: Boolean,
    onImportOptionSelected: (ImportOption) -> Unit,
    onPickFileClick: () -> Unit,
    onScanQrClick: () -> Unit,
    onKubeconfigInputChange: (String) -> Unit,
    onSaveKubeconfig: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_import_title),
            style = MaterialTheme.typography.titleLarge,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { onImportOptionSelected(ImportOption.PasteText) },
                label = { Text(stringResource(R.string.onboarding_import_option_paste)) },
            )
            AssistChip(
                onClick = {
                    onImportOptionSelected(ImportOption.PickFile)
                    onPickFileClick()
                },
                label = { Text(stringResource(R.string.onboarding_import_option_file)) },
            )
            AssistChip(
                onClick = {
                    onImportOptionSelected(ImportOption.ScanQr)
                    onScanQrClick()
                },
                label = { Text(stringResource(R.string.onboarding_import_option_qr)) },
            )
        }

        if (selectedImportOption == ImportOption.PasteText) {
            OutlinedTextField(
                value = kubeconfigInput,
                onValueChange = onKubeconfigInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text(stringResource(R.string.onboarding_kubeconfig_label)) },
                placeholder = { Text(stringResource(R.string.onboarding_kubeconfig_placeholder)) },
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_import_placeholder),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Button(
            onClick = onSaveKubeconfig,
            enabled = !isSavingKubeconfig,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isSavingKubeconfig) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(text = stringResource(R.string.onboarding_save_kubeconfig))
            }
        }
    }
}

@Composable
private fun TestConnectionPage(
    connectionState: ClusterConnectionState,
    onTestConnection: () -> Unit,
) {
    val isConnecting = connectionState is ClusterConnectionState.Connecting
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedConnectionIndicator(isConnecting = isConnecting)
            Text(
                text = when (connectionState) {
                    is ClusterConnectionState.Connected -> stringResource(
                        R.string.onboarding_connection_success,
                        connectionState.contextName,
                    )

                    is ClusterConnectionState.Failed -> stringResource(
                        R.string.onboarding_connection_failed,
                        connectionState.reason.orEmpty().ifBlank {
                            stringResource(R.string.onboarding_connection_failed_generic)
                        },
                    )

                    is ClusterConnectionState.Connecting -> stringResource(
                        R.string.onboarding_connection_connecting,
                        connectionState.contextName,
                    )

                    ClusterConnectionState.Disconnected -> stringResource(R.string.onboarding_test_connection_description)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        OutlinedButton(
            onClick = onTestConnection,
            enabled = !isConnecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.onboarding_test_connection))
        }
    }
}

@Composable
private fun AnimatedConnectionIndicator(
    isConnecting: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "connecting-indicator")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connecting-alpha",
    )
    val alpha = if (isConnecting) pulse else 1f

    Box(
        modifier = Modifier
            .size(64.dp)
            .alpha(alpha),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            strokeWidth = 4.dp,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun DonePage(
    state: OnboardingState,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.onboarding_done_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(
                    R.string.onboarding_done_summary,
                    state.clusterSummary.contextName.ifBlank { stringResource(R.string.onboarding_done_unknown_cluster) },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (state.clusterSummary.server.isNotBlank()) {
                Text(
                    text = stringResource(R.string.onboarding_done_server, state.clusterSummary.server),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.onboarding_finish))
        }
    }
}

private fun stepToPage(step: OnboardingStep): Int {
    return when (step) {
        OnboardingStep.Welcome -> 0
        OnboardingStep.ImportKubeconfig -> 1
        OnboardingStep.TestConnection -> 2
        OnboardingStep.Done -> 3
    }
}

private fun pageToStep(page: Int): OnboardingStep {
    return when (page.coerceIn(0, PAGE_COUNT - 1)) {
        0 -> OnboardingStep.Welcome
        1 -> OnboardingStep.ImportKubeconfig
        2 -> OnboardingStep.TestConnection
        else -> OnboardingStep.Done
    }
}
