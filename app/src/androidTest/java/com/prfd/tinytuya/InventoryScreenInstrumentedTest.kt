package com.prfd.tinytuya

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.local.LocalStatusRecord
import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.lan.LocalPollDeviceState
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.ui.inventory.InventoryScreen
import com.prfd.tinytuya.ui.app.LanDiscoveryUiState
import com.prfd.tinytuya.ui.app.LocalControlUiState
import com.prfd.tinytuya.ui.theme.TinytuyaTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InventoryScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inventoryShowsReadinessWithoutRenderingLocalKey() {
        setInventoryContent()
        composeRule.onNodeWithTag("inventory_list").performScrollToIndex(4)

        composeRule.onNodeWithText("Office lamp").assertExists()
        composeRule.onNodeWithText("Key secured").assertExists()
        composeRule.onNodeWithText(LOCAL_KEY, substring = true).assertDoesNotExist()
    }

    @Test
    fun deletingDataRequiresExplicitConfirmation() {
        var deleteCalled = false
        setInventoryContent(onDelete = { deleteCalled = true })
        composeRule.onNodeWithTag("inventory_list").performScrollToIndex(5)

        composeRule.onNodeWithText("Delete all local data").performClick()
        composeRule.onNodeWithText("Delete all local data?").assertExists()
        assertFalse(deleteCalled)

        composeRule.onNodeWithText("Delete data").performClick()
        composeRule.runOnIdle { assertTrue(deleteCalled) }
    }

    @Test
    fun localScanStartsOnlyAfterExplicitTap() {
        var scanCalled = false
        setInventoryContent(onDiscoverLan = { scanCalled = true })

        assertFalse(scanCalled)
        composeRule.onNodeWithText("Scan & read status").performClick()
        composeRule.runOnIdle { assertTrue(scanCalled) }
    }

    @Test
    fun scanningStateDisablesRepeatedScan() {
        setInventoryContent(discovery = LanDiscoveryUiState.Scanning)

        composeRule.onNodeWithText("Listening for Tuya devices").assertExists()
        composeRule.onNodeWithTag("lan_scan_button").assertIsNotEnabled()
    }

    @Test
    fun latestScanDistinguishesMatchedAndUnlinkedDevices() {
        val catalog = sampleCatalog().copy(
            schemaVersion = 2,
            lastDiscoveryAtEpochMillis = 9L,
            lanDevices = listOf(
                lanRecord(id = "office-lamp", ip = "192.168.10.20"),
                lanRecord(id = "unlinked-device", ip = "192.168.10.21"),
            ),
        )
        setInventoryContent(catalog = catalog)
        composeRule.onNodeWithTag("inventory_list").performScrollToIndex(6)

        composeRule.onNodeWithText("Unlinked Tuya device").assertExists()
        composeRule.onNodeWithText("Cloud key unavailable").assertExists()
        composeRule.onNodeWithText("192.168.10.21", substring = true).assertExists()
        composeRule.onNodeWithText(LOCAL_KEY, substring = true).assertDoesNotExist()
    }

    @Test
    fun mappedSwitchStatusIsRenderedWithoutRawSecrets() {
        val catalog = controlledCatalog()

        setInventoryContent(catalog = catalog)
        composeRule.onNodeWithTag("inventory_list").performScrollToIndex(4)

        composeRule.onNodeWithText("Power is on").assertExists()
        composeRule.onNodeWithText("Power").assertExists()
        composeRule.onNodeWithTag("local_switch_1").assertIsOn()
        composeRule.onNodeWithText(LOCAL_KEY, substring = true).assertDoesNotExist()
    }

    @Test
    fun localSwitchRequiresAnInProcessRefreshBeforeControl() {
        setInventoryContent(catalog = controlledCatalog())
        composeRule.onNodeWithTag("inventory_list").performScrollToIndex(4)

        composeRule.onNodeWithTag("local_switch_1").assertIsNotEnabled().assertIsOn()
        composeRule.onNodeWithText("Refresh local devices to enable control.").assertExists()
    }

    @Test
    fun verifiedLocalSwitchInvokesTheTypedControlCallback() {
        var request: Triple<String, String, Boolean>? = null
        setInventoryContent(
            catalog = controlledCatalog(),
            control = LocalControlUiState.Ready,
            onSetBooleanControl = { deviceId, dataPointId, value ->
                request = Triple(deviceId, dataPointId, value)
            },
        )
        composeRule.onNodeWithTag("inventory_list").performScrollToIndex(4)

        composeRule.onNodeWithTag("local_switch_1").assertIsOn().performClick()

        composeRule.runOnIdle {
            assertTrue(request == Triple("office-lamp", "1", false))
        }
        composeRule.onNodeWithText(LOCAL_KEY, substring = true).assertDoesNotExist()
    }

    @Test
    fun pendingControlKeepsTheLastConfirmedStateVisible() {
        setInventoryContent(
            catalog = controlledCatalog(),
            control = LocalControlUiState.Sending("office-lamp", "1", false),
        )
        composeRule.onNodeWithTag("inventory_list").performScrollToIndex(4)

        composeRule.onNodeWithTag("local_switch_1").assertIsOn().assertIsNotEnabled()
        composeRule.onNodeWithText("Turning off and confirming…").assertExists()
        composeRule.onNodeWithTag("local_control_progress").assertExists()
    }

    @Test
    fun unconfirmedCommandAsksForRefreshWithoutClaimingTheWriteFailed() {
        val catalog = controlledCatalog().copy(
            lastLocalPollAtEpochMillis = 11L,
            localStatus = listOf(
                LocalStatusRecord(
                    id = "office-lamp",
                    state = LocalPollDeviceState.OFFLINE,
                    errorCode = "LOCAL_CONTROL_UNCONFIRMED",
                    durationMillis = 4_500L,
                    dataPoints = emptyList(),
                    polledAtEpochMillis = 11L,
                )
            ),
        )
        setInventoryContent(
            catalog = catalog,
            control = LocalControlUiState.Error(
                deviceId = "office-lamp",
                dataPointId = "1",
                code = "LOCAL_CONTROL_UNCONFIRMED",
                message = "The device did not confirm its new state.",
            ),
        )
        composeRule.onNodeWithTag("inventory_list").performScrollToIndex(4)

        composeRule.onNodeWithText("Could not confirm the requested state").assertExists()
        composeRule.onNodeWithText("Refresh to verify", substring = true).assertExists()
        composeRule.onNodeWithText("command failed", substring = true).assertDoesNotExist()
    }

    @Test
    fun multiGangCardSummarizesAndControlsEachVerifiedChannel() {
        var request: Triple<String, String, Boolean>? = null
        setInventoryContent(
            catalog = multiGangCatalog(),
            control = LocalControlUiState.Ready,
            onSetBooleanControl = { deviceId, dataPointId, value ->
                request = Triple(deviceId, dataPointId, value)
            },
        )
        composeRule.onNodeWithTag("inventory_list").performScrollToIndex(4)

        composeRule.onNodeWithText("3-gang switch", substring = true).assertExists()
        composeRule.onNodeWithText("2 of 3 switches on").assertExists()
        composeRule.onNodeWithText("3 LOCAL SWITCHES").assertExists()
        composeRule.onNodeWithTag("local_switch_1").assertIsOn()
        composeRule.onNodeWithTag("local_switch_2").assertIsOff().performClick()
        composeRule.onNodeWithTag("local_switch_3").assertIsOn()

        composeRule.runOnIdle {
            assertTrue(request == Triple("office-lamp", "2", true))
        }
    }

    @Test
    fun outletCardPrioritizesScaledElectricalMetricsAfterItsSwitch() {
        setInventoryContent(catalog = outletMetricsCatalog())
        composeRule.onNodeWithTag("inventory_list").performScrollToIndex(4)

        composeRule.onNodeWithText("Smart outlet", substring = true).assertExists()
        composeRule.onNodeWithText("Power draw").assertExists()
        composeRule.onNodeWithText("12.3 W").assertExists()
        composeRule.onNodeWithText("Voltage").assertExists()
        composeRule.onNodeWithText("230.4 V").assertExists()
        composeRule.onNodeWithText("Current").assertExists()
        composeRule.onNodeWithText("421 mA").assertExists()
        composeRule.onNodeWithText("Energy").assertExists()
        composeRule.onNodeWithText("1.234 kWh").assertExists()
        composeRule.onNodeWithText("Countdown").assertExists()
        composeRule.onNodeWithText("Off").assertExists()
        composeRule.onNodeWithText("Child lock").assertExists()
        composeRule.onNodeWithText("Yes").assertExists()
    }

    @Test
    fun gatewayChildIsClearlyUnsupportedWithoutAFalseRefreshPrompt() {
        val catalog = sampleCatalog().copy(
            devices = listOf(
                sampleCatalog().devices.single().copy(
                    isSubDevice = true,
                    gatewayId = "gateway-id",
                    mappingJson = "{\"20\":{\"code\":\"switch_led\",\"type\":\"Boolean\"}}",
                )
            )
        )
        setInventoryContent(catalog = catalog)
        composeRule.onNodeWithTag("inventory_list").performScrollToIndex(4)

        composeRule.onNodeWithText("Gateway child").assertExists()
        composeRule.onNodeWithText("Unsupported locally").assertExists()
        composeRule.onNodeWithText("communicates through a Tuya gateway", substring = true)
            .assertExists()
        composeRule.onNodeWithText("Refresh local devices to read", substring = true)
            .assertDoesNotExist()
        composeRule.onNodeWithTag("local_switch_20").assertDoesNotExist()
    }

    @Test
    fun protectedCameraHidesCachedDpsAndControlEvenWithASwitchMapping() {
        val catalog = controlledCatalog().copy(
            devices = listOf(
                controlledCatalog().devices.single().copy(
                    category = "sp",
                    productName = "Indoor camera",
                )
            )
        )
        setInventoryContent(catalog = catalog, control = LocalControlUiState.Ready)
        composeRule.onNodeWithTag("inventory_list").performScrollToIndex(4)

        composeRule.onNodeWithText("Smart camera", substring = true).assertExists()
        composeRule.onNodeWithText("Camera controls disabled").assertExists()
        composeRule.onNodeWithText("Camera streams and camera commands", substring = true)
            .assertExists()
        composeRule.onNodeWithTag("local_switch_1").assertDoesNotExist()
        composeRule.onNodeWithTag("dps_inspector_toggle").assertDoesNotExist()
        composeRule.onNodeWithText("Power is on").assertDoesNotExist()
    }

    @Test
    fun statusOnlyDeviceOffersBoundedDpsDetailsWithoutRenderingPrivatePayloads() {
        setInventoryContent(catalog = statusOnlyCatalog())
        composeRule.onNodeWithTag("inventory_list").performScrollToIndex(4)

        composeRule.onNodeWithText("Status only").assertExists()
        composeRule.onNodeWithText("Status-only profile").assertExists()
        composeRule.onNodeWithText("Local DPS stays read-only", substring = true).assertExists()
        composeRule.onNodeWithText(PRIVATE_DP_TEXT, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(PRIVATE_DP_JSON, substring = true).assertDoesNotExist()

        composeRule.onNodeWithTag("dps_inspector_toggle").performClick()

        composeRule.onNodeWithTag("dps_inspector_panel").assertExists()
        composeRule.onNodeWithText("LOCAL DPS · READ ONLY").assertExists()
        composeRule.onNodeWithText("DP 1 · Boolean").assertExists()
        composeRule.onNodeWithText("DP 3 · Enum").assertExists()
        composeRule.onNodeWithText("DP 4 · Text").assertExists()
        composeRule.onNodeWithText("DP 5 · Structured").assertExists()
        composeRule.onNodeWithText("Text and structured payloads stay hidden", substring = true)
            .assertExists()
        composeRule.onNodeWithText(PRIVATE_DP_TEXT, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(PRIVATE_DP_JSON, substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("local_switch_1").assertDoesNotExist()
    }

    private fun setInventoryContent(
        catalog: DeviceCatalog = sampleCatalog(),
        discovery: LanDiscoveryUiState = LanDiscoveryUiState.Idle,
        control: LocalControlUiState = LocalControlUiState.Unavailable,
        onDiscoverLan: () -> Unit = {},
        onSetBooleanControl: (String, String, Boolean) -> Unit = { _, _, _ -> },
        onDelete: () -> Unit = {},
    ) {
        composeRule.setContent {
            TinytuyaTheme(darkTheme = false) {
                InventoryScreen(
                    catalog = catalog,
                    discovery = discovery,
                    control = control,
                    onDiscoverLan = onDiscoverLan,
                    onSetBooleanControl = onSetBooleanControl,
                    onImportFromCloud = {},
                    onDeleteAllLocalData = onDelete,
                )
            }
        }
    }

    private fun sampleCatalog() = DeviceCatalog(
        schemaVersion = 1,
        importedAtEpochMillis = 1_753_981_200_000L,
        region = TuyaCloudRegion.WESTERN_AMERICA,
        devices = listOf(
            CloudImportedDevice(
                id = "office-lamp",
                name = "Office lamp",
                localKey = SensitiveString.of(LOCAL_KEY),
                category = "dj",
                productId = "",
                productName = "Lamp",
                model = "L1",
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

    private fun controlledCatalog() = sampleCatalog().copy(
        schemaVersion = 3,
        devices = listOf(
            sampleCatalog().devices.single().copy(
                category = "kg",
                mappingJson = "{\"1\":{\"code\":\"switch_1\",\"type\":\"Boolean\"}}",
            )
        ),
        lastDiscoveryAtEpochMillis = 9L,
        lanDevices = listOf(lanRecord(id = "office-lamp", ip = "192.168.10.20")),
        lastLocalPollAtEpochMillis = 10L,
        localStatus = listOf(
            LocalStatusRecord(
                id = "office-lamp",
                state = LocalPollDeviceState.RESPONDED,
                errorCode = "",
                durationMillis = 42L,
                dataPoints = listOf(
                    LocalDataPoint(
                        id = "1",
                        kind = LocalDataPointKind.BOOLEAN,
                        value = "true",
                    )
                ),
                polledAtEpochMillis = 10L,
            )
        ),
    )

    private fun multiGangCatalog() = controlledCatalog().copy(
        devices = listOf(
            controlledCatalog().devices.single().copy(
                productName = "Wall switch",
                mappingJson = """
                    {
                      "1":{"code":"switch_1","type":"Boolean"},
                      "2":{"code":"switch_2","type":"Boolean"},
                      "3":{"code":"switch_3","type":"Boolean"}
                    }
                """.trimIndent(),
            )
        ),
        localStatus = listOf(
            controlledCatalog().localStatus.single().copy(
                dataPoints = listOf(
                    LocalDataPoint("3", LocalDataPointKind.BOOLEAN, "true"),
                    LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
                    LocalDataPoint("2", LocalDataPointKind.BOOLEAN, "false"),
                )
            )
        ),
    )

    private fun outletMetricsCatalog() = controlledCatalog().copy(
        devices = listOf(
            controlledCatalog().devices.single().copy(
                category = "cz",
                productName = "Metered outlet",
                mappingJson = """
                    {
                      "1":{"code":"switch_1","type":"Boolean"},
                      "4":{"code":"child_lock","type":"Boolean"},
                      "9":{"code":"countdown_1","type":"Integer"},
                      "18":{"code":"cur_current","type":"Integer","values":{"unit":"mA","scale":0}},
                      "19":{"code":"cur_power","type":"Integer","values":"{\"unit\":\"W\",\"scale\":1}"},
                      "20":{"code":"cur_voltage","type":"Integer","values":{"unit":"V","scale":1}},
                      "21":{"code":"add_ele","type":"Integer","values":{"unit":"kWh","scale":3}}
                    }
                """.trimIndent(),
            )
        ),
        localStatus = listOf(
            controlledCatalog().localStatus.single().copy(
                dataPoints = listOf(
                    LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
                    LocalDataPoint("4", LocalDataPointKind.BOOLEAN, "true"),
                    LocalDataPoint("9", LocalDataPointKind.INTEGER, "0"),
                    LocalDataPoint("18", LocalDataPointKind.INTEGER, "421"),
                    LocalDataPoint("19", LocalDataPointKind.INTEGER, "123"),
                    LocalDataPoint("20", LocalDataPointKind.INTEGER, "2304"),
                    LocalDataPoint("21", LocalDataPointKind.INTEGER, "1234"),
                )
            )
        ),
    )

    private fun statusOnlyCatalog() = controlledCatalog().copy(
        devices = listOf(
            controlledCatalog().devices.single().copy(
                category = "custom_sensor",
                productName = "Room sensor",
                mappingJson = """
                    {
                      "1":{"code":"enabled","type":"Boolean"},
                      "2":{"code":"sample_count","type":"Integer"},
                      "3":{"code":"mode","type":"Enum","values":"{\"range\":[\"auto\",\"manual\"]}"},
                      "4":{"code":"api_token","type":"String"},
                      "5":{"code":"raw_blob","type":"Raw"}
                    }
                """.trimIndent(),
            )
        ),
        localStatus = listOf(
            controlledCatalog().localStatus.single().copy(
                dataPoints = listOf(
                    LocalDataPoint("5", LocalDataPointKind.JSON, PRIVATE_DP_JSON),
                    LocalDataPoint("3", LocalDataPointKind.STRING, "auto"),
                    LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
                    LocalDataPoint("4", LocalDataPointKind.STRING, PRIVATE_DP_TEXT),
                    LocalDataPoint("2", LocalDataPointKind.INTEGER, "42"),
                )
            )
        ),
    )

    private fun lanRecord(id: String, ip: String) = LanDeviceRecord(
        id = id,
        ip = ip,
        protocolVersion = "3.5",
        productKey = "product-key",
        mac = "",
        origin = "broadcast",
        lastSeenAtEpochMillis = 9L,
    )

    private companion object {
        const val LOCAL_KEY = "inventory-local-key-must-stay-hidden"
        const val PRIVATE_DP_TEXT = "private-device-token"
        const val PRIVATE_DP_JSON = "private-json-token"
    }
}
