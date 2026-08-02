package com.prfd.tinytuya.data.lan

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.local.LocalStatusRecord
import com.prfd.tinytuya.data.python.CloudCredentials
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.PythonRuntimeHealth
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.data.python.TuyaPythonGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalControlCoordinatorInstrumentedTest {
    @Test
    fun confirmedBooleanWriteUsesVerifiedFreshTargetAndPersistsReadBack() = runBlocking {
        val catalog = sampleCatalog()
        val gateway = FakeGateway { confirmedResult(false) }
        val store = FakeStore(catalog)
        val coordinator = DefaultLocalControlCoordinator(
            gateway = gateway,
            catalogStore = store,
            networkResolver = FakeNetworkResolver(NETWORK),
        )

        val updated = coordinator.setBoolean(
            catalog = catalog,
            expectedNetwork = NETWORK,
            deviceId = DEVICE_ID,
            dataPointId = "1",
            value = false,
        )

        assertNotNull(gateway.request)
        val request = requireNotNull(gateway.request)
        assertEquals("192.168.10.42", request.device.ip)
        assertEquals("false", request.changes.single().value)
        assertFalse(request.toString().contains(LOCAL_KEY))
        assertFalse(request.toString().contains("192.168.10.42"))
        assertEquals("false", updated.localStatus.single().dataPoints.single().value)
        assertEquals(1, store.mergeCount)
    }

    @Test
    fun changedWifiIsRejectedBeforeThePythonBridge() = runBlocking {
        val catalog = sampleCatalog()
        val gateway = FakeGateway { confirmedResult(false) }
        val coordinator = DefaultLocalControlCoordinator(
            gateway = gateway,
            catalogStore = FakeStore(catalog),
            networkResolver = FakeNetworkResolver(
                NETWORK.copy(localIpv4 = "192.168.10.6")
            ),
        )

        try {
            coordinator.setBoolean(catalog, NETWORK, DEVICE_ID, "1", false)
            throw AssertionError("Expected changed Wi-Fi to reject local control")
        } catch (error: LocalControlException) {
            assertEquals("LOCAL_CONTROL_NETWORK_CHANGED", error.code)
        }
        assertEquals(0, gateway.callCount)
    }

    @Test
    fun differentAndroidNetworkHandleIsRejectedEvenWhenSubnetIsIdentical() = runBlocking {
        val catalog = sampleCatalog()
        val gateway = FakeGateway { confirmedResult(false) }
        val coordinator = DefaultLocalControlCoordinator(
            gateway = gateway,
            catalogStore = FakeStore(catalog),
            networkResolver = FakeNetworkResolver(
                NETWORK.copy(networkHandle = NETWORK.networkHandle + 1)
            ),
        )

        try {
            coordinator.setBoolean(catalog, NETWORK, DEVICE_ID, "1", false)
            throw AssertionError("Expected a different Android network to reject local control")
        } catch (error: LocalControlException) {
            assertEquals("LOCAL_CONTROL_NETWORK_CHANGED", error.code)
        }
        assertEquals(0, gateway.callCount)
    }

    @Test
    fun unverifiedBooleanMappingCannotAuthorizeAWrite() = runBlocking {
        val catalog = sampleCatalog(
            mappingJson = "{\"1\":{\"code\":\"countdown_1\",\"type\":\"Boolean\"}}"
        )
        val gateway = FakeGateway { confirmedResult(false) }
        val coordinator = DefaultLocalControlCoordinator(
            gateway = gateway,
            catalogStore = FakeStore(catalog),
            networkResolver = FakeNetworkResolver(NETWORK),
        )

        try {
            coordinator.setBoolean(catalog, NETWORK, DEVICE_ID, "1", false)
            throw AssertionError("Expected an unverified mapping to reject local control")
        } catch (error: LocalControlException) {
            assertEquals("LOCAL_CONTROL_UNSUPPORTED", error.code)
        }
        assertEquals(0, gateway.callCount)
    }

    @Test
    fun protectedCameraMappingCannotAuthorizeAWrite() = runBlocking {
        val catalog = sampleCatalog(category = "sp")
        val gateway = FakeGateway { confirmedResult(false) }
        val coordinator = DefaultLocalControlCoordinator(
            gateway = gateway,
            catalogStore = FakeStore(catalog),
            networkResolver = FakeNetworkResolver(NETWORK),
        )

        try {
            coordinator.setBoolean(catalog, NETWORK, DEVICE_ID, "1", false)
            throw AssertionError("Expected a camera mapping to reject local control")
        } catch (error: LocalControlException) {
            assertEquals("LOCAL_CONTROL_UNSUPPORTED", error.code)
        }
        assertEquals(0, gateway.callCount)
    }

    @Test
    fun rejectedWritePersistsObservedRollbackAndReturnsSafeError() = runBlocking {
        val catalog = sampleCatalog()
        val gateway = FakeGateway {
            LocalControlResult(
                contractVersion = 1,
                id = DEVICE_ID,
                state = LocalControlState.REJECTED,
                errorCode = "LOCAL_CONTROL_NOT_APPLIED",
                durationMillis = 40L,
                dataPoints = listOf(
                    LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")
                ),
            )
        }
        val coordinator = DefaultLocalControlCoordinator(
            gateway = gateway,
            catalogStore = FakeStore(catalog),
            networkResolver = FakeNetworkResolver(NETWORK),
        )

        try {
            coordinator.setBoolean(catalog, NETWORK, DEVICE_ID, "1", false)
            throw AssertionError("Expected the unconfirmed change to be rejected")
        } catch (error: LocalControlException) {
            assertEquals("LOCAL_CONTROL_NOT_APPLIED", error.code)
            assertEquals(
                "true",
                error.updatedCatalog?.localStatus?.single()?.dataPoints?.single()?.value,
            )
        }
    }

    private class FakeNetworkResolver(
        private val network: LanNetworkContext,
    ) : LanNetworkResolver {
        override suspend fun resolve(): LanNetworkContext = network
    }

    private class FakeGateway(
        private val response: suspend (LocalControlRequest) -> LocalControlResult,
    ) : TuyaPythonGateway {
        var request: LocalControlRequest? = null
        var callCount = 0

        override suspend fun health(): PythonRuntimeHealth = error("Not used")

        override suspend fun importCloud(
            credentials: CloudCredentials,
            previousDevices: List<CloudImportedDevice>,
        ): CloudImportResult = error("Not used")

        override suspend fun discoverLan(request: LanDiscoveryRequest): LanDiscoveryResult =
            error("Not used")

        override suspend fun pollLocal(request: LocalPollRequest): LocalPollResult =
            error("Not used")

        override suspend fun setLocalValues(request: LocalControlRequest): LocalControlResult {
            callCount += 1
            this.request = request
            return response(request)
        }
    }

    private class FakeStore(
        private var catalog: DeviceCatalog,
    ) : DeviceCatalogStore {
        var mergeCount = 0

        override suspend fun load(): DeviceCatalog? = catalog

        override suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog =
            error("Not used")

        override suspend fun mergeLanDiscovery(
            result: LanDiscoveryResult,
            network: LanNetworkContext,
        ): DeviceCatalog =
            error("Not used")

        override suspend fun mergeLocalPoll(result: LocalPollResult): DeviceCatalog {
            mergeCount += 1
            val records = result.devices.map { device ->
                LocalStatusRecord(
                    id = device.id,
                    state = device.state,
                    errorCode = device.errorCode,
                    durationMillis = device.durationMillis,
                    dataPoints = device.dataPoints,
                    polledAtEpochMillis = 11L,
                )
            }
            catalog = catalog.copy(
                lastLocalPollAtEpochMillis = 11L,
                localStatus = records,
            )
            return catalog
        }

        override suspend fun deleteAll() = Unit
    }

    private companion object {
        const val DEVICE_ID = "verified-switch"
        const val LOCAL_KEY = "0123456789abcdef"
        val NETWORK = LanNetworkContext(
            interfaceName = "wlan0",
            localIpv4 = "192.168.10.5",
            prefixLength = 24,
            broadcastIpv4 = "192.168.10.255",
            networkHandle = 201L,
        )

        fun sampleCatalog(
            mappingJson: String =
                "{\"1\":{\"code\":\"switch_1\",\"type\":\"Boolean\"}}",
            category: String = "kg",
        ) = DeviceCatalog(
            schemaVersion = 3,
            importedAtEpochMillis = 1L,
            region = TuyaCloudRegion.WESTERN_AMERICA,
            devices = listOf(
                CloudImportedDevice(
                    id = DEVICE_ID,
                    name = "Verified switch",
                    localKey = SensitiveString.of(LOCAL_KEY),
                    category = category,
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
                    mappingJson = mappingJson,
                )
            ),
            lastDiscoveryAtEpochMillis = 10L,
            lanDevices = listOf(
                LanDeviceRecord(
                    id = DEVICE_ID,
                    ip = "192.168.10.42",
                    protocolVersion = "3.5",
                    productKey = "",
                    mac = "",
                    origin = "broadcast",
                    lastSeenAtEpochMillis = 10L,
                )
            ),
            lastLocalPollAtEpochMillis = 10L,
            localStatus = listOf(
                LocalStatusRecord(
                    id = DEVICE_ID,
                    state = LocalPollDeviceState.RESPONDED,
                    errorCode = "",
                    durationMillis = 20L,
                    dataPoints = listOf(
                        LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")
                    ),
                    polledAtEpochMillis = 10L,
                )
            ),
        )

        fun confirmedResult(value: Boolean) = LocalControlResult(
            contractVersion = 1,
            id = DEVICE_ID,
            state = LocalControlState.CONFIRMED,
            errorCode = "",
            durationMillis = 35L,
            dataPoints = listOf(
                LocalDataPoint("1", LocalDataPointKind.BOOLEAN, value.toString())
            ),
        )
    }
}
