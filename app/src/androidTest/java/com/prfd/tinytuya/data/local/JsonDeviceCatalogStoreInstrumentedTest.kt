package com.prfd.tinytuya.data.local

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prfd.tinytuya.data.lan.LanDiscoveredDevice
import com.prfd.tinytuya.data.lan.LanDiscoveryResult
import com.prfd.tinytuya.data.lan.LanNetworkContext
import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.lan.LocalPollDeviceState
import com.prfd.tinytuya.data.lan.LocalPollResult
import com.prfd.tinytuya.data.lan.LocalPolledDevice
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsonDeviceCatalogStoreInstrumentedTest {
  private lateinit var catalogDirectory: File
  private lateinit var store: JsonDeviceCatalogStore

  @Before
  fun setUp() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val testId = UUID.randomUUID().toString()
    catalogDirectory = File(context.noBackupFilesDir, "catalog-test-$testId")
    store =
      JsonDeviceCatalogStore(
        context = context,
        catalogDirectory = catalogDirectory,
        currentTimeMillis = { FIXED_IMPORT_TIME },
      )
  }

  @After fun tearDown() = runBlocking { store.deleteAll() }

  @Test
  fun roundTripKeepsAllDeviceData() = runBlocking {
    val result = sampleImport()

    val saved = store.replaceFromCloud(result)
    val loaded = store.load()

    assertEquals(FIXED_IMPORT_TIME, saved.importedAtEpochMillis)
    assertTrue(
      store.catalogFile.canonicalPath.startsWith(
        InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir.canonicalPath
      )
    )
    assertTrue(store.catalogFile.readText().startsWith("{"))
    assertNotNull(loaded)
    assertEquals(TuyaCloudRegion.CENTRAL_EUROPE, loaded?.region)
    assertEquals(1, loaded?.devices?.size)
    assertEquals(DEVICE_ID, loaded?.devices?.single()?.id)
    assertEquals(LOCAL_KEY, loaded?.devices?.single()?.localKey?.reveal())
    assertEquals("3.5", loaded?.devices?.single()?.protocolVersion)
  }

  @Test
  fun corruptedJsonIsReportedAsInvalid() = runBlocking {
    store.replaceFromCloud(sampleImport())
    val bytes = store.catalogFile.readBytes()
    bytes[0] = 'x'.code.toByte()
    store.catalogFile.writeBytes(bytes)

    try {
      store.load()
      throw AssertionError("Expected corrupted JSON to be rejected")
    } catch (error: DeviceCatalogStorageException) {
      assertEquals("CATALOG_INVALID", error.code)
    }
  }

  @Test
  fun rebindDiscoveryNetworkReplacesOnlyTheNetworkIdentity() = runBlocking {
    store.replaceFromCloud(sampleImport())
    val discovered = store.mergeLanDiscovery(sampleDiscovery(), NETWORK)
    val polled = store.mergeLocalPoll(sampleLocalPoll())

    val rebound = store.rebindDiscoveryNetwork(REBOUND_NETWORK)
    val loaded = store.load()

    assertEquals(REBOUND_NETWORK, rebound.lastDiscoveryNetwork)
    assertEquals(REBOUND_NETWORK, loaded?.lastDiscoveryNetwork)
    assertEquals(discovered.lastDiscoveryAtEpochMillis, rebound.lastDiscoveryAtEpochMillis)
    assertEquals(discovered.lanDevices, rebound.lanDevices)
    assertEquals(polled.lastLocalPollAtEpochMillis, rebound.lastLocalPollAtEpochMillis)
    assertEquals(polled.localStatus, rebound.localStatus)
    // SensitiveString compares by identity, so compare device identity plus the revealed key.
    assertEquals(
      polled.devices.map { Triple(it.id, it.name, it.localKey.reveal()) },
      rebound.devices.map { Triple(it.id, it.name, it.localKey.reveal()) },
    )
  }

  @Test
  fun rebindDiscoveryNetworkRequiresADiscoveredCatalog() = runBlocking {
    store.replaceFromCloud(sampleImport())

    try {
      store.rebindDiscoveryNetwork(REBOUND_NETWORK)
      throw AssertionError("Expected rebinding without a discovery generation to be rejected")
    } catch (error: DeviceCatalogStorageException) {
      assertEquals("CATALOG_INVALID", error.code)
    }
    assertNull(store.load()?.lastDiscoveryNetwork)
  }

  @Test
  fun lanDiscoveryRecordsAreMergedAndPersisted() = runBlocking {
    store.replaceFromCloud(sampleImport())

    val merged = store.mergeLanDiscovery(sampleDiscovery(), NETWORK)
    val loaded = store.load()

    assertEquals(4, merged.schemaVersion)
    assertEquals(FIXED_IMPORT_TIME, merged.lastDiscoveryAtEpochMillis)
    assertEquals(NETWORK, merged.lastDiscoveryNetwork)
    assertEquals(2, merged.lanDevices.size)
    assertEquals(LAN_IP, loaded?.lanDevices?.first { it.id == DEVICE_ID }?.ip)
    assertEquals(NETWORK, loaded?.lastDiscoveryNetwork)
    assertEquals(
      UNMATCHED_DEVICE_ID,
      loaded?.lanDevices?.first { it.id == UNMATCHED_DEVICE_ID }?.id,
    )
  }

  @Test
  fun localStatusIsMergedAndPersisted() = runBlocking {
    store.replaceFromCloud(sampleImport())
    store.mergeLanDiscovery(sampleDiscovery(), NETWORK)

    val merged = store.mergeLocalPoll(sampleLocalPoll())
    val loaded = store.load()

    assertEquals(4, merged.schemaVersion)
    assertEquals(FIXED_IMPORT_TIME, merged.lastLocalPollAtEpochMillis)
    assertEquals(LocalPollDeviceState.RESPONDED, merged.localStatus.single().state)
    assertEquals(
      SENSITIVE_DP_VALUE,
      loaded?.localStatus?.single()?.dataPoints?.single()?.value,
    )
  }

  @Test
  fun localStatusRejectsDeviceWhichWasNotFreshlyDiscovered() = runBlocking {
    store.replaceFromCloud(sampleImport())
    store.mergeLanDiscovery(sampleDiscovery(), NETWORK)
    val invalid =
      sampleLocalPoll()
        .copy(devices = listOf(sampleLocalPoll().devices.single().copy(id = UNMATCHED_DEVICE_ID)))

    try {
      store.mergeLocalPoll(invalid)
      throw AssertionError("Expected stale local status to be rejected")
    } catch (error: DeviceCatalogStorageException) {
      assertEquals("CATALOG_INVALID", error.code)
    }
  }

  @Test
  fun laterEmptyScanRetainsKnownLastSeenAndDropsStaleUnknownDevice() = runBlocking {
    store.replaceFromCloud(sampleImport())
    val first = store.mergeLanDiscovery(sampleDiscovery(), NETWORK)

    val second =
      store.mergeLanDiscovery(
        LanDiscoveryResult(
          contractVersion = 1,
          deviceCount = 0,
          matchedDeviceCount = 0,
          unmatchedDeviceCount = 0,
          durationMillis = 6_000L,
          warnings = listOf("NO_LAN_DEVICES"),
          devices = emptyList(),
        ),
        NETWORK,
      )

    assertEquals(first.lastDiscoveryAtEpochMillis?.plus(1), second.lastDiscoveryAtEpochMillis)
    assertEquals(NETWORK, second.lastDiscoveryNetwork)
    assertEquals(listOf(DEVICE_ID), second.lanDevices.map { it.id })
    assertTrue(
      second.lanDevices.single().lastSeenAtEpochMillis <
        requireNotNull(second.lastDiscoveryAtEpochMillis)
    )
  }

  @Test
  fun discoveryRejectsAnUnverifiableAndroidNetworkHandle() = runBlocking {
    store.replaceFromCloud(sampleImport())

    try {
      store.mergeLanDiscovery(
        sampleDiscovery(),
        NETWORK.copy(networkHandle = LanNetworkContext.UNKNOWN_NETWORK_HANDLE),
      )
      throw AssertionError("Expected an unknown Android network handle to be rejected")
    } catch (error: DeviceCatalogStorageException) {
      assertEquals("CATALOG_INVALID", error.code)
    }

    assertNull(store.load()?.lastDiscoveryAtEpochMillis)
    assertNull(store.load()?.lastDiscoveryNetwork)
  }

  @Test
  fun interruptedAtomicWriteRestoresLastCompleteCatalog() = runBlocking {
    store.replaceFromCloud(sampleImport())
    AtomicFile(store.catalogFile).startWrite().close()

    val loaded = store.load()

    assertEquals(DEVICE_ID, loaded?.devices?.single()?.id)
    assertEquals(LOCAL_KEY, loaded?.devices?.single()?.localKey?.reveal())
  }

  @Test
  fun deleteAllRemovesCatalogFile() = runBlocking {
    store.replaceFromCloud(sampleImport())
    assertTrue(store.catalogFile.exists())

    store.deleteAll()

    assertFalse(store.catalogFile.exists())
    assertNull(store.load())
  }

  @Test
  fun duplicateDeviceIdsAreRejectedBeforeWriting() = runBlocking {
    val first = sampleImport().devices.single()
    val duplicate =
      sampleImport()
        .copy(
          deviceCount = 2,
          devices = listOf(first, first.copy(name = "Duplicate")),
        )

    try {
      store.replaceFromCloud(duplicate)
      throw AssertionError("Expected duplicate device IDs to be rejected")
    } catch (error: DeviceCatalogStorageException) {
      assertEquals("CATALOG_INVALID", error.code)
    }
    assertFalse(store.catalogFile.exists())
  }

  private fun sampleImport() =
    CloudImportResult(
      contractVersion = 1,
      region = TuyaCloudRegion.CENTRAL_EUROPE,
      deviceCount = 1,
      missingLocalKeyCount = 0,
      warnings = emptyList(),
      devices =
        listOf(
          CloudImportedDevice(
            id = DEVICE_ID,
            name = DEVICE_NAME,
            localKey = SensitiveString.of(LOCAL_KEY),
            category = "dj",
            productId = "product-id",
            productName = "Table lamp",
            model = "TL-1",
            mac = "AA:BB:CC:DD:EE:FF",
            uuid = "device-uuid",
            isSubDevice = false,
            gatewayId = "",
            nodeId = "",
            protocolVersion = "3.5",
            lastIp = "192.0.2.14",
            mappingJson = "{\"1\":{\"code\":\"switch_led\"}}",
          )
        ),
    )

  private fun sampleDiscovery() =
    LanDiscoveryResult(
      contractVersion = 1,
      deviceCount = 2,
      matchedDeviceCount = 1,
      unmatchedDeviceCount = 1,
      durationMillis = 320L,
      warnings = listOf("UNMATCHED_LAN_DEVICES"),
      devices =
        listOf(
          LanDiscoveredDevice(
            id = DEVICE_ID,
            ip = LAN_IP,
            protocolVersion = "3.5",
            productKey = "product-id",
            mac = "AA:BB:CC:DD:EE:FF",
            origin = "broadcast",
          ),
          LanDiscoveredDevice(
            id = UNMATCHED_DEVICE_ID,
            ip = "192.0.2.15",
            protocolVersion = "3.3",
            productKey = "unknown-product",
            mac = "",
            origin = "broadcast",
          ),
        ),
    )

  private fun sampleLocalPoll() =
    LocalPollResult(
      contractVersion = 1,
      deviceCount = 1,
      respondedDeviceCount = 1,
      offlineDeviceCount = 0,
      errorDeviceCount = 0,
      durationMillis = 48L,
      warnings = emptyList(),
      devices =
        listOf(
          LocalPolledDevice(
            id = DEVICE_ID,
            state = LocalPollDeviceState.RESPONDED,
            errorCode = "",
            durationMillis = 48L,
            dataPoints =
              listOf(
                LocalDataPoint(
                  id = "1",
                  kind = LocalDataPointKind.STRING,
                  value = SENSITIVE_DP_VALUE,
                )
              ),
          )
        ),
    )

  private companion object {
    const val FIXED_IMPORT_TIME = 1_753_981_200_000L
    const val DEVICE_ID = "device-id"
    const val DEVICE_NAME = "Bedroom lamp"
    const val LOCAL_KEY = "private-local-key-value"
    const val LAN_IP = "192.0.2.14"
    const val UNMATCHED_DEVICE_ID = "unmatched-device-id"
    const val SENSITIVE_DP_VALUE = "local-status-value"
    val NETWORK =
      LanNetworkContext(
        interfaceName = "wlan0",
        localIpv4 = "192.0.2.2",
        prefixLength = 24,
        broadcastIpv4 = "192.0.2.255",
        networkHandle = 41L,
      )
    val REBOUND_NETWORK = NETWORK.copy(localIpv4 = "192.0.2.9", networkHandle = 97L)
  }
}
