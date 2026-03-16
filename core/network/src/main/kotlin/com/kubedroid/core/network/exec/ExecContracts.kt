package com.kubedroid.core.network.exec

import kotlinx.coroutines.flow.Flow

/**
 * Parameters required to open an exec session into a pod container.
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
 * Active interactive exec session.
 */
interface ExecSession {
    val outputFlow: Flow<String>
    val errorFlow: Flow<String>
    val exitCodeFlow: Flow<Int>

    suspend fun sendInput(input: String)
    suspend fun resize(columns: Int, rows: Int)
    fun close()
}

/**
 * Factory for creating exec sessions.
 */
interface ExecSessionFactory {
    suspend fun create(request: PodExecRequest): ExecSession
}
