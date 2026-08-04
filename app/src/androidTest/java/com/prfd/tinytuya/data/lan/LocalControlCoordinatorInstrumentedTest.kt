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
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.core.capability.TuyaHsvColor
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

        val updated = coordinator.execute(
            expectedNetwork = NETWORK,
            intent = toggleIntent(false),
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
    fun verifiedLightWritesUseTypedValuesAndTheObservedHsvEncoding() = runBlocking {
        val catalog = lightCatalog()
        val gateway = FakeGateway { request -> confirmedResult(request.changes.single()) }
        listOf<DeviceIntent>(
            DeviceIntent.SetChoice(DEVICE_ID, CapabilityId("light.mode"), "colour"),
            DeviceIntent.SetRange(DEVICE_ID, CapabilityId("light.brightness"), 500),
            DeviceIntent.SetRange(DEVICE_ID, CapabilityId("light.temperature"), 400),
            DeviceIntent.SetColor(
                DEVICE_ID,
                CapabilityId("light.color"),
                TuyaHsvColor(hue = 300, saturation = 700, brightness = 500),
            ),
        ).forEach { intent ->
            DefaultLocalControlCoordinator(
                gateway = gateway,
                catalogStore = FakeStore(catalog),
                networkResolver = FakeNetworkResolver(NETWORK),
            ).execute(NETWORK, intent)
        }

        assertEquals(
            listOf(
                LocalControlChange("21", LocalDataPointKind.STRING, "colour"),
                LocalControlChange("22", LocalDataPointKind.INTEGER, "500"),
                LocalControlChange("23", LocalDataPointKind.INTEGER, "400"),
                LocalControlChange("24", LocalDataPointKind.STRING, "012c02bc01f4"),
            ),
            gateway.requests.map { request -> request.changes.single() },
        )
    }

    @Test
    fun verifiedCoverActionsAndPositionUseTheGenericConfirmedWritePath() = runBlocking {
        val catalog = coverCatalog()
        val gateway = FakeGateway { request -> confirmedResult(request.changes.single()) }
        listOf<DeviceIntent>(
            DeviceIntent.InvokeAction(DEVICE_ID, CapabilityId("cover.actions"), "up"),
            DeviceIntent.SetRange(DEVICE_ID, CapabilityId("cover.position"), 75),
        ).forEach { intent ->
            DefaultLocalControlCoordinator(
                gateway = gateway,
                catalogStore = FakeStore(catalog),
                networkResolver = FakeNetworkResolver(NETWORK),
            ).execute(NETWORK, intent)
        }

        assertEquals(
            listOf(
                LocalControlChange("7", LocalDataPointKind.STRING, "up"),
                LocalControlChange("8", LocalDataPointKind.INTEGER, "75"),
            ),
            gateway.requests.map { request -> request.changes.single() },
        )
    }

    @Test
    fun unknownOrStaleCoverVocabularyCannotReachThePythonBridge() = runBlocking {
        val unknownVocabulary = coverCatalog().copy(
            devices = listOf(
                coverCatalog().devices.single().copy(
                    mappingJson = """
                        {
                          "7":{"code":"control_2","type":"Enum","values":{"range":["raise","pause","lower"]}}
                        }
                    """.trimIndent(),
                )
            ),
            localStatus = listOf(
                coverCatalog().localStatus.single().copy(
                    dataPoints = listOf(LocalDataPoint("7", LocalDataPointKind.STRING, "pause"))
                )
            ),
        )
        val stale = coverCatalog().copy(
            localStatus = listOf(coverCatalog().localStatus.single().copy(polledAtEpochMillis = 9L))
        )

        listOf(
            unknownVocabulary to DeviceIntent.InvokeAction(
                DEVICE_ID,
                CapabilityId("cover.actions"),
                "raise",
            ),
            stale to DeviceIntent.InvokeAction(
                DEVICE_ID,
                CapabilityId("cover.actions"),
                "up",
            ),
        ).forEach { (catalog, intent) ->
            val gateway = FakeGateway { request -> confirmedResult(request.changes.single()) }
            val coordinator = DefaultLocalControlCoordinator(
                gateway = gateway,
                catalogStore = FakeStore(catalog),
                networkResolver = FakeNetworkResolver(NETWORK),
            )
            try {
                coordinator.execute(NETWORK, intent)
                throw AssertionError("Expected unverified cover control to be rejected")
            } catch (error: LocalControlException) {
                assertEquals("LOCAL_CONTROL_UNSUPPORTED", error.code)
            }
            assertEquals(0, gateway.callCount)
        }
    }

    @Test
    fun invalidLightValueIsRejectedBeforeThePythonBridge() = runBlocking {
        val catalog = lightCatalog()
        val gateway = FakeGateway { request -> confirmedResult(request.changes.single()) }
        val coordinator = DefaultLocalControlCoordinator(
            gateway = gateway,
            catalogStore = FakeStore(catalog),
            networkResolver = FakeNetworkResolver(NETWORK),
        )

        try {
            coordinator.execute(
                NETWORK,
                DeviceIntent.SetColor(
                    deviceId = DEVICE_ID,
                    capabilityId = CapabilityId("light.color"),
                    color = TuyaHsvColor(hue = 361, saturation = 700, brightness = 500),
                ),
            )
            throw AssertionError("Expected an invalid light value to be rejected")
        } catch (error: LocalControlException) {
            assertEquals("LOCAL_CONTROL_UNSUPPORTED", error.code)
        }
        assertEquals(0, gateway.callCount)
    }

    @Test
    fun staleLightStatusCannotAuthorizeAWrite() = runBlocking {
        val catalog = lightCatalog().copy(
            localStatus = listOf(
                lightCatalog().localStatus.single().copy(polledAtEpochMillis = 9L)
            )
        )
        val gateway = FakeGateway { request -> confirmedResult(request.changes.single()) }
        val coordinator = DefaultLocalControlCoordinator(
            gateway = gateway,
            catalogStore = FakeStore(catalog),
            networkResolver = FakeNetworkResolver(NETWORK),
        )

        try {
            coordinator.execute(
                NETWORK,
                DeviceIntent.SetChoice(DEVICE_ID, CapabilityId("light.mode"), "colour"),
            )
            throw AssertionError("Expected stale light status to be rejected")
        } catch (error: LocalControlException) {
            assertEquals("LOCAL_CONTROL_UNSUPPORTED", error.code)
        }
        assertEquals(0, gateway.callCount)
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
            coordinator.execute(NETWORK, toggleIntent(false))
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
            coordinator.execute(NETWORK, toggleIntent(false))
            throw AssertionError("Expected a different Android network to reject local control")
        } catch (error: LocalControlException) {
            assertEquals("LOCAL_CONTROL_NETWORK_CHANGED", error.code)
        }
        assertEquals(0, gateway.callCount)
    }

    @Test
    fun changedDiscoveryGenerationRejectsAQueuedIntent() = runBlocking {
        val original = sampleCatalog()
        val rediscovered = original.copy(
            lastDiscoveryAtEpochMillis = 11L,
            lanDevices = listOf(
                original.lanDevices.single().copy(lastSeenAtEpochMillis = 11L)
            ),
            localStatus = listOf(
                original.localStatus.single().copy(polledAtEpochMillis = 11L)
            ),
        )
        val gateway = FakeGateway { confirmedResult(false) }
        val coordinator = DefaultLocalControlCoordinator(
            gateway = gateway,
            catalogStore = FakeStore(rediscovered),
            networkResolver = FakeNetworkResolver(NETWORK),
        )

        try {
            coordinator.execute(NETWORK, toggleIntent(false))
            throw AssertionError("Expected a new discovery generation to invalidate the intent")
        } catch (error: LocalControlException) {
            assertEquals("LOCAL_CONTROL_REFRESH_REQUIRED", error.code)
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
            coordinator.execute(NETWORK, toggleIntent(false))
            throw AssertionError("Expected an unverified mapping to reject local control")
        } catch (error: LocalControlException) {
            assertEquals("LOCAL_CONTROL_UNSUPPORTED", error.code)
        }
        assertEquals(0, gateway.callCount)
    }

    @Test
    fun forgedCapabilityIdCannotReachThePythonBridge() = runBlocking {
        val catalog = sampleCatalog()
        val gateway = FakeGateway { confirmedResult(false) }
        val coordinator = DefaultLocalControlCoordinator(
            gateway = gateway,
            catalogStore = FakeStore(catalog),
            networkResolver = FakeNetworkResolver(NETWORK),
        )

        try {
            coordinator.execute(
                NETWORK,
                DeviceIntent.SetToggle(DEVICE_ID, CapabilityId("forged.power"), false),
            )
            throw AssertionError("Expected a forged capability ID to be rejected")
        } catch (error: LocalControlException) {
            assertEquals("LOCAL_CONTROL_UNSUPPORTED", error.code)
        }
        assertEquals(0, gateway.callCount)
    }

    @Test
    fun semanticIntentRebindsAgainstTheLatestPersistedDps() = runBlocking {
        val latest = sampleCatalog().copy(
            devices = listOf(
                sampleCatalog().devices.single().copy(
                    mappingJson = "{\"9\":{\"code\":\"switch_1\",\"type\":\"Boolean\"}}"
                )
            ),
            localStatus = listOf(
                sampleCatalog().localStatus.single().copy(
                    dataPoints = listOf(
                        LocalDataPoint("9", LocalDataPointKind.BOOLEAN, "true")
                    )
                )
            ),
        )
        val gateway = FakeGateway { request -> confirmedResult(request.changes.single()) }
        val coordinator = DefaultLocalControlCoordinator(
            gateway = gateway,
            catalogStore = FakeStore(latest),
            networkResolver = FakeNetworkResolver(NETWORK),
        )

        coordinator.execute(NETWORK, toggleIntent(false))

        assertEquals("9", gateway.request?.changes?.single()?.id)
    }

    @Test
    fun mappingChangedAfterIntentCreationCannotReachThePythonBridge() = runBlocking {
        val intentFromOldUi = toggleIntent(false)
        val latest = sampleCatalog(
            mappingJson = "{\"1\":{\"code\":\"countdown_1\",\"type\":\"Boolean\"}}"
        )
        val gateway = FakeGateway { confirmedResult(false) }
        val coordinator = DefaultLocalControlCoordinator(
            gateway = gateway,
            catalogStore = FakeStore(latest),
            networkResolver = FakeNetworkResolver(NETWORK),
        )

        try {
            coordinator.execute(NETWORK, intentFromOldUi)
            throw AssertionError("Expected the changed mapping to invalidate the old intent")
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
            coordinator.execute(NETWORK, toggleIntent(false))
            throw AssertionError("Expected the unconfirmed change to be rejected")
        } catch (error: LocalControlException) {
            assertEquals("LOCAL_CONTROL_NOT_APPLIED", error.code)
            assertEquals(
                "true",
                error.updatedCatalog?.localStatus?.single()?.dataPoints?.single()?.value,
            )
        }
    }

    private suspend fun LocalControlCoordinator.execute(
        expectedNetwork: LanNetworkContext,
        intent: DeviceIntent,
    ): DeviceCatalog = execute(
        expectedNetwork = expectedNetwork,
        expectedDiscoveryAtEpochMillis = 10L,
        intent = intent,
    )

    private class FakeNetworkResolver(
        private val network: LanNetworkContext,
    ) : LanNetworkResolver {
        override suspend fun resolve(): LanNetworkContext = network
    }

    private class FakeGateway(
        private val response: suspend (LocalControlRequest) -> LocalControlResult,
    ) : TuyaPythonGateway {
        var request: LocalControlRequest? = null
        val requests = mutableListOf<LocalControlRequest>()
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
            requests += request
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
            lastDiscoveryNetwork = NETWORK,
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

        fun lightCatalog() = sampleCatalog(
            category = "dj",
            mappingJson = """
                {
                  "20":{"code":"switch_led","type":"Boolean"},
                  "21":{"code":"work_mode","type":"Enum","values":{"range":["white","colour","scene","music"]}},
                  "22":{"code":"bright_value_v2","type":"Integer","values":{"min":10,"max":1000,"step":1,"scale":0}},
                  "23":{"code":"temp_value_v2","type":"Integer","values":{"min":0,"max":1000,"step":1,"scale":0}},
                  "24":{"code":"colour_data_v2","type":"Json"}
                }
            """.trimIndent(),
        ).copy(
            lastDiscoveryAtEpochMillis = 10L,
            localStatus = listOf(
                LocalStatusRecord(
                    id = DEVICE_ID,
                    state = LocalPollDeviceState.RESPONDED,
                    errorCode = "",
                    durationMillis = 20L,
                    dataPoints = listOf(
                        LocalDataPoint("20", LocalDataPointKind.BOOLEAN, "true"),
                        LocalDataPoint("21", LocalDataPointKind.STRING, "white"),
                        LocalDataPoint("22", LocalDataPointKind.INTEGER, "1000"),
                        LocalDataPoint("23", LocalDataPointKind.INTEGER, "1000"),
                        LocalDataPoint("24", LocalDataPointKind.STRING, "000003e803e8"),
                    ),
                    polledAtEpochMillis = 10L,
                )
            ),
        )

        fun coverCatalog() = sampleCatalog(
            category = "cl",
            mappingJson = """
                {
                  "7":{"code":"control_2","type":"Enum","values":{"range":["up","stop","down"]}},
                  "8":{"code":"percent_control_2","type":"Integer","values":{"min":0,"max":100,"step":1,"scale":0}},
                  "9":{"code":"percent_state_2","type":"Integer","values":{"min":0,"max":100,"step":1,"scale":0}}
                }
            """.trimIndent(),
        ).copy(
            localStatus = listOf(
                LocalStatusRecord(
                    id = DEVICE_ID,
                    state = LocalPollDeviceState.RESPONDED,
                    errorCode = "",
                    durationMillis = 20L,
                    dataPoints = listOf(
                        LocalDataPoint("7", LocalDataPointKind.STRING, "stop"),
                        LocalDataPoint("8", LocalDataPointKind.INTEGER, "50"),
                        LocalDataPoint("9", LocalDataPointKind.INTEGER, "52"),
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

        fun confirmedResult(change: LocalControlChange) = LocalControlResult(
            contractVersion = 1,
            id = DEVICE_ID,
            state = LocalControlState.CONFIRMED,
            errorCode = "",
            durationMillis = 35L,
            dataPoints = listOf(
                LocalDataPoint(change.id, change.kind, change.value)
            ),
        )

        fun toggleIntent(value: Boolean) = DeviceIntent.SetToggle(
            deviceId = DEVICE_ID,
            capabilityId = CapabilityId("switch.1"),
            value = value,
        )
    }
}
