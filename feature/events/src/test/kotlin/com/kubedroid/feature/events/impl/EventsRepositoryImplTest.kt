package com.kubedroid.feature.events.impl

import com.kubedroid.feature.events.domain.model.ClusterEvent
import com.kubedroid.feature.events.domain.model.ClusterEventType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventsRepositoryImplTest {

    @Test
    fun watchAllEvents_streamEmitsInitialAndWatchUpdates() = runTest {
        val initialEvent = clusterEvent(
            uid = "normal-initial",
            type = ClusterEventType.Normal,
            lastTimestampEpochMillis = 100,
        )
        val updatedEvent = clusterEvent(
            uid = "normal-updated",
            type = ClusterEventType.Normal,
            lastTimestampEpochMillis = 200,
        )
        val api = FakeEventsApi(
            listSnapshotsBySelector = mutableMapOf(
                "type=Normal" to mutableListOf(
                    EventsRepositoryImpl.EventsListSnapshot(
                        events = listOf(initialEvent),
                        resourceVersion = "rv-normal-1",
                    ),
                ),
                "type=Warning" to mutableListOf(
                    EventsRepositoryImpl.EventsListSnapshot(
                        events = emptyList(),
                        resourceVersion = "rv-warning-1",
                    ),
                ),
            ),
            watchOutcomesBySelector = mutableMapOf(
                "type=Normal" to mutableListOf(
                    FakeWatchOutcome.session(
                        FakeEventsWatchSession(
                            listOf(
                                watchEvent(type = "ADDED", event = updatedEvent, resourceVersion = "rv-normal-2"),
                            ),
                        ),
                    ),
                ),
                "type=Warning" to mutableListOf(
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                ),
            ),
        )
        val repository = createRepository(this, api, initialBackoffMillis = 60_000L, maxBackoffMillis = 60_000L)
        val emissions = mutableListOf<List<ClusterEvent>>()

        repository.watchAllEvents("default")
            .take(2)
            .collect { emissions += it }

        assertEquals(2, emissions.size)
        assertEquals(listOf("normal-initial"), emissions[0].map { it.uid })
        assertEquals(listOf("normal-updated", "normal-initial"), emissions[1].map { it.uid })
    }

    @Test
    fun watchAllEvents_mergesAndSortsAndCapsTo500() = runTest {
        val latest = InstantFixtures.at(2_000)
        val middle = InstantFixtures.at(1_000)
        val oldest = InstantFixtures.at(100)
        val bulk = (0 until 600).map { index ->
            clusterEvent(
                uid = "bulk-$index",
                type = if (index % 2 == 0) ClusterEventType.Normal else ClusterEventType.Warning,
                lastTimestampEpochMillis = index.toLong(),
            )
        }
        val api = FakeEventsApi(
            listSnapshots = mutableListOf(
                EventsRepositoryImpl.EventsListSnapshot(
                    events = listOf(
                        clusterEvent(uid = "warning", type = ClusterEventType.Warning, lastTimestampEpochMillis = latest.toEpochMilli()),
                        clusterEvent(uid = "normal", type = ClusterEventType.Normal, lastTimestampEpochMillis = middle.toEpochMilli()),
                        clusterEvent(uid = "oldest", type = ClusterEventType.Warning, lastTimestampEpochMillis = oldest.toEpochMilli()),
                    ) + bulk,
                    resourceVersion = "rv-1",
                ),
            ),
            watchOutcomes = mutableListOf(FakeWatchOutcome.session(FakeEventsWatchSession(emptyList()))),
        )
        val repository = createRepository(this, api)

        val emitted = repository.watchAllEvents("default").first()

        assertEquals(500, emitted.size)
        assertEquals("warning", emitted.first().uid)
        assertTrue(emitted.zipWithNext().all { (left, right) -> left.lastTimestamp >= right.lastTimestamp })
    }

    @Test
    fun watchAllEvents_withNullNamespace_usesClusterWideCalls() = runTest {
        val api = FakeEventsApi(
            listSnapshots = mutableListOf(
                EventsRepositoryImpl.EventsListSnapshot(
                    events = listOf(clusterEvent(uid = "one", type = ClusterEventType.Normal, lastTimestampEpochMillis = 100)),
                    resourceVersion = "rv-1",
                ),
            ),
            watchOutcomes = mutableListOf(FakeWatchOutcome.session(FakeEventsWatchSession(emptyList()))),
        )
        val repository = createRepository(this, api)

        repository.watchAllEvents(namespace = null).first()

        assertEquals(listOf<String?>(null, null), api.listNamespaces)
        assertEquals(listOf<String?>(null, null), api.watchNamespaces)
    }

    @Test
    fun watchAllEvents_reconnectsWithBackoffOnWatchError() = runTest {
        val api = FakeEventsApi(
            listSnapshotsBySelector = mutableMapOf(
                "type=Normal" to mutableListOf(
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-normal-1"),
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-normal-2"),
                ),
                "type=Warning" to mutableListOf(
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-warning-1"),
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-warning-2"),
                ),
            ),
            watchOutcomesBySelector = mutableMapOf(
                "type=Normal" to mutableListOf(
                    FakeWatchOutcome.error(IllegalStateException("boom")),
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                ),
                "type=Warning" to mutableListOf(
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                ),
            ),
        )
        val repository = createRepository(
            scope = this,
            api = api,
            initialBackoffMillis = 10L,
            maxBackoffMillis = 100L,
        )

        val collectJob = backgroundScope.launch {
            repository.watchAllEvents(namespace = "default").collect { }
        }

        runCurrent()
        val firstNormalWatchAttempts = api.watchCallsBySelector.getOrDefault("type=Normal", 0)
        assertTrue(firstNormalWatchAttempts >= 1)

        advanceTimeBy(10L)
        runCurrent()
        val normalWatchAttemptsAfterBackoff = api.watchCallsBySelector.getOrDefault("type=Normal", 0)
        assertTrue(normalWatchAttemptsAfterBackoff >= firstNormalWatchAttempts + 1)

        collectJob.cancel()
    }

    @Test
    fun watchAllEvents_appliesWatchAddModifyDeleteEvents() = runTest {
        val addedEvent = clusterEvent(
            uid = "normal-added",
            type = ClusterEventType.Normal,
            lastTimestampEpochMillis = 300,
            reason = "Scheduled",
        )
        val modifiedEvent = addedEvent.copy(
            lastTimestamp = InstantFixtures.at(400),
            reason = "Rescheduled",
        )
        val api = FakeEventsApi(
            listSnapshotsBySelector = mutableMapOf(
                "type=Normal" to mutableListOf(
                    EventsRepositoryImpl.EventsListSnapshot(
                        events = listOf(
                            clusterEvent(
                                uid = "normal-old",
                                type = ClusterEventType.Normal,
                                lastTimestampEpochMillis = 100,
                            ),
                        ),
                        resourceVersion = "rv-normal-1",
                    ),
                ),
                "type=Warning" to mutableListOf(
                    EventsRepositoryImpl.EventsListSnapshot(
                        events = listOf(
                            clusterEvent(
                                uid = "warning-stable",
                                type = ClusterEventType.Warning,
                                lastTimestampEpochMillis = 200,
                            ),
                        ),
                        resourceVersion = "rv-warning-1",
                    ),
                ),
            ),
            watchOutcomesBySelector = mutableMapOf(
                "type=Normal" to mutableListOf(
                    FakeWatchOutcome.session(
                        FakeEventsWatchSession(
                            listOf(
                                watchEvent(type = "ADDED", event = addedEvent, resourceVersion = "rv-normal-2"),
                                watchEvent(type = "MODIFIED", event = modifiedEvent, resourceVersion = "rv-normal-3"),
                                watchEvent(type = "DELETED", uid = "normal-old", resourceVersion = "rv-normal-4"),
                            ),
                        ),
                    ),
                ),
                "type=Warning" to mutableListOf(
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                ),
            ),
        )
        val repository = createRepository(this, api, initialBackoffMillis = 60_000L, maxBackoffMillis = 60_000L)

        val emitted = repository.watchAllEvents("default").first { events ->
            events.none { it.uid == "normal-old" } &&
                events.any { it.uid == "normal-added" && it.reason == "Rescheduled" }
        }

        assertEquals(listOf("normal-added"), emitted.map { it.uid })
        val normalAdded = requireNotNull(emitted.firstOrNull { it.uid == "normal-added" })
        assertEquals("Rescheduled", normalAdded.reason)
    }

    @Test
    fun watchAllEvents_reconnectsOnDisconnectAndResumesFromLatestSnapshot() = runTest {
        val api = FakeEventsApi(
            listSnapshotsBySelector = mutableMapOf(
                "type=Normal" to mutableListOf(
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-normal-1"),
                    EventsRepositoryImpl.EventsListSnapshot(
                        events = listOf(
                            clusterEvent(
                                uid = "normal-after-reconnect",
                                type = ClusterEventType.Normal,
                                lastTimestampEpochMillis = 600,
                            ),
                        ),
                        resourceVersion = "rv-normal-2",
                    ),
                ),
                "type=Warning" to mutableListOf(
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-warning-1"),
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-warning-2"),
                ),
            ),
            watchOutcomesBySelector = mutableMapOf(
                "type=Normal" to mutableListOf(
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                ),
                "type=Warning" to mutableListOf(
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                ),
            ),
        )
        val repository = createRepository(
            scope = this,
            api = api,
            initialBackoffMillis = 10L,
            maxBackoffMillis = 10L,
        )

        val emitted = repository.watchAllEvents("default").first { events ->
            events.any { it.uid == "normal-after-reconnect" }
        }

        assertEquals(listOf("normal-after-reconnect"), emitted.map { it.uid })
        assertTrue(api.watchCallsBySelector.getValue("type=Normal") >= 2)
    }

    @Test
    fun watchAllEvents_reconnectBackoffDoublesUntilMaxForNormalWatch() = runTest {
        val normalOpenWatchTimestamps = mutableListOf<Long>()
        val api = FakeEventsApi(
            listSnapshotsBySelector = mutableMapOf(
                "type=Normal" to mutableListOf(
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-normal-1"),
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-normal-2"),
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-normal-3"),
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-normal-4"),
                ),
                "type=Warning" to mutableListOf(
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-warning-1"),
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-warning-2"),
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-warning-3"),
                    EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = "rv-warning-4"),
                ),
            ),
            watchOutcomesBySelector = mutableMapOf(
                "type=Normal" to mutableListOf(
                    FakeWatchOutcome.error(IllegalStateException("normal-fail-1")),
                    FakeWatchOutcome.error(IllegalStateException("normal-fail-2")),
                    FakeWatchOutcome.error(IllegalStateException("normal-fail-3")),
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                ),
                "type=Warning" to mutableListOf(
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                ),
            ),
            onOpenWatch = { selector ->
                if (selector == "type=Normal") {
                    normalOpenWatchTimestamps += testScheduler.currentTime
                }
            },
        )
        val repository = createRepository(
            scope = this,
            api = api,
            initialBackoffMillis = 10L,
            maxBackoffMillis = 30L,
        )

        val collectJob = backgroundScope.launch {
            repository.watchAllEvents("default").collect { }
        }

        advanceUntilIdle()
        advanceTimeBy(10L)
        advanceUntilIdle()
        advanceTimeBy(20L)
        advanceUntilIdle()
        advanceTimeBy(30L)
        advanceUntilIdle()

        assertEquals(listOf(0L, 10L, 30L), normalOpenWatchTimestamps.take(3))

        collectJob.cancel()
    }

    @Test
    fun watchAllEvents_bufferCapDropsOldestEntriesWhenLimitExceeded() = runTest {
        val warningBase = clusterEvent(
            uid = "warning-stable",
            type = ClusterEventType.Warning,
            lastTimestampEpochMillis = 350,
        )
        val api = FakeEventsApi(
            listSnapshotsBySelector = mutableMapOf(
                "type=Normal" to mutableListOf(
                    EventsRepositoryImpl.EventsListSnapshot(
                        events = listOf(
                            clusterEvent(uid = "normal-a", type = ClusterEventType.Normal, lastTimestampEpochMillis = 100),
                            clusterEvent(uid = "normal-b", type = ClusterEventType.Normal, lastTimestampEpochMillis = 200),
                            clusterEvent(uid = "normal-c", type = ClusterEventType.Normal, lastTimestampEpochMillis = 300),
                        ),
                        resourceVersion = "rv-normal-1",
                    ),
                ),
                "type=Warning" to mutableListOf(
                    EventsRepositoryImpl.EventsListSnapshot(
                        events = listOf(warningBase),
                        resourceVersion = "rv-warning-1",
                    ),
                ),
            ),
            watchOutcomesBySelector = mutableMapOf(
                "type=Normal" to mutableListOf(
                    FakeWatchOutcome.session(
                        FakeEventsWatchSession(
                            listOf(
                                watchEvent(
                                    type = "ADDED",
                                    event = clusterEvent(
                                        uid = "normal-newest",
                                        type = ClusterEventType.Normal,
                                        lastTimestampEpochMillis = 400,
                                    ),
                                    resourceVersion = "rv-normal-2",
                                ),
                            ),
                        ),
                    ),
                ),
                "type=Warning" to mutableListOf(
                    FakeWatchOutcome.session(FakeEventsWatchSession(emptyList())),
                ),
            ),
        )
        val repository = createRepository(
            scope = this,
            api = api,
            initialBackoffMillis = 60_000L,
            maxBackoffMillis = 60_000L,
            maxBufferedEvents = 3,
        )

        val emitted = repository.watchAllEvents("default").first { events ->
            events.any { it.uid == "normal-newest" }
        }

        assertEquals(listOf("normal-newest", "normal-c", "normal-b"), emitted.map { it.uid })
        assertTrue(emitted.none { it.uid == "normal-a" })
    }

    @Test
    fun getEventsByObject_filtersByKindAndNameAndSortsDescending() = runTest {
        val api = FakeEventsApi(
            listSnapshots = mutableListOf(
                EventsRepositoryImpl.EventsListSnapshot(
                    events = listOf(
                        clusterEvent(uid = "a", type = ClusterEventType.Warning, lastTimestampEpochMillis = 10),
                        clusterEvent(uid = "b", type = ClusterEventType.Normal, lastTimestampEpochMillis = 20),
                    ),
                    resourceVersion = "rv-1",
                ),
            ),
            watchOutcomes = mutableListOf(),
        )
        val repository = createRepository(this, api)

        val result = repository.getEventsByObject(
            kind = "Pod",
            name = "nginx",
            namespace = "default",
        ).getOrThrow()

        assertEquals(listOf("b", "a"), result.map { it.uid })
        assertEquals("involvedObject.kind=Pod,involvedObject.name=nginx", api.lastFieldSelector)
    }

    private fun createRepository(
        scope: TestScope,
        api: FakeEventsApi,
        initialBackoffMillis: Long = 1_000L,
        maxBackoffMillis: Long = 30_000L,
        maxBufferedEvents: Int = 500,
    ): EventsRepositoryImpl {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return EventsRepositoryImpl(
            eventsApiFactory = EventsRepositoryImpl.EventsApiFactory { api },
            ioDispatcher = dispatcher,
            watchReconnectInitialBackoffMillis = initialBackoffMillis,
            watchReconnectMaxBackoffMillis = maxBackoffMillis,
            maxBufferedEvents = maxBufferedEvents,
        )
    }
}

