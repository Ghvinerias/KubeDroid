package com.kubedroid.feature.storage.ui

import com.kubedroid.feature.events.domain.model.ClusterEvent

enum class StorageTab {
    PVCS,
    PVS,
    STORAGE_CLASSES,
    CONFIG_MAPS,
    SECRETS,
}

enum class PvcBindingStatus {
    BOUND,
    UNBOUND,
}

data class PvcListItemUi(
    val name: String,
    val namespace: String,
    val capacity: String,
    val accessMode: String,
    val bindingStatus: PvcBindingStatus,
    val storageClass: String,
    val boundPvName: String?,
)

data class PvListItemUi(
    val name: String,
    val capacity: String,
    val reclaimPolicy: String,
    val status: String,
)

data class StorageClassListItemUi(
    val name: String,
    val provisioner: String,
    val reclaimPolicy: String,
    val volumeBindingMode: String,
)

data class ConfigMapListItemUi(
    val name: String,
    val namespace: String,
    val keyCount: Int,
    val age: String,
)

data class SecretListItemUi(
    val name: String,
    val namespace: String,
    val type: String,
    val keyCount: Int,
)

sealed interface StorageListState {
    data object Loading : StorageListState
    data object Success : StorageListState
    data object Empty : StorageListState
    data class Error(val cause: Throwable?) : StorageListState
}

data class StorageUiState(
    val selectedTab: StorageTab = StorageTab.PVCS,
    val selectedNamespace: String = DEFAULT_NAMESPACE,
    val selectedConfigMapNamespace: String = DEFAULT_NAMESPACE,
    val selectedSecretNamespace: String = DEFAULT_NAMESPACE,
    val namespaces: List<String> = emptyList(),
    val pvcState: StorageListState = StorageListState.Loading,
    val pvsState: StorageListState = StorageListState.Loading,
    val storageClassState: StorageListState = StorageListState.Loading,
    val configMapState: StorageListState = StorageListState.Loading,
    val secretState: StorageListState = StorageListState.Loading,
    val pvcs: List<PvcListItemUi> = emptyList(),
    val pvs: List<PvListItemUi> = emptyList(),
    val storageClasses: List<StorageClassListItemUi> = emptyList(),
    val configMaps: List<ConfigMapListItemUi> = emptyList(),
    val secrets: List<SecretListItemUi> = emptyList(),
)

sealed interface PvcDetailContentState {
    data object Loading : PvcDetailContentState
    data class Error(val cause: Throwable?) : PvcDetailContentState
    data class Success(
        val pvc: PvcListItemUi,
        val accessModes: List<String>,
        val events: List<ClusterEvent>,
    ) : PvcDetailContentState
}

data class PvcDetailUiState(
    val namespace: String = "",
    val name: String = "",
    val contentState: PvcDetailContentState = PvcDetailContentState.Loading,
)

sealed interface ConfigMapDetailContentState {
    data object Loading : ConfigMapDetailContentState
    data class Error(val cause: Throwable?) : ConfigMapDetailContentState
    data class Success(
        val namespace: String,
        val name: String,
        val entries: List<ConfigMapKeyValueEntryUi>,
        val hasPendingChanges: Boolean,
        val isSaving: Boolean,
    ) : ConfigMapDetailContentState
}

data class ConfigMapKeyValueEntryUi(
    val key: String,
    val value: String,
)

data class ConfigMapDetailUiState(
    val namespace: String = "",
    val name: String = "",
    val contentState: ConfigMapDetailContentState = ConfigMapDetailContentState.Loading,
)

sealed interface SecretDetailContentState {
    data object Loading : SecretDetailContentState
    data class Error(val cause: Throwable?) : SecretDetailContentState
    data class Success(
        val namespace: String,
        val name: String,
        val type: String,
        val keys: List<String>,
        val revealedValues: Map<String, String>,
        val revealingKeys: Set<String>,
        val remainingRevealSeconds: Map<String, Int>,
    ) : SecretDetailContentState
}

data class SecretDetailUiState(
    val namespace: String = "",
    val name: String = "",
    val contentState: SecretDetailContentState = SecretDetailContentState.Loading,
)

const val DEFAULT_NAMESPACE = "default"
