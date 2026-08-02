package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.python.PythonBridgeException
import com.prfd.tinytuya.data.python.TuyaPythonGateway
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface LocalControlCoordinator {
    suspend fun setBoolean(
        catalog: DeviceCatalog,
        expectedNetwork: LanNetworkContext,
        deviceId: String,
        dataPointId: String,
        value: Boolean,
    ): DeviceCatalog
}

class DefaultLocalControlCoordinator(
    private val gateway: TuyaPythonGateway,
    private val catalogStore: DeviceCatalogStore,
    private val networkResolver: LanNetworkResolver,
) : LocalControlCoordinator {
    private val deviceLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun setBoolean(
        catalog: DeviceCatalog,
        expectedNetwork: LanNetworkContext,
        deviceId: String,
        dataPointId: String,
        value: Boolean,
    ): DeviceCatalog = deviceLocks.getOrPut(deviceId) { Mutex() }.withLock {
        val currentNetwork = try {
            networkResolver.resolve()
        } catch (error: CancellationException) {
            throw error
        } catch (error: LanDiscoveryException) {
            throw LocalControlException(
                code = error.code,
                message = error.message ?: "The active Wi-Fi network is unavailable.",
            )
        }
        if (currentNetwork != expectedNetwork) {
            throw controlError(
                code = "LOCAL_CONTROL_NETWORK_CHANGED",
                message = "Wi-Fi changed after the last refresh. Refresh local devices before controlling them.",
            )
        }

        val lastDiscoveryAt = catalog.lastDiscoveryAtEpochMillis ?: throw refreshRequired()
        val device = catalog.devices.singleOrNull { it.id == deviceId }
            ?: throw unsupportedControl()
        val lanRecord = catalog.lanDevices.singleOrNull {
            it.id == deviceId && it.lastSeenAtEpochMillis == lastDiscoveryAt
        } ?: throw refreshRequired()
        val localStatus = catalog.localStatus.singleOrNull { it.id == deviceId }
            ?: throw refreshRequired()
        val capability = LocalDeviceCapabilityRegistry.booleanControls(
            device = device,
            status = localStatus,
            lastDiscoveryAtEpochMillis = lastDiscoveryAt,
        ).singleOrNull { it.dataPointId == dataPointId }
            ?: throw unsupportedControl()
        if (capability.currentValue == value) return@withLock catalog

        val protocolVersion = lanRecord.protocolVersion.ifBlank { device.protocolVersion }
        if (
            device.isSubDevice ||
            device.localKey.isBlank ||
            protocolVersion !in SUPPORTED_LOCAL_PROTOCOLS
        ) {
            throw unsupportedControl()
        }

        val result = try {
            gateway.setLocalValues(
                LocalControlRequest(
                    network = currentNetwork,
                    device = LocalControlDevice(
                        id = device.id,
                        ip = lanRecord.ip,
                        localKey = device.localKey,
                        protocolVersion = protocolVersion,
                    ),
                    changes = listOf(
                        LocalControlChange(
                            id = capability.dataPointId,
                            kind = LocalDataPointKind.BOOLEAN,
                            value = value.toString(),
                        )
                    ),
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: PythonBridgeException) {
            throw LocalControlException(
                code = error.code,
                message = error.message ?: "The local command could not be sent safely.",
            )
        }

        val statusState = when {
            result.state == LocalControlState.CONFIRMED -> LocalPollDeviceState.RESPONDED
            result.dataPoints.isNotEmpty() -> LocalPollDeviceState.RESPONDED
            result.state == LocalControlState.OFFLINE -> LocalPollDeviceState.OFFLINE
            else -> LocalPollDeviceState.ERROR
        }
        val statusErrorCode = if (statusState == LocalPollDeviceState.RESPONDED) {
            ""
        } else {
            result.errorCode.ifBlank { "LOCAL_CONTROL_FAILED" }
        }
        val updatedCatalog = catalogStore.mergeLocalPoll(
            LocalPollResult(
                contractVersion = result.contractVersion,
                deviceCount = 1,
                respondedDeviceCount = if (statusState == LocalPollDeviceState.RESPONDED) 1 else 0,
                offlineDeviceCount = if (statusState == LocalPollDeviceState.OFFLINE) 1 else 0,
                errorDeviceCount = if (statusState == LocalPollDeviceState.ERROR) 1 else 0,
                durationMillis = result.durationMillis,
                warnings = emptyList(),
                devices = listOf(
                    LocalPolledDevice(
                        id = result.id,
                        state = statusState,
                        errorCode = statusErrorCode,
                        durationMillis = result.durationMillis,
                        dataPoints = if (statusState == LocalPollDeviceState.RESPONDED) {
                            result.dataPoints
                        } else {
                            emptyList()
                        },
                    )
                ),
            )
        )

        if (result.state != LocalControlState.CONFIRMED) {
            throw LocalControlException(
                code = result.errorCode.ifBlank { "LOCAL_CONTROL_FAILED" },
                message = localControlMessage(result.errorCode),
                updatedCatalog = updatedCatalog,
            )
        }
        updatedCatalog
    }

    private fun refreshRequired() = controlError(
        code = "LOCAL_CONTROL_REFRESH_REQUIRED",
        message = "Refresh local devices before controlling this device.",
    )

    private fun unsupportedControl() = controlError(
        code = "LOCAL_CONTROL_UNSUPPORTED",
        message = "This control is not supported by the verified local device profile.",
    )

    private fun controlError(code: String, message: String) =
        LocalControlException(code = code, message = message)

    private fun localControlMessage(code: String): String = when (code) {
        "LOCAL_DEVICE_OFFLINE" -> "The device went offline before the command was confirmed."
        "LOCAL_DEVICE_TIMEOUT", "LOCAL_CONTROL_UNCONFIRMED" ->
            "The device did not confirm its new state. Refresh before trying again."
        "LOCAL_KEY_OR_VERSION_INVALID" ->
            "The device rejected the saved local key or protocol version."
        "LOCAL_CONTROL_NOT_APPLIED" ->
            "The device answered, but its state did not change to the requested value."
        "LOCAL_PROTOCOL_ERROR" -> "The device response could not be decoded safely."
        else -> "The local command could not be confirmed safely."
    }

    private companion object {
        val SUPPORTED_LOCAL_PROTOCOLS = setOf("3.1", "3.2", "3.3", "3.4", "3.5")
    }
}
