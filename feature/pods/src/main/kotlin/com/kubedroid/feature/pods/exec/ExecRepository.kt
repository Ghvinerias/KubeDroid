package com.kubedroid.feature.pods.exec

import kotlinx.coroutines.flow.Flow

/**
 * Parameters required to open an exec terminal session for a pod container.
 */
data class PodExecRequest(
    val apiServer: String,
    val namespace: String,
    val podName: String,
    val containerName: String? = null,
    val command: List<String>,
    val bearerToken: String? = null,
)

/**
 * Live exec session contract used by presentation layer.
 */
interface PodExecSession {
    val outputFlow: Flow<String>
    val errorFlow: Flow<String>
    val exitCodeFlow: Flow<Int>

    suspend fun sendInput(input: String)
    suspend fun resize(columns: Int, rows: Int)
    fun close()
}

/**
 * Contract for creating and managing pod exec sessions.
 */
interface ExecRepository {
    suspend fun createSession(request: PodExecRequest): Result<PodExecSession>
}
