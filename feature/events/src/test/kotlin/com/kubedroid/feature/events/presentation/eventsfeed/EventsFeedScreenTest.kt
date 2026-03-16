package com.kubedroid.feature.events.presentation.eventsfeed

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.kubedroid.feature.events.domain.model.ClusterEvent
import com.kubedroid.feature.events.domain.model.ClusterEventType
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventsFeedScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun eventsFeedScreen_streamStateRendersItems() {
        var uiState by mutableStateOf(
            EventsFeedUiState(
                isLoading = false,
                events = emptyList(),
            ),
        )

        setScreen(stateProvider = { uiState })

        composeRule.runOnIdle {
            uiState = uiState.copy(
                events = listOf(
                    sampleEvent(uid = "event-1", objectName = "api", reason = "Created", message = "Pod created"),
                    sampleEvent(uid = "event-2", objectName = "worker", reason = "Pulled", message = "Image pulled"),
                ),
            )
        }

        composeRule.onNodeWithText("api").assertIsDisplayed()
        composeRule.onNodeWithText("Created").assertIsDisplayed()
        composeRule.onNodeWithText("Pod created").assertIsDisplayed()
    }

    @Test
    fun eventsFeedScreen_filterByTypeUpdatesRenderedItems() {
        val allEvents = listOf(
            sampleEvent(
                uid = "warning-1",
                objectName = "api-warning",
                reason = "Failed",
                message = "Readiness failed",
                type = ClusterEventType.Warning,
            ),
            sampleEvent(
                uid = "normal-1",
                objectName = "api-normal",
                reason = "Created",
                message = "Pod created",
                type = ClusterEventType.Normal,
            ),
        )
        var uiState by mutableStateOf(
            EventsFeedUiState(
                isLoading = false,
                events = allEvents,
                typeFilter = EventTypeFilter.All,
            ),
        )

        setScreen(
            stateProvider = { uiState },
            onTypeSelected = { selected ->
                uiState = uiState.copy(
                    typeFilter = selected,
                    events = when (selected) {
                        EventTypeFilter.All -> allEvents
                        EventTypeFilter.Warning -> allEvents.filter { it.type == ClusterEventType.Warning }
                        EventTypeFilter.Normal -> allEvents.filter { it.type == ClusterEventType.Normal }
                    },
                )
            },
        )

        composeRule.onNodeWithText("Warning").performClick()

        composeRule.onNodeWithText("api-warning").assertIsDisplayed()
        composeRule.onAllNodesWithText("api-normal").assertCountEquals(0)
    }

    @Test
    fun eventsFeedScreen_searchUpdatesRenderedItems() {
        val allEvents = listOf(
            sampleEvent(uid = "event-1", objectName = "api", reason = "Created", message = "Pod created"),
            sampleEvent(uid = "event-2", objectName = "worker", reason = "Pulled", message = "Image pulled"),
        )
        var uiState by mutableStateOf(
            EventsFeedUiState(
                isLoading = false,
                events = allEvents,
                searchQuery = "",
            ),
        )

        setScreen(
            stateProvider = { uiState },
            onSearchQueryChange = { query ->
                val normalized = query.trim()
                uiState = uiState.copy(
                    searchQuery = query,
                    events = if (normalized.isBlank()) {
                        allEvents
                    } else {
                        allEvents.filter { event ->
                            event.involvedObjectName.contains(normalized, ignoreCase = true) ||
                                event.reason.contains(normalized, ignoreCase = true) ||
                                event.message.contains(normalized, ignoreCase = true)
                        }
                    },
                )
            },
        )

        composeRule.onNode(hasSetTextAction()).performTextInput("pull")

        composeRule.onNodeWithText("worker").assertIsDisplayed()
        composeRule.onAllNodesWithText("api").assertCountEquals(0)
    }

    @Test
    fun eventsFeedScreen_namespaceChipFiltersRenderedItems() {
        val allEvents = listOf(
            sampleEvent(
                uid = "event-default",
                objectName = "api",
                reason = "Created",
                message = "Pod created",
                namespace = "default",
            ),
            sampleEvent(
                uid = "event-kube-system",
                objectName = "coredns",
                reason = "Started",
                message = "Container started",
                namespace = "kube-system",
            ),
        )
        var uiState by mutableStateOf(
            EventsFeedUiState(
                isLoading = false,
                availableNamespaces = listOf("default", "kube-system"),
                events = allEvents,
            ),
        )

        setScreen(
            stateProvider = { uiState },
            onNamespaceSelected = { namespace ->
                uiState = uiState.copy(
                    selectedNamespace = namespace,
                    events = if (namespace == null) {
                        allEvents
                    } else {
                        allEvents.filter { it.namespace == namespace }
                    },
                )
            },
        )

        composeRule.onNodeWithText("kube-system").performClick()

        composeRule.onNodeWithText("coredns").assertIsDisplayed()
        composeRule.onAllNodesWithText("api").assertCountEquals(0)
    }

    @Test
    fun eventsFeedScreen_emptyStateShown() {
        setScreen(
            stateProvider = {
                EventsFeedUiState(
                    isLoading = false,
                    events = emptyList(),
                )
            },
        )

        composeRule.onNodeWithText("No events found").assertIsDisplayed()
    }

    @Test
    fun eventsFeedScreen_errorStateShown() {
        setScreen(
            stateProvider = {
                EventsFeedUiState(
                    isLoading = false,
                    error = "Watch failed",
                )
            },
        )

        composeRule.onNodeWithText("Watch failed").assertIsDisplayed()
    }

    private fun setScreen(
        stateProvider: () -> EventsFeedUiState,
        onRetry: () -> Unit = {},
        onNamespaceSelected: (String?) -> Unit = {},
        onTypeSelected: (EventTypeFilter) -> Unit = {},
        onSearchQueryChange: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                EventsFeedScreen(
                    state = stateProvider(),
                    onRetry = onRetry,
                    onNamespaceSelected = onNamespaceSelected,
                    onTypeSelected = onTypeSelected,
                    onSearchQueryChange = onSearchQueryChange,
                )
            }
        }
    }

    private fun sampleEvent(
        uid: String,
        objectName: String,
        reason: String,
        message: String,
        namespace: String = "default",
        type: ClusterEventType = ClusterEventType.Normal,
    ): ClusterEvent {
        return ClusterEvent(
            uid = uid,
            namespace = namespace,
            involvedObjectName = objectName,
            involvedObjectKind = "Pod",
            reason = reason,
            message = message,
            type = type,
            count = 1,
            lastTimestamp = Instant.ofEpochMilli(500),
        )
    }
}
