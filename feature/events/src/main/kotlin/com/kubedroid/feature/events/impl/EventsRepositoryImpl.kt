package com.kubedroid.feature.events.impl

import android.content.Context
import android.util.Log
import com.google.gson.reflect.TypeToken
import com.kubedroid.feature.events.domain.model.ClusterEvent
import com.kubedroid.feature.events.domain.model.ClusterEventType
import com.kubedroid.feature.events.domain.repository.EventsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.CoreV1Event
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.Watch
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class EventsRepositoryImpl : EventsRepository {
    private val eventsApiFactory: EventsApiFactory
    private val ioDispatcher: CoroutineDispatcher
    private val watchReconnectInitialBackoffMillis: Long
    private val watchReconnectMaxBackoffMillis: Long
    private val maxBufferedEvents: Int

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        eventsApiFactory = EventsApiFactory.Default(
            apiClientProvider = { buildApiClient(FilePaths.kubeConfigPath(context)) },
        ),
        ioDispatcher = Dispatchers.IO,
    )

    internal constructor(
        eventsApiFactory: EventsApiFactory,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        watchReconnectInitialBackoffMillis: Long = WATCH_RECONNECT_INITIAL_BACKOFF_MILLIS,
        watchReconnectMaxBackoffMillis: Long = WATCH_RECONNECT_MAX_BACKOFF_MILLIS,
        maxBufferedEvents: Int = MAX_BUFFERED_EVENTS,
    ) {
        this.eventsApiFactory = eventsApiFactory
        this.ioDispatcher = ioDispatcher
        this.watchReconnectInitialBackoffMillis = watchReconnectInitialBackoffMillis
        this.watchReconnectMaxBackoffMillis = watchReconnectMaxBackoffMillis
        this.maxBufferedEvents = maxBufferedEvents
    }

    override fun watchAllEvents(namespace: String?): Flow<List<ClusterEvent>> = callbackFlow {
        val normalizedNamespace = namespace?.trim()?.ifEmpty { null }
        val eventsApi = eventsApiFactory.create()
        val eventsByUid = mutableMapOf<String, ClusterEvent>()
        val normalUids = mutableSetOf<String>()
        val warningUids = mutableSetOf<String>()
        val stateMutex = Mutex()

        suspend fun emitMergedLocked() {
            val merged = eventsByUid.toSortedLimited(maxBufferedEvents)
            val sendResult = trySend(merged)
            if (sendResult.isFailure && !sendResult.isClosed) {
                Log.w(TAG, "Dropping event emission due to backpressure")
            }
        }

        fun launchTypeWatcher(
            fieldSelector: String,
        ) = launch(ioDispatcher) {
            var reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)
            var resourceVersion: String? = null

            while (isActive) {
                try {
                    val initialSnapshot = eventsApi.listEvents(
                        namespace = normalizedNamespace,
                        fieldSelector = fieldSelector,
                    )
                    resourceVersion = initialSnapshot.resourceVersion

                    stateMutex.withLock {
                        val typeUids = if (fieldSelector == EVENT_TYPE_NORMAL_SELECTOR) normalUids else warningUids
                        typeUids.forEach(eventsByUid::remove)
                        typeUids.clear()

                        initialSnapshot.events.forEach { event ->
                            eventsByUid[event.uid] = event
                            if (fieldSelector == EVENT_TYPE_NORMAL_SELECTOR) {
                                normalUids += event.uid
                            } else {
                                warningUids += event.uid
                            }
                        }
                        trimToMaxSize(eventsByUid, normalUids, warningUids, maxBufferedEvents)
                        emitMergedLocked()
                    }

                    eventsApi.openEventWatch(
                        namespace = normalizedNamespace,
                        resourceVersion = resourceVersion,
                        fieldSelector = fieldSelector,
                    ).use { watchSession ->
                        reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)
                        for (watchEvent in watchSession) {
                            if (!isActive) break
                            resourceVersion = watchEvent.resourceVersion ?: resourceVersion

                            stateMutex.withLock {
                                when (watchEvent.type?.uppercase()) {
                                    "ADDED", "MODIFIED" -> {
                                        watchEvent.event?.let { event ->
                                            eventsByUid[event.uid] = event
                                            if (event.type == ClusterEventType.Normal) {
                                                normalUids += event.uid
                                                warningUids -= event.uid
                                            } else {
                                                warningUids += event.uid
                                                normalUids -= event.uid
                                            }
                                            trimToMaxSize(eventsByUid, normalUids, warningUids, maxBufferedEvents)
                                            emitMergedLocked()
                                        } ?: run {
                                            val uid = watchEvent.uid
                                            if (uid != null && eventsByUid.remove(uid) != null) {
                                                normalUids -= uid
                                                warningUids -= uid
                                                emitMergedLocked()
                                            }
                                        }
                                    }

                                    "DELETED" -> {
                                        val uid = watchEvent.uid ?: watchEvent.event?.uid
                                        if (uid != null && eventsByUid.remove(uid) != null) {
                                            normalUids -= uid
                                            warningUids -= uid
                                            emitMergedLocked()
                                        }
                                    }

                                    "BOOKMARK" -> Unit
                                    else -> Unit
                                }
                            }
                        }
                    }

                    if (isActive) {
                        throw EventsWatchDisconnectedException("Events watch stream disconnected")
                    }
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    delay(reconnectDelayMillis)
                    reconnectDelayMillis = (reconnectDelayMillis * 2).coerceAtMost(
                        watchReconnectMaxBackoffMillis.coerceAtLeast(reconnectDelayMillis),
                    )
                }
            }
        }

        val normalWatcher = launchTypeWatcher(
            fieldSelector = EVENT_TYPE_NORMAL_SELECTOR,
        )
        val warningWatcher = launchTypeWatcher(
            fieldSelector = EVENT_TYPE_WARNING_SELECTOR,
        )

        awaitClose {
            normalWatcher.cancel()
            warningWatcher.cancel()
        }
    }.flowOn(ioDispatcher)

    override suspend fun getEventsByObject(
        kind: String,
        name: String,
        namespace: String?,
    ): Result<List<ClusterEvent>> = withContext(ioDispatcher) {
        runCatching {
            val normalizedKind = kind.trim()
            val normalizedName = name.trim()
            require(normalizedKind.isNotEmpty()) { "kind must not be empty" }
            require(normalizedName.isNotEmpty()) { "name must not be empty" }

            val selector = "involvedObject.kind=$normalizedKind,involvedObject.name=$normalizedName"
            eventsApiFactory.create()
                .listEvents(namespace = namespace?.trim()?.ifEmpty { null }, fieldSelector = selector)
                .events
                .sortedByDescending { it.lastTimestamp }
                .take(maxBufferedEvents.coerceAtLeast(1))
        }
    }

    companion object {
        private const val TAG = "EventsRepo"
        private const val MAX_BUFFERED_EVENTS = 500
        private const val WATCH_TIMEOUT_SECONDS = 300
        private const val WATCH_RECONNECT_INITIAL_BACKOFF_MILLIS = 1_000L
        private const val WATCH_RECONNECT_MAX_BACKOFF_MILLIS = 30_000L
        private const val EVENT_TYPE_NORMAL_SELECTOR = "type=Normal"
        private const val EVENT_TYPE_WARNING_SELECTOR = "type=Warning"
    }

    private object FilePaths {
        fun kubeConfigPath(context: Context): Path = context.filesDir.toPath().resolve("kube/config")
    }

    internal fun interface EventsApiFactory {
        fun create(): EventsApi

        class Default(
            private val apiClientProvider: () -> ApiClient,
        ) : EventsApiFactory {
            override fun create(): EventsApi = KubernetesEventsApi(apiClientProvider)
        }
    }

    internal interface EventsApi {
        fun listEvents(namespace: String?, fieldSelector: String? = null): EventsListSnapshot
        fun openEventWatch(namespace: String?, resourceVersion: String?, fieldSelector: String? = null): EventsWatchSession
    }

    internal data class EventsListSnapshot(
        val events: List<ClusterEvent>,
        val resourceVersion: String?,
    )

    internal data class EventWatchEvent(
        val type: String?,
        val uid: String?,
        val event: ClusterEvent?,
        val resourceVersion: String?,
    )

    internal interface EventsWatchSession : Iterable<EventWatchEvent>, AutoCloseable

    private class KubernetesEventsApi(
        private val apiClientProvider: () -> ApiClient,
    ) : EventsApi {
        override fun listEvents(namespace: String?, fieldSelector: String?): EventsListSnapshot {
            val api = CoreV1Api(apiClientProvider())
            val response = if (namespace == null) {
                val request = api.listEventForAllNamespaces()
                if (!fieldSelector.isNullOrBlank()) {
                    request.fieldSelector(fieldSelector)
                }
                request.execute()
            } else {
                val request = api.listNamespacedEvent(namespace)
                if (!fieldSelector.isNullOrBlank()) {
                    request.fieldSelector(fieldSelector)
                }
                request.execute()
            }

            return EventsListSnapshot(
                events = response.items.orEmpty().mapNotNull { it.toClusterEventOrNull() },
                resourceVersion = response.metadata?.resourceVersion,
            )
        }

        override fun openEventWatch(
            namespace: String?,
            resourceVersion: String?,
            fieldSelector: String?,
        ): EventsWatchSession {
            val api = CoreV1Api(apiClientProvider())
            val requestCall = if (namespace == null) {
                val request = api.listEventForAllNamespaces()
                    .watch(true)
                    .allowWatchBookmarks(true)
                    .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
                if (!fieldSelector.isNullOrBlank()) {
                    request.fieldSelector(fieldSelector)
                }
                if (!resourceVersion.isNullOrBlank()) {
                    request.resourceVersion(resourceVersion)
                }
                request.buildCall(null)
            } else {
                val request = api.listNamespacedEvent(namespace)
                    .watch(true)
                    .allowWatchBookmarks(true)
                    .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
                if (!fieldSelector.isNullOrBlank()) {
                    request.fieldSelector(fieldSelector)
                }
                if (!resourceVersion.isNullOrBlank()) {
                    request.resourceVersion(resourceVersion)
                }
                request.buildCall(null)
            }

            val watch: Watch<CoreV1Event> = Watch.createWatch(
                apiClientProvider(),
                requestCall,
                object : TypeToken<Watch.Response<CoreV1Event>>() {}.type,
            )
            return KubernetesEventsWatchSession(watch)
        }
    }

    private class KubernetesEventsWatchSession(
        private val watch: Watch<CoreV1Event>,
    ) : EventsWatchSession {
        override fun iterator(): Iterator<EventWatchEvent> {
            val delegate = watch.iterator()
            return object : Iterator<EventWatchEvent> {
                override fun hasNext(): Boolean = delegate.hasNext()

                override fun next(): EventWatchEvent {
                    val response = delegate.next()
                    val payload = response.`object`
                    return EventWatchEvent(
                        type = response.type,
                        uid = payload?.metadata?.uid,
                        event = payload.toClusterEventOrNull(),
                        resourceVersion = payload?.metadata?.resourceVersion,
                    )
                }
            }
        }

        override fun close() {
            watch.close()
        }
    }
}

