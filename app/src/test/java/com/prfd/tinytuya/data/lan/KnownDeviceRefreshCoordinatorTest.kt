package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownDeviceRefreshCoordinatorTest {
  @Test
  fun matchingNetworkPollsTheVerifiedAddressWithoutDiscovery() = runBlocking {
    val polled = discoveredCatalog().copy(lastLocalPollAtEpochMillis = 6L)
    val resolver = FakeNetworkResolver(NETWORK)
    val status = FakeLocalStatusCoordinator(polledCatalog = polled)
    val store = FakeCatalogStore(discoveredCatalog())
    val coordinator = DefaultKnownDeviceRefreshCoordinator(resolver, status, store)

    val outcome = coordinator.refresh(discoveredCatalog())

    assertEquals(NETWORK, outcome.network)
    assertEquals(6L, outcome.catalog.lastLocalPollAtEpochMillis)
    assertEquals(1, resolver.callCount)
    assertEquals(1, status.callCount)
    assertEquals(0, status.unmergedCallCount)
    assertNull(store.reboundNetwork)
    assertTrue(store.mergedResults.isEmpty())
    assertEquals("192.168.10.42", status.catalog?.lanDevices?.single()?.ip)
  }

  @Test
  fun sameSubnetNewHandleVerifiesByPollAndRebindsSmallHousehold() = runBlocking {
    val currentNetwork = NETWORK.copy(networkHandle = 202L, localIpv4 = "192.168.10.9")
    val resolver = FakeNetworkResolver(currentNetwork)
    val status =
      FakeLocalStatusCoordinator(
        unmergedResult = verificationResult(respondedIds = setOf("saved-device"))
      )
    val store = FakeCatalogStore(discoveredCatalog())
    val coordinator = DefaultKnownDeviceRefreshCoordinator(resolver, status, store)

    val outcome = coordinator.refresh(discoveredCatalog())

    assertEquals(currentNetwork, outcome.network)
    assertEquals(1, status.unmergedCallCount)
    assertEquals(0, status.callCount)
    assertEquals(3, status.unmergedMaxAttempts)
    assertEquals(VERIFICATION_DEVICE_LIMIT_FOR_TEST, status.unmergedLimit)
    assertEquals(currentNetwork, store.reboundNetwork)
    assertEquals(1, store.mergedResults.size)
    assertEquals(1, store.mergedResults.single().respondedDeviceCount)
  }

  @Test
  fun largeHouseholdProbesSingleAttemptThenFullPoll() = runBlocking {
    val currentNetwork = NETWORK.copy(networkHandle = 202L)
    val resolver = FakeNetworkResolver(currentNetwork)
    val status =
      FakeLocalStatusCoordinator(
        polledCatalog = discoveredCatalog(5).copy(lastLocalPollAtEpochMillis = 6L),
        unmergedResult =
          verificationResult(
            respondedIds = setOf("saved-device-1"),
            polledIds = (1..4).map { "saved-device-$it" },
          ),
      )
    val store = FakeCatalogStore(discoveredCatalog(5))
    val coordinator = DefaultKnownDeviceRefreshCoordinator(resolver, status, store)

    val outcome = coordinator.refresh(discoveredCatalog(5))

    assertEquals(currentNetwork, outcome.network)
    assertEquals(1, status.unmergedCallCount)
    assertEquals(1, status.unmergedMaxAttempts)
    assertEquals(4, status.unmergedLimit)
    assertEquals(currentNetwork, store.reboundNetwork)
    assertTrue(store.mergedResults.isEmpty())
    assertEquals(1, status.callCount)
    assertEquals(6L, outcome.catalog.lastLocalPollAtEpochMillis)
  }

  @Test
  fun zeroVerificationResponsesFailWithoutRebindingOrMerging() = runBlocking {
    val resolver = FakeNetworkResolver(NETWORK.copy(networkHandle = 202L))
    val status =
      FakeLocalStatusCoordinator(unmergedResult = verificationResult(respondedIds = emptySet()))
    val store = FakeCatalogStore(discoveredCatalog())
    val coordinator = DefaultKnownDeviceRefreshCoordinator(resolver, status, store)

    val error = runCatching { coordinator.refresh(discoveredCatalog()) }.exceptionOrNull()

    assertTrue(error is LocalStatusException)
    assertEquals("LOCAL_REFRESH_UNVERIFIED", (error as LocalStatusException).code)
    assertEquals(1, status.unmergedCallCount)
    assertEquals(0, status.callCount)
    assertNull(store.reboundNetwork)
    assertTrue(store.mergedResults.isEmpty())
  }

  @Test
  fun differentSubnetRequiresDiscoveryBeforeAnyPoll() = runBlocking {
    val resolver =
      FakeNetworkResolver(
        NETWORK.copy(localIpv4 = "192.168.20.5", broadcastIpv4 = "192.168.20.255")
      )
    val status = FakeLocalStatusCoordinator()
    val store = FakeCatalogStore(discoveredCatalog())
    val coordinator = DefaultKnownDeviceRefreshCoordinator(resolver, status, store)

    val error = runCatching { coordinator.refresh(discoveredCatalog()) }.exceptionOrNull()

    assertTrue(error is LocalStatusException)
    assertEquals("LOCAL_REFRESH_DISCOVERY_REQUIRED", (error as LocalStatusException).code)
    assertEquals(1, resolver.callCount)
    assertEquals(0, status.callCount)
    assertEquals(0, status.unmergedCallCount)
    assertNull(store.reboundNetwork)
  }

  @Test
  fun differentNetworkHandleWithinOneAttemptTierStaysDeterministic() = runBlocking {
    val resolver = FakeNetworkResolver(NETWORK.copy(networkHandle = 202L))
    val status =
      FakeLocalStatusCoordinator(
        unmergedResult =
          verificationResult(
            respondedIds = setOf("saved-device-1"),
            polledIds = listOf("saved-device-1", "saved-device-2"),
          )
      )
    val store = FakeCatalogStore(discoveredCatalog(2))
    val coordinator = DefaultKnownDeviceRefreshCoordinator(resolver, status, store)

    coordinator.refresh(discoveredCatalog(2))

    assertEquals(3, status.unmergedMaxAttempts)
    assertEquals(4, status.unmergedLimit)
    assertTrue(store.mergedResults.isNotEmpty())
  }

  @Test
  fun staleAddressGenerationRequiresDiscoveryBeforeNetworkResolution() = runBlocking {
    val stale =
      discoveredCatalog()
        .copy(
          lanDevices =
            listOf(discoveredCatalog().lanDevices.single().copy(lastSeenAtEpochMillis = 4L))
        )
    val resolver = FakeNetworkResolver(NETWORK)
    val status = FakeLocalStatusCoordinator()
    val store = FakeCatalogStore(stale)
    val coordinator = DefaultKnownDeviceRefreshCoordinator(resolver, status, store)

    val error = runCatching { coordinator.refresh(stale) }.exceptionOrNull()

    assertTrue(stale.hasPreviouslyMatchedStatusTargets())
    assertFalse(stale.hasCurrentKnownStatusTargets())
    assertEquals("LOCAL_REFRESH_DISCOVERY_REQUIRED", (error as LocalStatusException).code)
    assertEquals(0, resolver.callCount)
    assertEquals(0, status.callCount)
    assertEquals(0, status.unmergedCallCount)
  }

  private class FakeNetworkResolver(private val network: LanNetworkContext) : LanNetworkResolver {
    var callCount = 0

    override suspend fun resolve(): LanNetworkContext {
      callCount += 1
      return network
    }
  }

  private class FakeLocalStatusCoordinator(
    private val polledCatalog: DeviceCatalog? = null,
    private val unmergedResult: LocalPollResult? = null,
  ) : LocalStatusCoordinator {
    var callCount = 0
    var catalog: DeviceCatalog? = null
    var network: LanNetworkContext? = null
    var unmergedCallCount = 0
    var unmergedCatalog: DeviceCatalog? = null
    var unmergedMaxAttempts = 0
    var unmergedLimit = 0

    override suspend fun poll(
      catalog: DeviceCatalog,
      network: LanNetworkContext,
    ): DeviceCatalog {
      callCount += 1
      this.catalog = catalog
      this.network = network
      return polledCatalog ?: catalog
    }

    override suspend fun pollUnmerged(
      catalog: DeviceCatalog,
      network: LanNetworkContext,
      maxAttempts: Int,
      limit: Int,
    ): LocalPollResult {
      unmergedCallCount += 1
      unmergedCatalog = catalog
      unmergedMaxAttempts = maxAttempts
      unmergedLimit = limit
      return unmergedResult ?: error("unexpected pollUnmerged call")
    }
  }

  private class FakeCatalogStore(private var catalog: DeviceCatalog) : DeviceCatalogStore {
    var reboundNetwork: LanNetworkContext? = null
    val mergedResults = mutableListOf<LocalPollResult>()

    override suspend fun load(): DeviceCatalog? = catalog

    override suspend fun replaceFromCloud(result: com.prfd.tinytuya.data.python.CloudImportResult) =
      catalog

    override suspend fun mergeLanDiscovery(
      result: LanDiscoveryResult,
      network: LanNetworkContext,
    ): DeviceCatalog = catalog

    override suspend fun mergeLocalPoll(result: LocalPollResult): DeviceCatalog {
      mergedResults.add(result)
      return catalog
    }

    override suspend fun rebindDiscoveryNetwork(network: LanNetworkContext): DeviceCatalog {
      reboundNetwork = network
      return catalog
    }

    override suspend fun deleteAll() {}
  }

  private companion object {
    const val VERIFICATION_DEVICE_LIMIT_FOR_TEST = 4

    val NETWORK =
      LanNetworkContext(
        interfaceName = "wlan0",
        localIpv4 = "192.168.10.5",
        prefixLength = 24,
        broadcastIpv4 = "192.168.10.255",
        networkHandle = 101L,
      )

    fun verificationResult(
      respondedIds: Set<String>,
      polledIds: List<String> = respondedIds.toList(),
    ): LocalPollResult {
      val devices = polledIds.map { id ->
        val responded = id in respondedIds
        LocalPolledDevice(
          id = id,
          state = if (responded) LocalPollDeviceState.RESPONDED else LocalPollDeviceState.OFFLINE,
          errorCode = if (responded) "" else "LOCAL_DEVICE_TIMEOUT",
          durationMillis = 10L,
          attemptCount = 1,
          dataPoints = emptyList(),
        )
      }
      val responded = devices.count { it.state == LocalPollDeviceState.RESPONDED }
      return LocalPollResult(
        contractVersion = 1,
        deviceCount = devices.size,
        respondedDeviceCount = responded,
        offlineDeviceCount = devices.size - responded,
        errorDeviceCount = 0,
        durationMillis = 10L,
        warnings = emptyList(),
        devices = devices,
      )
    }

    fun discoveredCatalog(deviceCount: Int = 1): DeviceCatalog =
      DeviceCatalog(
        schemaVersion = 4,
        importedAtEpochMillis = 1L,
        region = TuyaCloudRegion.WESTERN_AMERICA,
        devices =
          (1..deviceCount).map { index ->
            CloudImportedDevice(
              id = "saved-device" + if (deviceCount == 1) "" else "-$index",
              name = "Saved switch $index",
              localKey = SensitiveString.of("saved-local-key"),
              category = "kg",
              productId = "",
              productName = "Switch",
              model = "",
              mac = "",
              uuid = "",
              isSubDevice = false,
              gatewayId = "",
              nodeId = "",
              protocolVersion = "3.5",
              lastIp = "",
              mappingJson = "{\"1\":{\"code\":\"switch_1\",\"type\":\"Boolean\"}}",
            )
          },
        lastDiscoveryAtEpochMillis = 5L,
        lastDiscoveryNetwork = NETWORK,
        lanDevices =
          (1..deviceCount).map { index ->
            LanDeviceRecord(
              id = "saved-device" + if (deviceCount == 1) "" else "-$index",
              ip = "192.168.10." + (41 + index),
              protocolVersion = "3.5",
              productKey = "",
              mac = "",
              origin = "broadcast",
              lastSeenAtEpochMillis = 5L,
            )
          },
      )
  }
}
