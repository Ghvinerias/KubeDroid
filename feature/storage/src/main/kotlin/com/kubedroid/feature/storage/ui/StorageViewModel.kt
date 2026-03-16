package com.kubedroid.feature.storage.ui

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.network.config.BiometricToken
import com.kubedroid.core.network.config.ConfigMap
import com.kubedroid.core.network.config.ConfigMapRepository
import com.kubedroid.core.network.namespace.NamespaceStore
import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import com.kubedroid.core.network.pods.NamespaceRepository
import com.kubedroid.core.network.config.Secret
import com.kubedroid.core.network.config.SecretRepository
import com.kubedroid.core.network.storage.PersistentVolume
import com.kubedroid.core.network.storage.PersistentVolumeClaim
import com.kubedroid.core.network.storage.StorageClass
import com.kubedroid.feature.events.domain.repository.EventsRepository
import com.kubedroid.feature.storage.StorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val repository: StorageRepository,
    private val configMapRepository: ConfigMapRepository,
    private val secretRepository: SecretRepository,
    private val namespaceRepository: NamespaceRepository,
    private val namespaceStore: NamespaceStore,
) : ViewModel() {

    private companion object {
        const val TAG = "StorageViewModel"
    }

    private val _uiState = MutableStateFlow(StorageUiState())
    val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

    init {
        observeSelectedNamespace()
        refresh()
    }

    fun onTabSelected(tab: StorageTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun onNamespaceChange(namespace: String) {
        val normalized = namespace.trim().ifEmpty { DEFAULT_NAMESPACE }
        viewModelScope.launch {
            namespaceStore.setNamespace(normalized)
        }
    }

    fun onConfigMapNamespaceChange(namespace: String) {
        val normalized = namespace.trim().ifEmpty { DEFAULT_NAMESPACE }
        viewModelScope.launch {
            namespaceStore.setNamespace(normalized)
        }
    }

    fun onSecretNamespaceChange(namespace: String) {
        val normalized = namespace.trim().ifEmpty { DEFAULT_NAMESPACE }
        viewModelScope.launch {
            namespaceStore.setNamespace(normalized)
        }
    }

    fun refresh() {
        loadNamespaces()
        loadPvcs()
        loadPvs()
        loadStorageClasses()
        loadConfigMaps()
        loadSecrets()
    }

    private fun loadNamespaces() {
        viewModelScope.launch {
            namespaceRepository.listNamespaces().fold(
                onSuccess = { namespaces ->
                    val normalized = namespaces
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sorted()
                    _uiState.update { current ->
                        current.copy(
                            namespaces = normalized,
                            selectedNamespace = current.selectedNamespace.takeIf {
                                normalized.contains(it) || it.isAllNamespacesSelection()
                            } ?: DEFAULT_NAMESPACE,
                            selectedConfigMapNamespace = current.selectedConfigMapNamespace.takeIf {
                                normalized.contains(it) || it.isAllNamespacesSelection()
                            } ?: DEFAULT_NAMESPACE,
                            selectedSecretNamespace = current.selectedSecretNamespace.takeIf {
                                normalized.contains(it) || it.isAllNamespacesSelection()
                            } ?: DEFAULT_NAMESPACE,
                        )
                    }
                    val selectedNamespace = _uiState.value.selectedNamespace
                    if (normalized.isNotEmpty() && selectedNamespace !in normalized && !selectedNamespace.isAllNamespacesSelection()) {
                        val fallbackNamespace = if (DEFAULT_NAMESPACE in normalized) {
                            DEFAULT_NAMESPACE
                        } else {
                            normalized.first()
                        }
                        viewModelScope.launch {
                            namespaceStore.setNamespace(fallbackNamespace)
                        }
                    }
                },
                onFailure = {
                    _uiState.update { current -> current.copy(namespaces = emptyList()) }
                },
            )
        }
    }

    private fun observeSelectedNamespace() {
        viewModelScope.launch {
            namespaceStore.selectedNamespace
                .distinctUntilChanged()
                .collect { namespace ->
                    _uiState.update {
                        it.copy(
                            selectedNamespace = namespace,
                            selectedConfigMapNamespace = namespace,
                            selectedSecretNamespace = namespace,
                        )
                    }
                    loadPvcs()
                    loadConfigMaps()
                    loadSecrets()
                }
        }
    }

    private fun loadPvcs() {
        val namespace = _uiState.value.selectedNamespace
        _uiState.update { it.copy(pvcState = StorageListState.Loading) }

        viewModelScope.launch {
            repository.listPvcs(namespace)
                .fold(
                    onSuccess = { pvcs ->
                        _uiState.update {
                            it.copy(
                                pvcs = pvcs.toPvcUi(),
                                pvcState = if (pvcs.isEmpty()) StorageListState.Empty else StorageListState.Success,
                            )
                        }
                    },
                    onFailure = { throwable ->
                        Log.e(TAG, "Failed to load PVCs", throwable)
                        _uiState.update { it.copy(pvcState = StorageListState.Error(cause = throwable)) }
                    },
                )
        }
    }

    private fun loadPvs() {
        _uiState.update { it.copy(pvsState = StorageListState.Loading) }

        viewModelScope.launch {
            repository.listPvs()
                .fold(
                    onSuccess = { pvs ->
                        _uiState.update {
                            it.copy(
                                pvs = pvs.toPvUi(),
                                pvsState = if (pvs.isEmpty()) StorageListState.Empty else StorageListState.Success,
                            )
                        }
                    },
                    onFailure = { throwable ->
                        Log.e(TAG, "Failed to load PVs", throwable)
                        _uiState.update { it.copy(pvsState = StorageListState.Error(cause = throwable)) }
                    },
                )
        }
    }

    private fun loadStorageClasses() {
        _uiState.update { it.copy(storageClassState = StorageListState.Loading) }

        viewModelScope.launch {
            repository.listStorageClasses()
                .fold(
                    onSuccess = { classes ->
                        _uiState.update {
                            it.copy(
                                storageClasses = classes.toStorageClassUi(),
                                storageClassState = if (classes.isEmpty()) {
                                    StorageListState.Empty
                                } else {
                                    StorageListState.Success
                                },
                            )
                        }
                    },
                    onFailure = { throwable ->
                        Log.e(TAG, "Failed to load StorageClasses", throwable)
                        _uiState.update {
                            it.copy(storageClassState = StorageListState.Error(cause = throwable))
                        }
                    },
                )
        }
    }

    private fun loadConfigMaps() {
        val namespace = _uiState.value.selectedConfigMapNamespace
        _uiState.update { it.copy(configMapState = StorageListState.Loading) }

        viewModelScope.launch {
            configMapRepository.list(namespace).fold(
                onSuccess = { configMaps ->
                    _uiState.update {
                        it.copy(
                            configMaps = configMaps.toConfigMapUi(),
                            configMapState = if (configMaps.isEmpty()) StorageListState.Empty else StorageListState.Success,
                        )
                    }
                },
                onFailure = { throwable ->
                    Log.e(TAG, "Failed to load ConfigMaps", throwable)
                    _uiState.update { it.copy(configMapState = StorageListState.Error(cause = throwable)) }
                },
            )
        }
    }

    private fun loadSecrets() {
        val namespace = _uiState.value.selectedSecretNamespace
        _uiState.update { it.copy(secretState = StorageListState.Loading) }

        viewModelScope.launch {
            secretRepository.list(namespace).fold(
                onSuccess = { secrets ->
                    _uiState.update {
                        it.copy(
                            secrets = secrets.toSecretUi(),
                            secretState = if (secrets.isEmpty()) StorageListState.Empty else StorageListState.Success,
                        )
                    }
                },
                onFailure = { throwable ->
                    Log.e(TAG, "Failed to load Secrets", throwable)
                    _uiState.update { it.copy(secretState = StorageListState.Error(cause = throwable)) }
                },
            )
        }
    }
}

@HiltViewModel
class PvcDetailViewModel @Inject constructor(
    private val storageRepository: StorageRepository,
    private val eventsRepository: EventsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private companion object {
        const val TAG = "PvcDetailViewModel"
    }

    private val namespace: String = savedStateHandle.get<String>("namespace").orEmpty()
    private val name: String = savedStateHandle.get<String>("name").orEmpty()

    private val _uiState = MutableStateFlow(
        PvcDetailUiState(
            namespace = namespace,
            name = name,
            contentState = PvcDetailContentState.Loading,
        ),
    )
    val uiState: StateFlow<PvcDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(contentState = PvcDetailContentState.Loading) }

        viewModelScope.launch {
            val pvcResult = storageRepository.listPvcs(namespace)
            val eventsResult = eventsRepository.getEventsByObject(
                kind = PVC_KIND,
                name = name,
                namespace = namespace,
            )

            pvcResult.fold(
                onSuccess = { pvcs ->
                    val pvc = pvcs.firstOrNull { it.name == name }
                    if (pvc == null) {
                        _uiState.update {
                            it.copy(
                                contentState = PvcDetailContentState.Error(
                                    cause = IllegalStateException("PersistentVolumeClaim not found"),
                                ),
                            )
                        }
                        return@launch
                    }

                    val events = eventsResult.getOrElse { throwable ->
                        Log.e(TAG, "Failed to load PVC events", throwable)
                        emptyList()
                    }

                    _uiState.update {
                        it.copy(
                            contentState = PvcDetailContentState.Success(
                                pvc = pvc.toUi(),
                                accessModes = pvc.accessModes,
                                events = events,
                            ),
                        )
                    }
                },
                onFailure = { throwable ->
                    Log.e(TAG, "Failed to load PVC detail", throwable)
                    _uiState.update { it.copy(contentState = PvcDetailContentState.Error(cause = throwable)) }
                },
            )
        }
    }
}

