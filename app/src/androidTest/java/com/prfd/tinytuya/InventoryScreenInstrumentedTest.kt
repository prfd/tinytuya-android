package com.prfd.tinytuya

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.ui.inventory.InventoryScreen
import com.prfd.tinytuya.ui.app.LanDiscoveryUiState
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
        composeRule.onNodeWithText("Scan this Wi-Fi").performClick()
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

    private fun setInventoryContent(
        catalog: DeviceCatalog = sampleCatalog(),
        discovery: LanDiscoveryUiState = LanDiscoveryUiState.Idle,
        onDiscoverLan: () -> Unit = {},
        onDelete: () -> Unit = {},
    ) {
        composeRule.setContent {
            TinytuyaTheme(darkTheme = false) {
                InventoryScreen(
                    catalog = catalog,
                    discovery = discovery,
                    onDiscoverLan = onDiscoverLan,
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
