package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStore
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
 * ports. The Android network identity must match the saved snapshot before any saved address is
 * reused. When only the opaque network handle changed but the subnet still matches, the poll itself
 * becomes the reachability proof: a bounded verification poll re-proves the network, the saved
 * network identity is rebound, and only then are the results merged.
 */
class DefaultKnownDeviceRefreshCoordinator(
  private val networkResolver: LanNetworkResolver,
  private val localStatusCoordinator: LocalStatusCoordinator,
  private val catalogStore: DeviceCatalogStore,
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
    if (currentNetwork == expectedNetwork) {
      return KnownDeviceRefreshOutcome(
        catalog = localStatusCoordinator.poll(catalog, currentNetwork),
        network = currentNetwork,
      )
    }
    if (!sameSubnet(currentNetwork, expectedNetwork)) throw discoveryRequired()

    // Re-verify tier: same subnet, changed handle or address. A bounded non-merging poll proves
    // reachability before anything is rebound or merged, so a wrong network can never overwrite
    // last-known-good status with offline records.
    val targetCount = catalog.currentStatusTargetCount()
    val verification =
      localStatusCoordinator.pollUnmerged(
        catalog = catalog,
        network = currentNetwork,
        maxAttempts = if (targetCount <= VERIFICATION_DEVICE_LIMIT) MAX_POLL_ATTEMPTS else 1,
        limit = VERIFICATION_DEVICE_LIMIT,
      )
    if (verification.respondedDeviceCount == 0) throw unverified()

    val rebound = catalogStore.rebindDiscoveryNetwork(currentNetwork)
    return if (verification.devices.size >= targetCount) {
      // Small household: the verification poll already covered every target, so merge it instead
      // of paying for a second poll.
      KnownDeviceRefreshOutcome(
        catalog = catalogStore.mergeLocalPoll(verification),
        network = currentNetwork,
      )
    } else {
      KnownDeviceRefreshOutcome(
        catalog = localStatusCoordinator.poll(rebound, currentNetwork),
        network = currentNetwork,
      )
    }
  }

  private fun discoveryRequired() =
    LocalStatusException(
      code = "LOCAL_REFRESH_DISCOVERY_REQUIRED",
      message = "Saved device addresses are not current on this Wi-Fi. Find devices again.",
    )

  private fun unverified() =
    LocalStatusException(
      code = "LOCAL_REFRESH_UNVERIFIED",
      message = "The saved devices did not answer on this Wi-Fi. Find devices again.",
    )

  private companion object {
    const val VERIFICATION_DEVICE_LIMIT = 4
    const val MAX_POLL_ATTEMPTS = 3
  }
}

fun DeviceCatalog.hasCurrentKnownStatusTargets(): Boolean = currentStatusTargetCount() > 0

fun DeviceCatalog.currentStatusTargetCount(): Int {
  val discoveryAt = lastDiscoveryAtEpochMillis ?: return 0
  val eligibleDevices = statusEligibleDevices()
  return lanDevices.count { record ->
    val device = eligibleDevices[record.id] ?: return@count false
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
