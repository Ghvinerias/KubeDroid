package com.kubedroid.feature.pods.exec.impl

import com.kubedroid.core.network.exec.ExecSessionFactory
import com.kubedroid.feature.pods.exec.ExecRepository
import com.kubedroid.feature.pods.exec.PodExecRequest
import com.kubedroid.feature.pods.exec.PodExecSession
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultExecRepository @Inject constructor(
    private val execSessionFactory: ExecSessionFactory,
) : ExecRepository {

    override suspend fun createSession(request: PodExecRequest): Result<PodExecSession> {
        return runCatching {
            val networkSession = execSessionFactory.create(request.toNetwork())
            NetworkBackedExecSession(networkSession)
        }
    }

    private fun PodExecRequest.toNetwork(): com.kubedroid.core.network.exec.PodExecRequest {
        return com.kubedroid.core.network.exec.PodExecRequest(
            apiServer = apiServer,
            namespace = namespace,
            podName = podName,
            containerName = containerName,
            command = command,
            bearerToken = bearerToken,
        )
    }

    private class NetworkBackedExecSession(
        private val delegate: com.kubedroid.core.network.exec.ExecSession,
    ) : PodExecSession {
        override val outputFlow = delegate.outputFlow
        override val errorFlow = delegate.errorFlow
        override val exitCodeFlow = delegate.exitCodeFlow

        override suspend fun sendInput(input: String) {
            delegate.sendInput(input)
        }

        override suspend fun resize(columns: Int, rows: Int) {
            delegate.resize(columns, rows)
        }

        override fun close() {
            delegate.close()
        }
    }
}
