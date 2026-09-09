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

  /**
   * Polls without merging anything into the saved catalog. Used for network re-verification, so an
   * unverified attempt can never overwrite last-known-good status with offline records.
   * [maxAttempts] must be in 1..3; [limit] bounds the device count after the success-first ordering
   * (devices whose last status responded first, then by id).
   */
  suspend fun pollUnmerged(
    catalog: DeviceCatalog,
    network: LanNetworkContext,
    maxAttempts: Int,
    limit: Int,
  ): LocalPollResult
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
    val currentLanById =
      catalog.lanDevices
        .filter { it.lastSeenAtEpochMillis == lastDiscoveryAt }
        .associateBy { it.id }
    val devices =
      catalog.devices
        .asSequence()
        .filter { cloudDevice ->
          LocalDeviceCapabilityRegistry.canPollStatus(cloudDevice) && !cloudDevice.localKey.isBlank
        }
        .mapNotNull { cloudDevice ->
          val lanDevice = currentLanById[cloudDevice.id] ?: return@mapNotNull null
          val protocolVersion = lanDevice.protocolVersion.ifBlank { cloudDevice.protocolVersion }
          if (!isSupportedLocalProtocol(protocolVersion)) return@mapNotNull null
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

    val result =
      try {
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

  override suspend fun pollUnmerged(
    catalog: DeviceCatalog,
    network: LanNetworkContext,
    maxAttempts: Int,
    limit: Int,
  ): LocalPollResult {
    require(maxAttempts in MIN_ATTEMPTS..MAX_ATTEMPTS) {
      "Verification poll attempt count is out of range."
    }
    require(limit in 1..MAX_DEVICES_PER_REFRESH) {
      "Verification poll device limit is out of range."
    }
    val lastDiscoveryAt = catalog.lastDiscoveryAtEpochMillis ?: return emptyResult()
    val currentLanById =
      catalog.lanDevices
        .filter { it.lastSeenAtEpochMillis == lastDiscoveryAt }
        .associateBy { it.id }
    val lastRespondedIds =
      catalog.localStatus
        .filter { it.state == LocalPollDeviceState.RESPONDED }
        .associateBy { it.id }
    val devices =
      catalog.devices
        .asSequence()
        .filter { cloudDevice ->
          LocalDeviceCapabilityRegistry.canPollStatus(cloudDevice) && !cloudDevice.localKey.isBlank
        }
        .mapNotNull { cloudDevice ->
          val lanDevice = currentLanById[cloudDevice.id] ?: return@mapNotNull null
          val protocolVersion = lanDevice.protocolVersion.ifBlank { cloudDevice.protocolVersion }
          if (!isSupportedLocalProtocol(protocolVersion)) return@mapNotNull null
          LocalPollDevice(
            id = cloudDevice.id,
            ip = lanDevice.ip,
            localKey = cloudDevice.localKey,
            protocolVersion = protocolVersion,
          )
        }
        .toList()
    if (devices.isEmpty()) return emptyResult()

    // Success-first, then id: powered devices prove the network fastest, and the order stays
    // deterministic for tests.
    val ordered =
      devices
        .sortedWith(
          compareByDescending<LocalPollDevice> { it.id in lastRespondedIds }.thenBy { it.id }
        )
        .take(limit)

    return try {
      gateway.pollLocal(
        LocalPollRequest(network = network, devices = ordered, maxAttempts = maxAttempts)
      )
    } catch (error: CancellationException) {
      throw error
    } catch (error: PythonBridgeException) {
      throw LocalStatusException(
        code = error.code,
        message = error.message ?: "Local device status could not be read.",
      )
    }
  }

  private fun emptyResult() =
    LocalPollResult(
      contractVersion = 0,
      deviceCount = 0,
      respondedDeviceCount = 0,
      offlineDeviceCount = 0,
      errorDeviceCount = 0,
      durationMillis = 0,
      warnings = listOf("NO_CURRENT_STATUS_TARGETS"),
      devices = emptyList(),
    )

  private companion object {
    const val MAX_DEVICES_PER_REFRESH = 32
    const val MIN_ATTEMPTS = 1
    const val MAX_ATTEMPTS = 3
  }
}

internal fun isSupportedLocalProtocol(version: String): Boolean =
  version in SUPPORTED_LOCAL_PROTOCOLS

private val SUPPORTED_LOCAL_PROTOCOLS = setOf("3.1", "3.2", "3.3", "3.4", "3.5")
