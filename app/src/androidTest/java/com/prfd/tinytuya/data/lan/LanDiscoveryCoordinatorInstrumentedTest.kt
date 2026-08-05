package com.prfd.tinytuya.data.lan

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.python.CloudCredentials
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.PythonBridgeException
import com.prfd.tinytuya.data.python.PythonRuntimeHealth
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.data.python.TuyaPythonGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanDiscoveryCoordinatorInstrumentedTest {
  @Test
  fun successfulScanUsesMetadataOnlyAndAlwaysReleasesRadio() = runBlocking {
    val result = discoveryResult()
    val gateway = FakeGateway { result }
    val store = FakeStore()
    val radio = FakeRadio()
    val coordinator = coordinator(gateway, store, radio)

    val outcome = coordinator.discover(sampleCatalog())
    val catalog = outcome.catalog

    assertEquals("192.168.10.42", catalog.lanDevices.single().ip)
    assertEquals("192.168.10.5", outcome.network.localIpv4)
    assertEquals(12, gateway.request?.timeoutSeconds)
    assertEquals(listOf("known-device"), gateway.request?.knownDevices?.map { it.id })
    assertFalse(gateway.request.toString().contains(LOCAL_KEY))
    assertTrue(radio.acquired)
    assertTrue(radio.released)
  }

  @Test
  fun bridgeFailureIsNormalizedAndStillReleasesRadio() = runBlocking {
    val gateway = FakeGateway {
      throw PythonBridgeException(
        code = "LAN_PORT_UNAVAILABLE",
        message = "A Tuya discovery port is already in use.",
      )
    }
    val radio = FakeRadio()
    val coordinator = coordinator(gateway, FakeStore(), radio)

    try {
      coordinator.discover(sampleCatalog())
      throw AssertionError("Expected discovery to fail")
    } catch (error: LanDiscoveryException) {
      assertEquals("LAN_PORT_UNAVAILABLE", error.code)
    }
    assertTrue(radio.released)
  }

  private fun coordinator(
    gateway: TuyaPythonGateway,
    store: DeviceCatalogStore,
    radio: LanDiscoveryRadio,
  ) =
    DefaultLanDiscoveryCoordinator(
      gateway = gateway,
      catalogStore = store,
      networkResolver =
        object : LanNetworkResolver {
          override suspend fun resolve() =
            LanNetworkContext(
              interfaceName = "wlan0",
              localIpv4 = "192.168.10.5",
              prefixLength = 24,
              broadcastIpv4 = "192.168.10.255",
            )
        },
      radio = radio,
    )

  private class FakeGateway(
    private val discover: suspend (LanDiscoveryRequest) -> LanDiscoveryResult
  ) : TuyaPythonGateway {
    var request: LanDiscoveryRequest? = null

    override suspend fun health(): PythonRuntimeHealth = error("Not used")

    override suspend fun importCloud(
      credentials: CloudCredentials,
      previousDevices: List<CloudImportedDevice>,
    ): CloudImportResult = error("Not used")

    override suspend fun discoverLan(request: LanDiscoveryRequest): LanDiscoveryResult {
      this.request = request
      return discover(request)
    }

    override suspend fun pollLocal(request: LocalPollRequest): LocalPollResult = error("Not used")

    override suspend fun setLocalValues(request: LocalControlRequest): LocalControlResult =
      error("Not used")
  }

  private class FakeStore : DeviceCatalogStore {
    override suspend fun load(): DeviceCatalog? = error("Not used")

    override suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog =
      error("Not used")

    override suspend fun mergeLanDiscovery(
      result: LanDiscoveryResult,
      network: LanNetworkContext,
    ): DeviceCatalog =
      sampleCatalog()
        .copy(
          schemaVersion = 2,
          lastDiscoveryAtEpochMillis = 10L,
          lastDiscoveryNetwork = network,
          lanDevices =
            result.devices.map { device ->
              LanDeviceRecord(
                id = device.id,
                ip = device.ip,
                protocolVersion = device.protocolVersion,
                productKey = device.productKey,
                mac = device.mac,
                origin = device.origin,
                lastSeenAtEpochMillis = 10L,
              )
            },
        )

    override suspend fun mergeLocalPoll(result: LocalPollResult): DeviceCatalog = error("Not used")

    override suspend fun deleteAll() = Unit
  }

  private class FakeRadio : LanDiscoveryRadio {
    var acquired = false
    var released = false

    override fun acquire() {
      acquired = true
    }

    override fun release() {
      released = true
    }
  }

  private companion object {
    const val LOCAL_KEY = "coordinator-local-key-must-not-cross-discovery-boundary"

    fun sampleCatalog() =
      DeviceCatalog(
        schemaVersion = 1,
        importedAtEpochMillis = 1L,
        region = TuyaCloudRegion.WESTERN_AMERICA,
        devices =
          listOf(
            CloudImportedDevice(
              id = "known-device",
              name = "Known switch",
              localKey = SensitiveString.of(LOCAL_KEY),
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
              mappingJson = "{}",
            )
          ),
      )

    fun discoveryResult() =
      LanDiscoveryResult(
        contractVersion = 1,
        deviceCount = 1,
        matchedDeviceCount = 1,
        unmatchedDeviceCount = 0,
        durationMillis = 120L,
        warnings = emptyList(),
        devices =
          listOf(
            LanDiscoveredDevice(
              id = "known-device",
              ip = "192.168.10.42",
              protocolVersion = "3.5",
              productKey = "product-key",
              mac = "",
              origin = "broadcast",
            )
          ),
      )
  }
}