@HiltViewModel
class ConfigMapDetailViewModel @Inject constructor(
    private val configMapRepository: ConfigMapRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private companion object {
        const val TAG = "ConfigMapDetailViewModel"
    }

    private val namespace: String = savedStateHandle.get<String>("namespace").orEmpty()
    private val name: String = savedStateHandle.get<String>("name").orEmpty()
    private var originalData: Map<String, String> = emptyMap()
    private var editedData: Map<String, String> = emptyMap()
    private var isSaving: Boolean = false

    private val _uiState = MutableStateFlow(
        ConfigMapDetailUiState(
            namespace = namespace,
            name = name,
            contentState = ConfigMapDetailContentState.Loading,
        ),
    )
    val uiState: StateFlow<ConfigMapDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(contentState = ConfigMapDetailContentState.Loading) }
        viewModelScope.launch {
            configMapRepository.get(name = name, namespace = namespace).fold(
                onSuccess = { configMap ->
                    originalData = configMap.data
                    editedData = configMap.data
                    isSaving = false
                    emitConfigMapSuccess()
                },
                onFailure = { throwable ->
                    Log.e(TAG, "Failed to load ConfigMap detail", throwable)
                    _uiState.update { it.copy(contentState = ConfigMapDetailContentState.Error(cause = throwable)) }
                },
            )
        }
    }

    fun onValueChange(key: String, value: String) {
        editedData = editedData.toMutableMap().apply { put(key, value) }
        emitConfigMapSuccess()
    }

    fun save() {
        if (isSaving || editedData == originalData) return

        isSaving = true
        emitConfigMapSuccess()
        viewModelScope.launch {
            configMapRepository.update(
                ConfigMap(
                    name = name,
                    namespace = namespace,
                    data = editedData,
                    age = "",
                ),
            ).fold(
                onSuccess = { updated ->
                    originalData = updated.data
                    editedData = updated.data
                    isSaving = false
                    emitConfigMapSuccess()
                },
                onFailure = { throwable ->
                    Log.e(TAG, "Failed to update ConfigMap", throwable)
                    isSaving = false
                    _uiState.update { it.copy(contentState = ConfigMapDetailContentState.Error(cause = throwable)) }
                },
            )
        }
    }

    private fun emitConfigMapSuccess() {
        _uiState.update {
            it.copy(
                contentState = ConfigMapDetailContentState.Success(
                    namespace = namespace,
                    name = name,
                    entries = editedData.entries
                        .sortedBy { entry -> entry.key }
                        .map { entry ->
                            ConfigMapKeyValueEntryUi(
                                key = entry.key,
                                value = entry.value,
                            )
                        },
                    hasPendingChanges = editedData != originalData,
                    isSaving = isSaving,
                ),
            )
        }
    }
}

