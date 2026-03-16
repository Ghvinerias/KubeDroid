package com.kubedroid.feature.pods.portforward.impl

import com.kubedroid.core.network.portforward.PortForwardRequest
import com.kubedroid.core.network.portforward.PortForwardSession
import com.kubedroid.core.network.portforward.PortForwardSessionImpl
import com.kubedroid.feature.pods.portforward.PodPortForwardRequest
import com.kubedroid.feature.pods.portforward.PortForwardRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultPortForwardRepository @Inject constructor() : PortForwardRepository {

    override suspend fun createSession(request: PodPortForwardRequest): Result<PortForwardSession> {
        return runCatching {
            PortForwardSessionImpl.create(
                request = PortForwardRequest(
                    apiServer = request.apiServer,
                    namespace = request.namespace,
                    podName = request.podName,
                    remotePort = request.remotePort,
                    localPort = request.localPort ?: 0,
                    bearerToken = request.bearerToken,
                ),
            )
        }
    }
}
