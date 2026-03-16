package com.kubedroid.feature.deployments.ui

import com.kubedroid.core.network.deployments.Deployment
import com.kubedroid.core.network.deployments.RolloutRevision

data class DeploymentListItem(
    val stableKey: String,
    val name: String,
    val namespace: String,
    val desiredReplicas: Int,
    val readyReplicas: Int,
    val updatedReplicas: Int,
    val availableReplicas: Int,
    val observedGeneration: Long?,
)

enum class DeploymentDetailTab {
    OVERVIEW,
    ROLLOUT_HISTORY,
}

sealed interface DeploymentListContentState {
    data object Loading : DeploymentListContentState
    data object Empty : DeploymentListContentState
    data class Success(val items: List<DeploymentListItem>) : DeploymentListContentState
    data class Error(val cause: Throwable?) : DeploymentListContentState
}

sealed interface RolloutHistoryUiState {
    data object Loading : RolloutHistoryUiState
    data object Empty : RolloutHistoryUiState
    data class Success(val revisions: List<RolloutRevision>) : RolloutHistoryUiState
    data class Error(val cause: Throwable?) : RolloutHistoryUiState
}

data class DeploymentListUiState(
    val namespace: String = DEFAULT_NAMESPACE,
    val availableNamespaces: List<String> = emptyList(),
    val listState: DeploymentListContentState = DeploymentListContentState.Loading,
    val isDataFromCache: Boolean = false,
    val lastUpdatedAtEpochMillis: Long? = null,
)

data class DeploymentDetailUiState(
    val namespace: String = DEFAULT_NAMESPACE,
    val deploymentName: String = "",
    val deployment: Deployment? = null,
    val selectedTab: DeploymentDetailTab = DeploymentDetailTab.OVERVIEW,
    val rolloutHistoryState: RolloutHistoryUiState = RolloutHistoryUiState.Loading,
    val isScaleDialogVisible: Boolean = false,
    val scaleReplicas: Int = 0,
    val isRolloutPaused: Boolean = false,
    val isActionInProgress: Boolean = false,
    val actionMessage: DeploymentActionMessage? = null,
)

sealed interface DeploymentActionMessage {
    data class Success(val action: DeploymentUserAction) : DeploymentActionMessage
    data class Failure(
        val action: DeploymentUserAction,
        val error: DeploymentActionError,
    ) : DeploymentActionMessage
}

enum class DeploymentUserAction {
    SCALE,
    PAUSE,
    RESUME,
    ROLLBACK,
}

enum class DeploymentActionError {
    INVALID_REPLICA_COUNT,
    NOT_FOUND,
    FORBIDDEN,
    CONFLICT,
    UNKNOWN,
}

internal const val DEFAULT_NAMESPACE = "default"

internal fun Deployment.toListItem(): DeploymentListItem = DeploymentListItem(
    stableKey = "$namespace/$name",
    name = name,
    namespace = namespace,
    desiredReplicas = desiredReplicas,
    readyReplicas = readyReplicas,
    updatedReplicas = updatedReplicas,
    availableReplicas = availableReplicas,
    observedGeneration = observedGeneration,
)