@HiltViewModel
class SecretDetailViewModel @Inject constructor(
    private val secretRepository: SecretRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private companion object {
        const val TAG = "SecretDetailViewModel"
        const val REVEAL_AUTO_HIDE_MILLIS = 30_000L
    }

    private val namespace: String = savedStateHandle.get<String>("namespace").orEmpty()
    private val name: String = savedStateHandle.get<String>("name").orEmpty()
    private var keys: List<String> = emptyList()
    private var secretType: String = ""
    private val revealedValues = mutableMapOf<String, String>()
    private val revealingKeys = mutableSetOf<String>()
    private val remainingRevealSeconds = mutableMapOf<String, Int>()
    private val autoHideJobs = mutableMapOf<String, Job>()

    private val _uiState = MutableStateFlow(
        SecretDetailUiState(
            namespace = namespace,
            name = name,
            contentState = SecretDetailContentState.Loading,
        ),
    )
    val uiState: StateFlow<SecretDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        hideAllValues()
        _uiState.update { it.copy(contentState = SecretDetailContentState.Loading) }
        viewModelScope.launch {
            secretRepository.get(name = name, namespace = namespace).fold(
                onSuccess = { secret ->
                    keys = secret.dataKeys.sorted()
                    secretType = secret.type
                    emitSecretSuccess()
                },
                onFailure = { throwable ->
                    Log.e(TAG, "Failed to load Secret detail: ${throwable.message}")
                    _uiState.update { it.copy(contentState = SecretDetailContentState.Error(cause = throwable)) }
                },
            )
        }
    }

    fun revealValue(key: String, biometricToken: BiometricToken) {
        if (revealingKeys.contains(key)) return
        revealingKeys += key
        emitSecretSuccess()

        viewModelScope.launch {
            secretRepository.getDecryptedValue(
                name = name,
                namespace = namespace,
                key = key,
                biometricToken = biometricToken,
            ).fold(
                onSuccess = { value ->
                    revealingKeys -= key
                    revealedValues[key] = value
                    scheduleAutoHide(key)
                    emitSecretSuccess()
                },
                onFailure = { throwable ->
                    Log.e(TAG, "Failed to reveal Secret value for key=$key: ${throwable.message}")
                    revealingKeys -= key
                    emitSecretSuccess()
                },
            )
        }
    }

    fun hideValue(key: String) {
        autoHideJobs.remove(key)?.cancel()
        revealedValues.remove(key)
        remainingRevealSeconds.remove(key)
        emitSecretSuccess()
    }

    override fun onCleared() {
        hideAllValues()
        super.onCleared()
    }

    private fun hideAllValues() {
        autoHideJobs.values.forEach { it.cancel() }
        autoHideJobs.clear()
        revealedValues.clear()
        revealingKeys.clear()
        remainingRevealSeconds.clear()
    }

    private fun scheduleAutoHide(key: String) {
        autoHideJobs.remove(key)?.cancel()
        autoHideJobs[key] = viewModelScope.launch {
            val revealStartedAt = System.currentTimeMillis()
            val revealExpiresAt = revealStartedAt + REVEAL_AUTO_HIDE_MILLIS
            while (true) {
                val remainingMillis = revealExpiresAt - System.currentTimeMillis()
                if (remainingMillis <= 0L) break
                remainingRevealSeconds[key] = ((remainingMillis + 999L) / 1000L).toInt()
                emitSecretSuccess()
                delay(1_000L)
            }
            revealedValues.remove(key)
            remainingRevealSeconds.remove(key)
            autoHideJobs.remove(key)
            emitSecretSuccess()
        }
    }

    private fun emitSecretSuccess() {
        _uiState.update {
            it.copy(
                contentState = SecretDetailContentState.Success(
                    namespace = namespace,
                    name = name,
                    type = secretType.orDash(),
                    keys = keys,
                    revealedValues = revealedValues.toMap(),
                    revealingKeys = revealingKeys.toSet(),
                    remainingRevealSeconds = remainingRevealSeconds.toMap(),
                ),
            )
        }
    }
}