private sealed class FakeWatchOutcome {
    data class Session(val session: EventsRepositoryImpl.EventsWatchSession) : FakeWatchOutcome()
    data class Error(val throwable: Throwable) : FakeWatchOutcome()

    companion object {
        fun session(session: EventsRepositoryImpl.EventsWatchSession): FakeWatchOutcome = Session(session)
        fun error(throwable: Throwable): FakeWatchOutcome = Error(throwable)
    }
}

private class FakeEventsApi(
    private val listSnapshots: MutableList<EventsRepositoryImpl.EventsListSnapshot> = mutableListOf(),
    private val watchOutcomes: MutableList<FakeWatchOutcome> = mutableListOf(),
    private val listSnapshotsBySelector: MutableMap<String, MutableList<EventsRepositoryImpl.EventsListSnapshot>> = mutableMapOf(),
    private val watchOutcomesBySelector: MutableMap<String, MutableList<FakeWatchOutcome>> = mutableMapOf(),
    private val onOpenWatch: ((String?) -> Unit)? = null,
) : EventsRepositoryImpl.EventsApi {
    val listNamespaces = mutableListOf<String?>()
    val watchNamespaces = mutableListOf<String?>()
    val watchCallsBySelector = mutableMapOf<String, Int>()
    var lastFieldSelector: String? = null

    override fun listEvents(namespace: String?, fieldSelector: String?): EventsRepositoryImpl.EventsListSnapshot {
        listNamespaces += namespace
        lastFieldSelector = fieldSelector
        val perSelectorQueue = fieldSelector?.let { selector -> listSnapshotsBySelector[selector] }
        return perSelectorQueue?.removeFirstOrNull()
            ?: listSnapshots.removeFirstOrNull()
            ?: EventsRepositoryImpl.EventsListSnapshot(events = emptyList(), resourceVersion = null)
    }

    override fun openEventWatch(
        namespace: String?,
        resourceVersion: String?,
        fieldSelector: String?,
    ): EventsRepositoryImpl.EventsWatchSession {
        watchNamespaces += namespace
        if (fieldSelector != null) {
            watchCallsBySelector[fieldSelector] = watchCallsBySelector.getOrDefault(fieldSelector, 0) + 1
        }
        onOpenWatch?.invoke(fieldSelector)
        val perSelectorQueue = fieldSelector?.let { selector -> watchOutcomesBySelector[selector] }
        val outcome = perSelectorQueue?.removeFirstOrNull()
            ?: watchOutcomes.removeFirstOrNull()
            ?: FakeWatchOutcome.session(FakeEventsWatchSession(emptyList()))
        return when (outcome) {
            is FakeWatchOutcome.Error -> throw outcome.throwable
            is FakeWatchOutcome.Session -> outcome.session
        }
    }
}

