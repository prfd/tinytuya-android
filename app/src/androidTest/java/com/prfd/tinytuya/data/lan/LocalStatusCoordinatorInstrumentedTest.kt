package com.prfd.tinytuya.data.lan

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.local.LocalStatusRecord
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStatusCoordinatorInstrumentedTest {
  @Test
  fun pollUsesOnlyFreshEligibleDirectDeviceAndRedactsItsKey() = runBlocking {
    val catalog = sampleCatalog()
    val gateway = FakeGateway { samplePollResult() }
    val store = FakeStore(catalog)
    val coordinator = DefaultLocalStatusCoordinator(gateway, store)

    val updated = coordinator.poll(catalog, NETWORK)

    assertEquals(listOf("fresh-device"), gateway.request?.devices?.map { it.id })
    assertEquals("192.168.10.42", gateway.request?.devices?.single()?.ip)
    assertFalse(gateway.request.toString().contains(LOCAL_KEY))
    assertEquals(LocalPollDeviceState.RESPONDED, updated.localStatus.single().state)
  }

  @Test
  fun bridgeFailureIsNormalizedWithoutPersistingAResult() = runBlocking {
    val catalog = sampleCatalog()
    val gateway = FakeGateway {
      throw PythonBridgeException(
        code = "LOCAL_POLL_FAILED",
        message = "Local device status could not be read.",
      )
    }
    val store = FakeStore(catalog)
    val coordinator = DefaultLocalStatusCoordinator(gateway, store)

    try {
      coordinator.poll(catalog, NETWORK)
      throw AssertionError("Expected local polling to fail")
    } catch (error: LocalStatusException) {
      assertEquals("LOCAL_POLL_FAILED", error.code)
    }
    assertEquals(0, store.mergeCount)
  }

  @Test
  fun refreshWithOnlyProtectedDevicesDoesNotCallThePythonBridge() = runBlocking {
    val catalog =
      sampleCatalog().let { source ->
        val protectedIds = setOf("gateway", "camera", "lock", "sub-device")
        source.copy(
          devices = source.devices.filter { device -> device.id in protectedIds },
          lanDevices = source.lanDevices.filter { device -> device.id in protectedIds },
        )
      }
    val gateway = FakeGateway { error("Protected devices must not reach Python") }
    val store = FakeStore(catalog)
    val coordinator = DefaultLocalStatusCoordinator(gateway, store)

    val unchanged = coordinator.poll(catalog, NETWORK)

    assertEquals(catalog, unchanged)
    assertEquals(null, gateway.request)
    assertEquals(0, store.mergeCount)
  }

  private class FakeGateway(private val poll: suspend (LocalPollRequest) -> LocalPollResult) :
    TuyaPythonGateway {
    var request: LocalPollRequest? = null

    override suspend fun health(): PythonRuntimeHealth = error("Not used")

    override suspend fun importCloud(
      credentials: CloudCredentials,
      previousDevices: List<CloudImportedDevice>,
    ): CloudImportResult = error("Not used")

    override suspend fun discoverLan(request: LanDiscoveryRequest): LanDiscoveryResult =
      error("Not used")

    override suspend fun pollLocal(request: LocalPollRequest): LocalPollResult {
      this.request = request
      return poll(request)
    }

    override suspend fun setLocalValues(request: LocalControlRequest): LocalControlResult =
      error("Not used")
  }

  private class FakeStore(private val catalog: DeviceCatalog) : DeviceCatalogStore {
    var mergeCount = 0

    override suspend fun load(): DeviceCatalog? = error("Not used")

    override suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog =
      error("Not used")

    override suspend fun mergeLanDiscovery(
      result: LanDiscoveryResult,
      network: LanNetworkContext,
    ): DeviceCatalog = error("Not used")

    override suspend fun mergeLocalPoll(result: LocalPollResult): DeviceCatalog {
      mergeCount += 1
      return catalog.copy(
        schemaVersion = 3,
        lastLocalPollAtEpochMillis = 11L,
        localStatus =
          result.devices.map { device ->
            LocalStatusRecord(
              id = device.id,
              state = device.state,
              errorCode = device.errorCode,
              durationMillis = device.durationMillis,
              dataPoints = device.dataPoints,
              polledAtEpochMillis = 11L,
            )
          },
      )
    }

    override suspend fun deleteAll() = Unit
  }

  private companion object {
    const val LOCAL_KEY = "0123456789abcdef"
    val NETWORK =
      LanNetworkContext(
        interfaceName = "wlan0",
        localIpv4 = "192.168.10.5",
        prefixLength = 24,
        broadcastIpv4 = "192.168.10.255",
      )

    fun sampleCatalog(): DeviceCatalog =
      DeviceCatalog(
        schemaVersion = 2,
        importedAtEpochMillis = 1L,
        region = TuyaCloudRegion.WESTERN_AMERICA,
        devices =
          listOf(
            cloudDevice(id = "fresh-device"),
            cloudDevice(id = "stale-device"),
            cloudDevice(id = "sub-device", isSubDevice = true),
            cloudDevice(id = "gateway", category = "wg2"),
            cloudDevice(id = "camera", category = "sp"),
            cloudDevice(id = "lock", category = "ms"),
          ),
        lastDiscoveryAtEpochMillis = 10L,
        lanDevices =
          listOf(
            lanDevice(id = "fresh-device", lastSeenAt = 10L, ip = "192.168.10.42"),
            lanDevice(id = "stale-device", lastSeenAt = 9L, ip = "192.168.10.43"),
            lanDevice(id = "sub-device", lastSeenAt = 10L, ip = "192.168.10.44"),
            lanDevice(id = "gateway", lastSeenAt = 10L, ip = "192.168.10.45"),
            lanDevice(id = "camera", lastSeenAt = 10L, ip = "192.168.10.46"),
            lanDevice(id = "lock", lastSeenAt = 10L, ip = "192.168.10.47"),
          ),
      )

    fun cloudDevice(
      id: String,
      isSubDevice: Boolean = false,
      category: String = "kg",
    ) =
      CloudImportedDevice(
        id = id,
        name = id,
        localKey = SensitiveString.of(LOCAL_KEY),
        category = category,
        productId = "",
        productName = "Switch",
        model = "",
        mac = "",
        uuid = "",
        isSubDevice = isSubDevice,
        gatewayId = "",
        nodeId = "",
        protocolVersion = "3.5",
        lastIp = "",
        mappingJson = "{}",
      )

    fun lanDevice(id: String, lastSeenAt: Long, ip: String) =
      LanDeviceRecord(
        id = id,
        ip = ip,
        protocolVersion = "3.5",
        productKey = "",
        mac = "",
        origin = "broadcast",
        lastSeenAtEpochMillis = lastSeenAt,
      )

    fun samplePollResult() =
      LocalPollResult(
        contractVersion = 1,
        deviceCount = 1,
        respondedDeviceCount = 1,
        offlineDeviceCount = 0,
        errorDeviceCount = 0,
        durationMillis = 25L,
        warnings = emptyList(),
        devices =
          listOf(
            LocalPolledDevice(
              id = "fresh-device",
              state = LocalPollDeviceState.RESPONDED,
              errorCode = "",
              durationMillis = 25L,
              dataPoints = listOf(LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")),
            )
          ),
      )
  }
}