private fun Map<String, ClusterEvent>.toSortedLimited(maxSize: Int): List<ClusterEvent> {
    return values
        .asSequence()
        .sortedByDescending { it.lastTimestamp }
        .take(maxSize.coerceAtLeast(1))
        .toList()
}

private fun trimToMaxSize(
    eventsByUid: MutableMap<String, ClusterEvent>,
    normalUids: MutableSet<String>,
    warningUids: MutableSet<String>,
    maxSize: Int,
) {
    val boundedMaxSize = maxSize.coerceAtLeast(1)
    if (eventsByUid.size <= boundedMaxSize) return

    val keepUids = eventsByUid.values
        .asSequence()
        .sortedByDescending { it.lastTimestamp }
        .take(boundedMaxSize)
        .map { it.uid }
        .toHashSet()

    val iterator = eventsByUid.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        if (entry.key !in keepUids) {
            iterator.remove()
        }
    }
    normalUids.retainAll(keepUids)
    warningUids.retainAll(keepUids)
}

private fun CoreV1Event?.toClusterEventOrNull(): ClusterEvent? {
    if (this == null) return null
    val eventType = when (type?.trim()?.uppercase()) {
        "NORMAL" -> ClusterEventType.Normal
        "WARNING" -> ClusterEventType.Warning
        else -> return null
    }

    val metadata = metadata
    val involved = involvedObject
    val fallbackTimestamp = metadata?.creationTimestamp?.toInstant() ?: Instant.EPOCH
    val timestamp = lastTimestamp?.toInstant()
        ?: eventTime?.toInstant()
        ?: firstTimestamp?.toInstant()
        ?: fallbackTimestamp

    val uid = metadata?.uid
        ?.trim()
        ?.ifEmpty { null }
        ?: "${metadata?.namespace.orEmpty()}:${involved?.kind.orEmpty()}:${involved?.name.orEmpty()}:${reason.orEmpty()}:${timestamp.toEpochMilli()}"

    return ClusterEvent(
        uid = uid,
        namespace = metadata?.namespace?.trim().orEmpty(),
        involvedObjectName = involved?.name?.trim().orEmpty(),
        involvedObjectKind = involved?.kind?.trim().orEmpty(),
        reason = reason?.trim().orEmpty(),
        message = message?.trim().orEmpty(),
        type = eventType,
        count = count ?: 1,
        lastTimestamp = timestamp,
    )
}

private fun buildApiClient(kubeConfigPath: Path): ApiClient {
    if (!Files.exists(kubeConfigPath)) {
        throw IllegalStateException("Kubeconfig file not found at $kubeConfigPath")
    }

    val raw = Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
    val kubeConfig = KubeConfig.loadKubeConfig(StringReader(raw))
    return ClientBuilder.kubeconfig(kubeConfig)
        .build()
        .setLenientOnJson(true)
}

private class EventsWatchDisconnectedException(
    message: String,
) : RuntimeException(message)
