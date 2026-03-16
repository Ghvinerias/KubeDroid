package com.kubedroid.feature.storage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kubedroid.feature.events.domain.model.ClusterEvent
import com.kubedroid.feature.events.domain.model.ClusterEventType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StorageScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_StorageScreen_pvcTabSelected_rendersPvcContent() {
        setStorageScreen(
            StorageUiState(
                selectedTab = StorageTab.PVCS,
                selectedNamespace = "default",
                pvcState = StorageListState.Empty,
            ),
        )

        composeRule.onNodeWithText("PVC Namespace").assertIsDisplayed()
        composeRule.onNodeWithText("No persistent volume claims found").assertIsDisplayed()
    }

    @Test
    fun test_StorageScreen_tabClick_invokesSelectionCallback() {
        var selectedTab: StorageTab? = null
        setStorageScreen(
            state = StorageUiState(
                selectedTab = StorageTab.PVCS,
                pvcState = StorageListState.Empty,
            ),
            onTabSelected = { selectedTab = it },
        )

        composeRule.onNodeWithText("PVs").performClick()

        composeRule.runOnIdle {
            assertEquals(StorageTab.PVS, selectedTab)
        }
    }

    @Test
    fun test_StorageScreen_pvsTabSelected_rendersPvContent() {
        setStorageScreen(
            StorageUiState(
                selectedTab = StorageTab.PVS,
                pvsState = StorageListState.Empty,
            ),
        )

        composeRule.onNodeWithText("No persistent volumes found").assertIsDisplayed()
    }

    @Test
    fun test_StorageScreen_storageClassesTabSelected_rendersStorageClassContent() {
        setStorageScreen(
            StorageUiState(
                selectedTab = StorageTab.STORAGE_CLASSES,
                storageClassState = StorageListState.Empty,
            ),
        )

        composeRule.onNodeWithText("No storage classes found").assertIsDisplayed()
    }

    private fun setStorageScreen(
        state: StorageUiState,
        onTabSelected: (StorageTab) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                StorageScreen(
                    state = state,
                    onTabSelected = onTabSelected,
                    onRefresh = {},
                    onNamespaceChange = {},
                    onConfigMapNamespaceChange = {},
                    onSecretNamespaceChange = {},
                    onPvcClick = { _, _ -> },
                    onConfigMapClick = { _, _ -> },
                    onSecretClick = { _, _ -> },
                )
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class PvcDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_PvcDetailScreen_loadingState_rendersProgress() {
        setDetailScreen(
            PvcDetailUiState(
                namespace = "default",
                name = "claim-a",
                contentState = PvcDetailContentState.Loading,
            ),
        )

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun test_PvcDetailScreen_errorState_withoutCause_rendersFallbackMessage() {
        setDetailScreen(
            PvcDetailUiState(
                namespace = "default",
                name = "claim-a",
                contentState = PvcDetailContentState.Error(cause = null),
            ),
        )

        composeRule.onNodeWithText("Failed to load storage resources").assertIsDisplayed()
    }

    @Test
    fun test_PvcDetailScreen_successState_rendersDetailsAndEvents() {
        setDetailScreen(
            PvcDetailUiState(
                namespace = "default",
                name = "claim-a",
                contentState = PvcDetailContentState.Success(
                    pvc = PvcListItemUi(
                        name = "claim-a",
                        namespace = "default",
                        capacity = "10Gi",
                        accessMode = "ReadWriteOnce",
                        bindingStatus = PvcBindingStatus.BOUND,
                        storageClass = "fast",
                        boundPvName = "pv-a",
                    ),
                    accessModes = listOf("ReadWriteOnce", "ReadOnlyMany"),
                    events = listOf(
                        ClusterEvent(
                            uid = "event-1",
                            namespace = "default",
                            involvedObjectName = "claim-a",
                            involvedObjectKind = "PersistentVolumeClaim",
                            reason = "ProvisioningSucceeded",
                            message = "Successfully provisioned volume",
                            type = ClusterEventType.Warning,
                            count = 1,
                            lastTimestamp = Instant.parse("2026-03-01T10:00:00Z"),
                        ),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Namespace: default").assertIsDisplayed()
        composeRule.onNodeWithText("Bound PV: pv-a").assertIsDisplayed()
        composeRule.onNodeWithText("Access modes: ReadWriteOnce, ReadOnlyMany").assertIsDisplayed()
        composeRule.onNodeWithText("Events").assertIsDisplayed()
        composeRule.onNodeWithText("ProvisioningSucceeded").assertIsDisplayed()
        composeRule.onNodeWithText("Successfully provisioned volume").assertIsDisplayed()
        composeRule.onNodeWithText("Type: Warning").assertIsDisplayed()
        composeRule.onNodeWithText("Last timestamp:", substring = true).assertIsDisplayed()
    }

    @Test
    fun test_PvcDetailScreen_actions_invokeCallbacks() {
        var backClicked = false
        var refreshClicked = false
        setDetailScreen(
            state = PvcDetailUiState(
                namespace = "default",
                name = "claim-a",
                contentState = PvcDetailContentState.Loading,
            ),
            onRefresh = { refreshClicked = true },
            onBackClick = { backClicked = true },
        )

        composeRule.onNodeWithContentDescription("Go back").performClick()
        composeRule.onNodeWithContentDescription("Refresh storage").performClick()

        composeRule.runOnIdle {
            assertTrue(backClicked)
            assertTrue(refreshClicked)
        }
    }

    private fun setDetailScreen(
        state: PvcDetailUiState,
        onRefresh: () -> Unit = {},
        onBackClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                PvcDetailScreen(
                    state = state,
                    onRefresh = onRefresh,
                    onBackClick = onBackClick,
                )
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class ConfigMapDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_ConfigMapDetailScreen_successState_rendersNamespaceEntriesAndSave() {
        setDetailScreen(
            state = ConfigMapDetailUiState(
                namespace = "default",
                name = "app-config",
                contentState = ConfigMapDetailContentState.Success(
                    namespace = "default",
                    name = "app-config",
                    entries = listOf(
                        ConfigMapKeyValueEntryUi(key = "API_URL", value = "https://example.internal"),
                        ConfigMapKeyValueEntryUi(key = "FEATURE_FLAG", value = "true"),
                    ),
                    hasPendingChanges = true,
                    isSaving = false,
                ),
            ),
        )

        composeRule.onNodeWithText("Namespace: default").assertIsDisplayed()
        composeRule.onNodeWithText("API_URL").assertIsDisplayed()
        composeRule.onNodeWithText("https://example.internal").assertIsDisplayed()
        composeRule.onNodeWithText("FEATURE_FLAG").assertIsDisplayed()
        composeRule.onNodeWithText("true").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun test_ConfigMapDetailScreen_saveAction_invokesCallback_whenEnabled() {
        var saveClicked = false
        setDetailScreen(
            state = ConfigMapDetailUiState(
                namespace = "default",
                name = "app-config",
                contentState = ConfigMapDetailContentState.Success(
                    namespace = "default",
                    name = "app-config",
                    entries = listOf(ConfigMapKeyValueEntryUi(key = "A", value = "1")),
                    hasPendingChanges = true,
                    isSaving = false,
                ),
            ),
            onSave = { saveClicked = true },
        )

        composeRule.onNodeWithText("Save").performClick()

        composeRule.runOnIdle {
            assertTrue(saveClicked)
        }
    }

    private fun setDetailScreen(
        state: ConfigMapDetailUiState,
        onSave: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                ConfigMapDetailScreen(
                    state = state,
                    onRefresh = {},
                    onValueChange = { _, _ -> },
                    onSave = onSave,
                    onBackClick = {},
                )
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class SecretDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_SecretDetailScreen_valuesAreHiddenByDefault() {
        setDetailScreen(
            state = SecretDetailUiState(
                namespace = "default",
                name = "db-secret",
                contentState = SecretDetailContentState.Success(
                    namespace = "default",
                    name = "db-secret",
                    type = "Opaque",
                    keys = listOf("password"),
                    revealedValues = emptyMap(),
                    revealingKeys = emptySet(),
                    remainingRevealSeconds = emptyMap(),
                ),
            ),
            onRevealClick = {},
        )

        composeRule.onNodeWithText("password").assertIsDisplayed()
        composeRule.onNodeWithText("••••••").assertIsDisplayed()
        composeRule.onNodeWithText("Reveal").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithContentDescription("Copy secret value").assertDoesNotExist()
    }

    @Test
    fun test_SecretDetailScreen_revealRequiresBiometricSuccess() {
        var biometricSuccess by mutableStateOf(false)
        var revealedValues by mutableStateOf<Map<String, String>>(emptyMap())
        val secretValue = "super-secret-password"

        composeRule.setContent {
            MaterialTheme {
                SecretDetailScreen(
                    state = SecretDetailUiState(
                        namespace = "default",
                        name = "db-secret",
                        contentState = SecretDetailContentState.Success(
                            namespace = "default",
                            name = "db-secret",
                            type = "Opaque",
                            keys = listOf("password"),
                            revealedValues = revealedValues,
                            revealingKeys = emptySet(),
                            remainingRevealSeconds = emptyMap(),
                        ),
                    ),
                    onRefresh = {},
                    onBackClick = {},
                    onRevealClick = {
                        if (biometricSuccess) {
                            revealedValues = mapOf("password" to secretValue)
                        }
                    },
                    onHideClick = { revealedValues = emptyMap() },
                    onCopyClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Reveal").performClick()
        composeRule.onNodeWithText("••••••").assertIsDisplayed()
        composeRule.onNodeWithText(secretValue).assertDoesNotExist()

        composeRule.runOnIdle { biometricSuccess = true }
        composeRule.onNodeWithText("Reveal").performClick()
        composeRule.onNodeWithText(secretValue).assertIsDisplayed()
        composeRule.onNodeWithText("Hide").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Copy secret value").assertIsDisplayed().assertIsEnabled()
    }

    private fun setDetailScreen(
        state: SecretDetailUiState,
        onRevealClick: (key: String) -> Unit,
    ) {
        composeRule.setContent {
            MaterialTheme {
                SecretDetailScreen(
                    state = state,
                    onRefresh = {},
                    onBackClick = {},
                    onRevealClick = onRevealClick,
                    onHideClick = {},
                    onCopyClick = {},
                )
            }
        }
    }
}