private const val PVC_KIND = "PersistentVolumeClaim"

private fun List<PersistentVolumeClaim>.toPvcUi(): List<PvcListItemUi> = map { it.toUi() }

private fun PersistentVolumeClaim.toUi(): PvcListItemUi {
    val isBound = status.equals("Bound", ignoreCase = true) || !volumeName.isNullOrBlank()
    return PvcListItemUi(
        name = name,
        namespace = namespace,
        capacity = capacity.orDash(),
        accessMode = accessModes.firstOrNull().orDash(),
        bindingStatus = if (isBound) PvcBindingStatus.BOUND else PvcBindingStatus.UNBOUND,
        storageClass = storageClass.orDash(),
        boundPvName = volumeName,
    )
}

private fun List<PersistentVolume>.toPvUi(): List<PvListItemUi> = map { pv ->
    PvListItemUi(
        name = pv.name,
        capacity = pv.capacity.orDash(),
        reclaimPolicy = pv.reclaimPolicy.orDash(),
        status = pv.status.orDash(),
    )
}

private fun List<StorageClass>.toStorageClassUi(): List<StorageClassListItemUi> = map { storageClass ->
    StorageClassListItemUi(
        name = storageClass.name,
        provisioner = storageClass.provisioner.orDash(),
        reclaimPolicy = storageClass.reclaimPolicy.orDash(),
        volumeBindingMode = storageClass.volumeBindingMode.orDash(),
    )
}

private fun List<ConfigMap>.toConfigMapUi(): List<ConfigMapListItemUi> = map { configMap ->
    ConfigMapListItemUi(
        name = configMap.name,
        namespace = configMap.namespace,
        keyCount = configMap.data.size,
        age = configMap.age.orDash(),
    )
}

private fun List<Secret>.toSecretUi(): List<SecretListItemUi> = map { secret ->
    SecretListItemUi(
        name = secret.name,
        namespace = secret.namespace,
        type = secret.type.orDash(),
        keyCount = secret.dataKeys.size,
    )
}

private fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "-"