private class FakeEventsWatchSession(
    private val events: List<EventsRepositoryImpl.EventWatchEvent>,
) : EventsRepositoryImpl.EventsWatchSession {
    override fun iterator(): Iterator<EventsRepositoryImpl.EventWatchEvent> = events.iterator()
    override fun close() = Unit
}

private fun clusterEvent(
    uid: String,
    type: ClusterEventType,
    lastTimestampEpochMillis: Long,
    reason: String = "reason",
    message: String = "message",
): ClusterEvent {
    return ClusterEvent(
        uid = uid,
        namespace = "default",
        involvedObjectName = "obj",
        involvedObjectKind = "Pod",
        reason = reason,
        message = message,
        type = type,
        count = 1,
        lastTimestamp = InstantFixtures.at(lastTimestampEpochMillis),
    )
}

private fun watchEvent(
    type: String,
    event: ClusterEvent? = null,
    uid: String? = event?.uid,
    resourceVersion: String? = null,
): EventsRepositoryImpl.EventWatchEvent {
    return EventsRepositoryImpl.EventWatchEvent(
        type = type,
        uid = uid,
        event = event,
        resourceVersion = resourceVersion,
    )
}

private object InstantFixtures {
    fun at(epochMillis: Long) = java.time.Instant.ofEpochMilli(epochMillis)
}
