package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.python.PythonBridgeException
import com.prfd.tinytuya.data.python.TuyaPythonGateway
import java.util.concurrent.CancellationException

interface LocalStatusCoordinator {
    suspend fun poll(
        catalog: DeviceCatalog,
        network: LanNetworkContext,
    ): DeviceCatalog
}

class DefaultLocalStatusCoordinator(
    private val gateway: TuyaPythonGateway,
    private val catalogStore: DeviceCatalogStore,
) : LocalStatusCoordinator {
    override suspend fun poll(
        catalog: DeviceCatalog,
        network: LanNetworkContext,
    ): DeviceCatalog {
        val lastDiscoveryAt = catalog.lastDiscoveryAtEpochMillis ?: return catalog
        val currentLanById = catalog.lanDevices
            .filter { it.lastSeenAtEpochMillis == lastDiscoveryAt }
            .associateBy { it.id }
        val devices = catalog.devices
            .asSequence()
            .filterNot { it.isSubDevice || it.localKey.isBlank }
            .mapNotNull { cloudDevice ->
                val lanDevice = currentLanById[cloudDevice.id] ?: return@mapNotNull null
                val protocolVersion = lanDevice.protocolVersion
                    .ifBlank { cloudDevice.protocolVersion }
                if (protocolVersion !in SUPPORTED_LOCAL_PROTOCOLS) return@mapNotNull null
                LocalPollDevice(
                    id = cloudDevice.id,
                    ip = lanDevice.ip,
                    localKey = cloudDevice.localKey,
                    protocolVersion = protocolVersion,
                )
            }
            .sortedBy { it.id }
            .take(MAX_DEVICES_PER_REFRESH)
            .toList()

        if (devices.isEmpty()) return catalog

        val result = try {
            gateway.pollLocal(LocalPollRequest(network = network, devices = devices))
        } catch (error: CancellationException) {
            throw error
        } catch (error: PythonBridgeException) {
            throw LocalStatusException(
                code = error.code,
                message = error.message ?: "Local device status could not be read.",
            )
        }
        return catalogStore.mergeLocalPoll(result)
    }

    private companion object {
        const val MAX_DEVICES_PER_REFRESH = 32
        val SUPPORTED_LOCAL_PROTOCOLS = setOf("3.1", "3.2", "3.3", "3.4", "3.5")
    }
}
