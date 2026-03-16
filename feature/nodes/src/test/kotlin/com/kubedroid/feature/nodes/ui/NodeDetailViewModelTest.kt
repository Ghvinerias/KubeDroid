package com.kubedroid.feature.nodes.ui

import com.kubedroid.core.network.nodes.NodeActionResult
import com.kubedroid.core.network.nodes.NodeDrainProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeDetailViewModelTest {

    @Test
    fun toActionMessage_mapsConflictToFailureConflict() {
        val message = NodeActionResult.Conflict.toActionMessage(NodeActionType.DRAIN)

        val expected = NodeActionMessage.Failure(
            action = NodeActionType.DRAIN,
            error = NodeActionError.CONFLICT,
        )
        assertEquals(expected, message)
    }

    @Test
    fun toUiEvent_mapsWaitingOnPdbRetryDelayAndAttempt() {
        val event = NodeDrainProgress.WaitingOnDisruptionBudget(
            nodeName = "node-a",
            namespace = "kube-system",
            podName = "coredns-123",
            attempt = 4,
            retryDelayMillis = 2000L,
        ).toUiEvent()

        assertEquals(DrainEventMessageType.WAITING_ON_PDB, event.messageType)
        assertEquals("kube-system", event.namespace)
        assertEquals("coredns-123", event.podName)
        assertEquals(4, event.attempt)
        assertEquals(2000L, event.retryDelayMillis)
    }

    @Test
    fun toUiEvent_mapsCompletedForbiddenToForbiddenMessageType() {
        val event = NodeDrainProgress.Completed(
            result = NodeActionResult.Forbidden,
        ).toUiEvent()

        assertEquals(DrainEventMessageType.COMPLETED_FORBIDDEN, event.messageType)
        assertEquals(null, event.namespace)
        assertEquals(null, event.podName)
    }
}
