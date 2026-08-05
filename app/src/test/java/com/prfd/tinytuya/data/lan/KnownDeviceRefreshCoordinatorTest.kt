package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownDeviceRefreshCoordinatorTest {
  @Test
  fun matchingNetworkPollsTheVerifiedAddressWithoutDiscovery() = runBlocking {
    val polled = discoveredCatalog().copy(lastLocalPollAtEpochMillis = 6L)
    val resolver = FakeNetworkResolver(NETWORK)
    val status = FakeLocalStatusCoordinator(polled)
    val coordinator = DefaultKnownDeviceRefreshCoordinator(resolver, status)

    val outcome = coordinator.refresh(discoveredCatalog())

    assertEquals(NETWORK, outcome.network)
    assertEquals(6L, outcome.catalog.lastLocalPollAtEpochMillis)
    assertEquals(1, resolver.callCount)
    assertEquals(1, status.callCount)
    assertEquals("192.168.10.42", status.catalog?.lanDevices?.single()?.ip)
  }

  @Test
  fun differentNetworkHandleRequiresDiscoveryBeforeAnyPoll() = runBlocking {
    val resolver = FakeNetworkResolver(NETWORK.copy(networkHandle = 202L))
    val status = FakeLocalStatusCoordinator()
    val coordinator = DefaultKnownDeviceRefreshCoordinator(resolver, status)

    val error = runCatching { coordinator.refresh(discoveredCatalog()) }.exceptionOrNull()

    assertTrue(error is LocalStatusException)
    assertEquals("LOCAL_REFRESH_DISCOVERY_REQUIRED", (error as LocalStatusException).code)
    assertEquals(1, resolver.callCount)
    assertEquals(0, status.callCount)
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
    val coordinator = DefaultKnownDeviceRefreshCoordinator(resolver, status)

    val error = runCatching { coordinator.refresh(stale) }.exceptionOrNull()

    assertTrue(stale.hasPreviouslyMatchedStatusTargets())
    assertFalse(stale.hasCurrentKnownStatusTargets())
    assertEquals("LOCAL_REFRESH_DISCOVERY_REQUIRED", (error as LocalStatusException).code)
    assertEquals(0, resolver.callCount)
    assertEquals(0, status.callCount)
  }

  private class FakeNetworkResolver(private val network: LanNetworkContext) : LanNetworkResolver {
    var callCount = 0

    override suspend fun resolve(): LanNetworkContext {
      callCount += 1
      return network
    }
  }

  private class FakeLocalStatusCoordinator(private val result: DeviceCatalog? = null) :
    LocalStatusCoordinator {
    var callCount = 0
    var catalog: DeviceCatalog? = null

    override suspend fun poll(
      catalog: DeviceCatalog,
      network: LanNetworkContext,
    ): DeviceCatalog {
      callCount += 1
      this.catalog = catalog
      return result ?: catalog
    }
  }

  private companion object {
    val NETWORK =
      LanNetworkContext(
        interfaceName = "wlan0",
        localIpv4 = "192.168.10.5",
        prefixLength = 24,
        broadcastIpv4 = "192.168.10.255",
        networkHandle = 101L,
      )

    fun discoveredCatalog() =
      DeviceCatalog(
        schemaVersion = 4,
        importedAtEpochMillis = 1L,
        region = TuyaCloudRegion.WESTERN_AMERICA,
        devices =
          listOf(
            CloudImportedDevice(
              id = "saved-device",
              name = "Saved switch",
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
          ),
        lastDiscoveryAtEpochMillis = 5L,
        lastDiscoveryNetwork = NETWORK,
        lanDevices =
          listOf(
            LanDeviceRecord(
              id = "saved-device",
              ip = "192.168.10.42",
              protocolVersion = "3.5",
              productKey = "",
              mac = "",
              origin = "broadcast",
              lastSeenAtEpochMillis = 5L,
            )
          ),
      )
  }
}
