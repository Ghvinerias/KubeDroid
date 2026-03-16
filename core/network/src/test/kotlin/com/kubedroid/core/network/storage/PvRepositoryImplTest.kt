package com.kubedroid.core.network.storage

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PvRepositoryImplTest {

    @Test
    fun test_listStorageClasses_404_returnsEmptyList() = runTest {
        val repository = PvRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            pvApiFactory = PvApiFactory {
                object : PvApi {
                    override fun listPvs(): List<PersistentVolume> = emptyList()

                    override fun listPvcsSnapshot(namespace: String): PvcListSnapshot {
                        return PvcListSnapshot(emptyList(), null)
                    }

                    override fun openPvcWatch(namespace: String, resourceVersion: String?): PvcWatchSession {
                        error("not used")
                    }

                    override fun listStorageClasses(): List<StorageClass> {
                        throw ApiException(404, "not found")
                    }
                }
            },
        )

        val result = repository.listStorageClasses()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun test_listPvcs_matchesByVolumeName_andPreservesPendingStatus() = runTest {
        val repository = PvRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            pvApiFactory = PvApiFactory {
                object : PvApi {
                    override fun listPvs(): List<PersistentVolume> {
                        return listOf(
                            PersistentVolume(
                                name = "pv-a",
                                capacity = "10Gi",
                                accessModes = listOf("ReadWriteOnce"),
                                reclaimPolicy = "Delete",
                                status = "Bound",
                                storageClass = "fast",
                                claimRef = "default/claim-a",
                            ),
                        )
                    }

                    override fun listPvcsSnapshot(namespace: String): PvcListSnapshot {
                        return PvcListSnapshot(
                            pvcs = listOf(
                                PersistentVolumeClaim(
                                    name = "claim-a",
                                    namespace = namespace,
                                    status = "Bound",
                                    capacity = "10Gi",
                                    accessModes = listOf("ReadWriteOnce"),
                                    storageClass = "fast",
                                    volumeName = "pv-a",
                                ),
                                PersistentVolumeClaim(
                                    name = "claim-pending",
                                    namespace = namespace,
                                    status = "Pending",
                                    capacity = null,
                                    accessModes = emptyList(),
                                    storageClass = "slow",
                                    volumeName = null,
                                ),
                                PersistentVolumeClaim(
                                    name = "claim-missing-pv",
                                    namespace = namespace,
                                    status = "Bound",
                                    capacity = "1Gi",
                                    accessModes = listOf("ReadWriteOnce"),
                                    storageClass = "slow",
                                    volumeName = "pv-does-not-exist",
                                ),
                            ),
                            resourceVersion = "rv-1",
                        )
                    }

                    override fun openPvcWatch(namespace: String, resourceVersion: String?): PvcWatchSession {
                        error("not used")
                    }

                    override fun listStorageClasses(): List<StorageClass> = emptyList()
                }
            },
        )

        val result = repository.listPvcs("default")

        assertTrue(result.isSuccess)
        val pvcs = result.getOrThrow().associateBy { it.name }
        assertEquals("pv-a", pvcs.getValue("claim-a").volumeName)
        assertEquals("Pending", pvcs.getValue("claim-pending").status)
        assertNull(pvcs.getValue("claim-missing-pv").volumeName)
        assertEquals("Pending", pvcs.getValue("claim-missing-pv").status)
    }

    @Test
    fun test_watchPvcs_emptyNamespace_emitsFailure() = runTest {
        val repository = PvRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            pvApiFactory = PvApiFactory {
                object : PvApi {
                    override fun listPvs(): List<PersistentVolume> = emptyList()
                    override fun listPvcsSnapshot(namespace: String): PvcListSnapshot = PvcListSnapshot(emptyList(), null)
                    override fun openPvcWatch(namespace: String, resourceVersion: String?): PvcWatchSession = error("not used")
                    override fun listStorageClasses(): List<StorageClass> = emptyList()
                }
            },
        )

        val first = repository.watchPvcs("   ").first()

        assertTrue(first.isFailure)
        assertIs<IllegalArgumentException>(first.exceptionOrNull())
    }

    @Test
    fun test_listPvs_normalizesFailedStatusCasing() = runTest {
        val repository = PvRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            pvApiFactory = PvApiFactory {
                object : PvApi {
                    override fun listPvs(): List<PersistentVolume> {
                        return listOf(
                            PersistentVolume(
                                name = "pv-failed",
                                capacity = "10Gi",
                                accessModes = listOf("ReadWriteOnce"),
                                reclaimPolicy = "Delete",
                                status = "failed",
                                storageClass = "fast",
                                claimRef = "default/claim-a",
                            ),
                        )
                    }

                    override fun listPvcsSnapshot(namespace: String): PvcListSnapshot {
                        return PvcListSnapshot(emptyList(), null)
                    }

                    override fun openPvcWatch(namespace: String, resourceVersion: String?): PvcWatchSession {
                        error("not used")
                    }

                    override fun listStorageClasses(): List<StorageClass> = emptyList()
                }
            },
        )

        val result = repository.listPvs()

        assertTrue(result.isSuccess)
        assertEquals("Failed", result.getOrThrow().single().status)
    }

    @Test
    fun test_listPvcs_marksClaimBoundToFailedPv_asPendingAndUnbound() = runTest {
        val repository = PvRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            pvApiFactory = PvApiFactory {
                object : PvApi {
                    override fun listPvs(): List<PersistentVolume> {
                        return listOf(
                            PersistentVolume(
                                name = "pv-failed",
                                capacity = "10Gi",
                                accessModes = listOf("ReadWriteOnce"),
                                reclaimPolicy = "Delete",
                                status = "Failed",
                                storageClass = "fast",
                                claimRef = "default/claim-failed",
                            ),
                        )
                    }

                    override fun listPvcsSnapshot(namespace: String): PvcListSnapshot {
                        return PvcListSnapshot(
                            pvcs = listOf(
                                PersistentVolumeClaim(
                                    name = "claim-failed",
                                    namespace = namespace,
                                    status = "Bound",
                                    capacity = "10Gi",
                                    accessModes = listOf("ReadWriteOnce"),
                                    storageClass = "fast",
                                    volumeName = "pv-failed",
                                ),
                            ),
                            resourceVersion = "rv-1",
                        )
                    }

                    override fun openPvcWatch(namespace: String, resourceVersion: String?): PvcWatchSession {
                        error("not used")
                    }

                    override fun listStorageClasses(): List<StorageClass> = emptyList()
                }
            },
        )

        val result = repository.listPvcs("default")

        assertTrue(result.isSuccess)
        val pvc = result.getOrThrow().single()
        assertEquals("Pending", pvc.status)
        assertNull(pvc.volumeName)
    }

    @Test
    fun test_watchPvcs_marksMissingOrFailedPvs_asPendingAndUnbound() = runTest {
        val watchEvents = listOf(
            PvcWatchEvent(
                type = "MODIFIED",
                pvc = PersistentVolumeClaim(
                    name = "claim-failed",
                    namespace = "default",
                    status = "Bound",
                    capacity = "10Gi",
                    accessModes = listOf("ReadWriteOnce"),
                    storageClass = "fast",
                    volumeName = "pv-failed",
                ),
                resourceVersion = "rv-2",
            ),
        )
        val repository = PvRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            watchReconnectInitialBackoffMillis = 1L,
            watchReconnectMaxBackoffMillis = 2L,
            pvApiFactory = PvApiFactory {
                object : PvApi {
                    override fun listPvs(): List<PersistentVolume> {
                        return listOf(
                            PersistentVolume(
                                name = "pv-failed",
                                capacity = "10Gi",
                                accessModes = listOf("ReadWriteOnce"),
                                reclaimPolicy = "Delete",
                                status = "Failed",
                                storageClass = "fast",
                                claimRef = "default/claim-failed",
                            ),
                        )
                    }

                    override fun listPvcsSnapshot(namespace: String): PvcListSnapshot {
                        return PvcListSnapshot(
                            pvcs = listOf(
                                PersistentVolumeClaim(
                                    name = "claim-pending",
                                    namespace = namespace,
                                    status = "pending",
                                    capacity = null,
                                    accessModes = emptyList(),
                                    storageClass = "slow",
                                    volumeName = null,
                                ),
                            ),
                            resourceVersion = "rv-1",
                        )
                    }

                    override fun openPvcWatch(namespace: String, resourceVersion: String?): PvcWatchSession {
                        return TestPvcWatchSession(watchEvents)
                    }

                    override fun listStorageClasses(): List<StorageClass> = emptyList()
                }
            },
        )

        val collected = async {
            repository.watchPvcs("default")
                .take(2)
                .toList()
        }
        testScheduler.advanceUntilIdle()
        val emissions = collected.await()

        assertEquals(2, emissions.size)
        val initial = emissions[0].getOrThrow().associateBy { it.name }
        assertEquals("Pending", initial.getValue("claim-pending").status)
        assertNull(initial.getValue("claim-pending").volumeName)

        val modified = emissions[1].getOrThrow().associateBy { it.name }
        assertEquals("Pending", modified.getValue("claim-failed").status)
        assertNull(modified.getValue("claim-failed").volumeName)
    }

    @Test
    fun test_watchPvcs_deletedEvent_removesPvcFromState() = runTest {
        val watchEvents = listOf(
            PvcWatchEvent(
                type = "DELETED",
                pvc = PersistentVolumeClaim(
                    name = "claim-a",
                    namespace = "default",
                    status = "Bound",
                    capacity = "10Gi",
                    accessModes = listOf("ReadWriteOnce"),
                    storageClass = "fast",
                    volumeName = "pv-a",
                ),
                resourceVersion = "rv-2",
            ),
        )
        val repository = PvRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            watchReconnectInitialBackoffMillis = 1L,
            watchReconnectMaxBackoffMillis = 2L,
            pvApiFactory = PvApiFactory {
                object : PvApi {
                    override fun listPvs(): List<PersistentVolume> {
                        return listOf(
                            PersistentVolume(
                                name = "pv-a",
                                capacity = "10Gi",
                                accessModes = listOf("ReadWriteOnce"),
                                reclaimPolicy = "Delete",
                                status = "Bound",
                                storageClass = "fast",
                                claimRef = "default/claim-a",
                            ),
                        )
                    }

                    override fun listPvcsSnapshot(namespace: String): PvcListSnapshot {
                        return PvcListSnapshot(
                            pvcs = listOf(
                                PersistentVolumeClaim(
                                    name = "claim-a",
                                    namespace = namespace,
                                    status = "Bound",
                                    capacity = "10Gi",
                                    accessModes = listOf("ReadWriteOnce"),
                                    storageClass = "fast",
                                    volumeName = "pv-a",
                                ),
                            ),
                            resourceVersion = "rv-1",
                        )
                    }

                    override fun openPvcWatch(namespace: String, resourceVersion: String?): PvcWatchSession {
                        return TestPvcWatchSession(watchEvents)
                    }

                    override fun listStorageClasses(): List<StorageClass> = emptyList()
                }
            },
        )

        val collected = async {
            repository.watchPvcs("default")
                .take(2)
                .toList()
        }
        testScheduler.advanceUntilIdle()
        val emissions = collected.await()

        assertEquals(2, emissions.size)
        assertEquals(1, emissions[0].getOrThrow().size)
        assertTrue(emissions[1].getOrThrow().isEmpty())
    }

    @Test
    fun test_watchPvcs_openWatch403_emitsFailureAndDoesNotReconnect() = runTest {
        var openWatchAttempts = 0
        val repository = PvRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            watchReconnectInitialBackoffMillis = 1L,
            watchReconnectMaxBackoffMillis = 2L,
            pvApiFactory = PvApiFactory {
                object : PvApi {
                    override fun listPvs(): List<PersistentVolume> = emptyList()

                    override fun listPvcsSnapshot(namespace: String): PvcListSnapshot {
                        return PvcListSnapshot(
                            pvcs = emptyList(),
                            resourceVersion = "rv-1",
                        )
                    }

                    override fun openPvcWatch(namespace: String, resourceVersion: String?): PvcWatchSession {
                        openWatchAttempts += 1
                        throw ApiException(403, "forbidden")
                    }

                    override fun listStorageClasses(): List<StorageClass> = emptyList()
                }
            },
        )

        val collected = async {
            repository.watchPvcs("default")
                .take(2)
                .toList()
        }
        testScheduler.advanceUntilIdle()
        val emissions = collected.await()

        assertEquals(2, emissions.size)
        assertTrue(emissions[0].isSuccess)
        val failure = assertFailsWith<ApiException> { emissions[1].getOrThrow() }
        assertEquals(403, failure.code)
        assertEquals(1, openWatchAttempts)
    }
}

private class TestPvcWatchSession(
    private val events: List<PvcWatchEvent>,
) : PvcWatchSession {
    override fun iterator(): Iterator<PvcWatchEvent> = events.iterator()
    override fun close() = Unit
}
