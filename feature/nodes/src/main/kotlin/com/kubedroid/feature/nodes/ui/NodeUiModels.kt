package com.kubedroid.feature.nodes.ui

import com.kubedroid.core.network.nodes.Node

enum class NodeDetailTab {
    INFO,
    CONDITIONS,
    PODS,
}

enum class NodeActionType {
    CORDON,
    UNCORDON,
    DRAIN,
}

data class NodeListItem(
    val stableKey: String,
    val name: String,
    val roles: List<String>,
    val ready: Boolean,
    val unschedulable: Boolean,
    val cpuPercent: Float?,
    val memoryPercent: Float?,
)

sealed interface NodeListContentState {
    data object Loading : NodeListContentState
    data object Empty : NodeListContentState
    data class Error(val cause: Throwable?) : NodeListContentState
    data class Success(val items: List<NodeListItem>) : NodeListContentState
}

data class NodeListUiState(
    val listState: NodeListContentState = NodeListContentState.Loading,
    val isDataFromCache: Boolean = false,
    val lastUpdatedAtEpochMillis: Long? = null,
)

data class NodeDetailUiState(
    val nodeName: String = "",
    val selectedTab: NodeDetailTab = NodeDetailTab.INFO,
    val node: Node? = null,
    val isLoading: Boolean = true,
    val error: Throwable? = null,
    val isActionInProgress: Boolean = false,
    val activeConfirmation: NodeActionType? = null,
    val actionMessage: NodeActionMessage? = null,
    val drainState: DrainProgressUiState = DrainProgressUiState(),
)

data class DrainProgressUiState(
    val isVisible: Boolean = false,
    val nodeName: String = "",
    val totalPods: Int = 0,
    val evictedPods: Int = 0,
    val events: List<DrainProgressEventUi> = emptyList(),
    val isCompleted: Boolean = false,
)

data class DrainProgressEventUi(
    val messageType: DrainEventMessageType,
    val namespace: String?,
    val podName: String?,
    val attempt: Int?,
    val retryDelayMillis: Long?,
)

enum class DrainEventMessageType {
    STARTED,
    EVICTION_ATTEMPT,
    WAITING_ON_PDB,
    POD_EVICTED,
    COMPLETED_SUCCESS,
    COMPLETED_NOT_FOUND,
    COMPLETED_FORBIDDEN,
    COMPLETED_CONFLICT,
    COMPLETED_FAILURE,
}

sealed interface NodeActionMessage {
    data class Success(val action: NodeActionType) : NodeActionMessage
    data class Failure(val action: NodeActionType, val error: NodeActionError) : NodeActionMessage
}

enum class NodeActionError {
    NOT_FOUND,
    FORBIDDEN,
    CONFLICT,
    UNKNOWN,
}
