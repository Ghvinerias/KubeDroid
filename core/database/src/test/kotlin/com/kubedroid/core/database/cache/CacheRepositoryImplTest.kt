package com.kubedroid.core.database.cache

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CacheRepositoryImplTest {

    private var database: CacheDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun cacheResources_thenGetCachedResources_returnsStoredData() = runBlocking {
        val db = createDatabase()
        val repository = CacheRepositoryImpl(cacheDatabase = db)
        val fetchedAt = 10_000L

        val cacheResult = repository.cacheResources(
            contextName = "  prod-context  ",
            pods = listOf(
                CachedPod(
                    contextName = "ignored",
                    namespace = "default",
                    name = "api",
                    status = "Running",
                    lastFetchedAt = 1L,
                ),
            ),
            deployments = listOf(
                CachedDeployment(
                    contextName = "ignored",
                    namespace = "default",
                    name = "web",
                    desiredReplicas = 3,
                    availableReplicas = 2,
                    lastFetchedAt = 1L,
                ),
            ),
            nodes = listOf(
                CachedNode(
                    contextName = "ignored",
                    name = "node-a",
                    status = "Ready",
                    lastFetchedAt = 1L,
                ),
            ),
            namespaces = listOf(
                CachedNamespace(
                    contextName = "ignored",
                    name = "default",
                    status = "Active",
                    lastFetchedAt = 1L,
                ),
            ),
            fetchedAt = fetchedAt,
        )

        assertTrue(cacheResult.isSuccess)

        val readResult = repository.getCachedResources(contextName = "prod-context", namespace = "default")
        assertTrue(readResult.isSuccess)
        val resources = readResult.getOrThrow()

        assertEquals(1, resources.pods.size)
        assertEquals("prod-context", resources.pods.single().contextName)
        assertEquals(fetchedAt, resources.pods.single().lastFetchedAt)

        assertEquals(1, resources.deployments.size)
        assertEquals("prod-context", resources.deployments.single().contextName)
        assertEquals(fetchedAt, resources.deployments.single().lastFetchedAt)

        assertEquals(1, resources.nodes.size)
        assertEquals("prod-context", resources.nodes.single().contextName)
        assertEquals(fetchedAt, resources.nodes.single().lastFetchedAt)

        assertEquals(1, resources.namespaces.size)
        assertEquals("prod-context", resources.namespaces.single().contextName)
        assertEquals(fetchedAt, resources.namespaces.single().lastFetchedAt)
    }

    @Test
    fun getCacheAge_respectsTtlBoundary() = runBlocking {
        val db = createDatabase()
        val contextName = "prod-context"
        val fetchedAt = 1_000L

        val writer = CacheRepositoryImpl(cacheDatabase = db)
        assertTrue(
            writer.cacheResources(
                contextName = contextName,
                nodes = listOf(
                    CachedNode(
                        contextName = contextName,
                        name = "node-a",
                        status = "Ready",
                        lastFetchedAt = 0L,
                    ),
                ),
                fetchedAt = fetchedAt,
            ).isSuccess,
        )

        val freshRepository = CacheRepositoryImpl(
            cacheDatabase = db,
            nowProviderMillis = { fetchedAt + 120_000L },
        )
        val staleRepository = CacheRepositoryImpl(
            cacheDatabase = db,
            nowProviderMillis = { fetchedAt + 120_001L },
        )

        val fresh = freshRepository.getCacheAge(contextName).getOrThrow()
        val stale = staleRepository.getCacheAge(contextName).getOrThrow()

        assertTrue(fresh.isFresh)
        assertFalse(stale.isFresh)
        assertEquals(fetchedAt, fresh.lastFetchedAt)
        assertEquals(fetchedAt, stale.lastFetchedAt)
    }

    @Test
    fun getCachedResources_whenStorageFails_returnsFailureResult() = runBlocking {
        val db = createDatabase()
        val repository = CacheRepositoryImpl(cacheDatabase = db)
        db.close()

        val result = repository.getCachedResources(contextName = "prod-context")

        assertTrue(result.isFailure)
    }

    private fun createDatabase(): CacheDatabase {
        return Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CacheDatabase::class.java,
        ).build().also { database = it }
    }
}
