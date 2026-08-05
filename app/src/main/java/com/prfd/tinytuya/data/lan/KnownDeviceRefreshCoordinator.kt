package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.data.local.DeviceCatalog
import java.util.concurrent.CancellationException

data class KnownDeviceRefreshOutcome(
  val catalog: DeviceCatalog,
  val network: LanNetworkContext,
)

fun interface KnownDeviceRefreshCoordinator {
  suspend fun refresh(catalog: DeviceCatalog): KnownDeviceRefreshOutcome
}

object UnavailableKnownDeviceRefreshCoordinator : KnownDeviceRefreshCoordinator {
  override suspend fun refresh(catalog: DeviceCatalog): KnownDeviceRefreshOutcome {
    throw LocalStatusException(
      code = "LOCAL_REFRESH_UNAVAILABLE",
      message = "Quick local refresh is unavailable.",
    )
  }
}

/**
 * Reads status from addresses verified by the most recent discovery, without opening UDP discovery
 * ports. The Android network identity must still match exactly before any saved address is reused.
 */
class DefaultKnownDeviceRefreshCoordinator(
  private val networkResolver: LanNetworkResolver,
  private val localStatusCoordinator: LocalStatusCoordinator,
) : KnownDeviceRefreshCoordinator {
  override suspend fun refresh(catalog: DeviceCatalog): KnownDeviceRefreshOutcome {
    val expectedNetwork = catalog.lastDiscoveryNetwork ?: throw discoveryRequired()
    if (!catalog.hasCurrentKnownStatusTargets()) throw discoveryRequired()

    val currentNetwork =
      try {
        networkResolver.resolve()
      } catch (error: CancellationException) {
        throw error
      } catch (error: LanDiscoveryException) {
        throw LocalStatusException(
          code = error.code,
          message = error.message ?: "The active Wi-Fi network is unavailable.",
        )
      }
    if (currentNetwork != expectedNetwork) throw discoveryRequired()

    return KnownDeviceRefreshOutcome(
      catalog = localStatusCoordinator.poll(catalog, currentNetwork),
      network = currentNetwork,
    )
  }

  private fun discoveryRequired() =
    LocalStatusException(
      code = "LOCAL_REFRESH_DISCOVERY_REQUIRED",
      message = "Saved device addresses are not current on this Wi-Fi. Find devices again.",
    )
}

fun DeviceCatalog.hasCurrentKnownStatusTargets(): Boolean {
  val discoveryAt = lastDiscoveryAtEpochMillis ?: return false
  val eligibleDevices = statusEligibleDevices()
  return lanDevices.any { record ->
    val device = eligibleDevices[record.id] ?: return@any false
    record.lastSeenAtEpochMillis == discoveryAt &&
      isSupportedLocalProtocol(record.protocolVersion.ifBlank { device.protocolVersion })
  }
}

fun DeviceCatalog.hasPreviouslyMatchedStatusTargets(): Boolean {
  val eligibleDevices = statusEligibleDevices()
  return lanDevices.any { record ->
    val device = eligibleDevices[record.id] ?: return@any false
    isSupportedLocalProtocol(record.protocolVersion.ifBlank { device.protocolVersion })
  }
}

private fun DeviceCatalog.statusEligibleDevices() =
  devices
    .asSequence()
    .filter(LocalDeviceCapabilityRegistry::canPollStatus)
    .filter { device -> !device.localKey.isBlank }
    .associateBy { device -> device.id }
