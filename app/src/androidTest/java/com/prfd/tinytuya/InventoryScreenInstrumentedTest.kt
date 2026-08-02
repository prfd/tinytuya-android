package com.prfd.tinytuya

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
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

        composeRule.onNodeWithText("Last local response").assertExists()
        composeRule.onNodeWithText("Power").assertExists()
        composeRule.onNodeWithText("On").assertExists()
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
    }
}
