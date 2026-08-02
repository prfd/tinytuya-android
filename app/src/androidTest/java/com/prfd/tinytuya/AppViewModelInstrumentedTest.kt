package com.prfd.tinytuya

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.lan.LanDiscoveryCoordinator
import com.prfd.tinytuya.data.lan.LanDiscoveryOutcome
import com.prfd.tinytuya.data.lan.LanDiscoveryResult
import com.prfd.tinytuya.data.lan.LanNetworkContext
import com.prfd.tinytuya.data.lan.LocalControlCoordinator
import com.prfd.tinytuya.data.lan.LocalPollResult
import com.prfd.tinytuya.data.lan.LocalStatusCoordinator
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.ui.app.AppUiState
import com.prfd.tinytuya.ui.app.AppViewModel
import com.prfd.tinytuya.ui.app.LanDiscoveryUiState
import com.prfd.tinytuya.ui.app.LocalControlUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppViewModelInstrumentedTest {
    @Test
    fun missingCatalogRoutesToOnboarding() = runBlocking {
        val viewModel = AppViewModel(
            FakeCatalogStore(),
            FakeLanDiscoveryCoordinator(),
            FakeLocalStatusCoordinator(),
            FakeLocalControlCoordinator(),
        )

        val state = withTimeout(5_000) {
            viewModel.state.first { it !is AppUiState.Loading }
        }

        assertTrue(state is AppUiState.Onboarding)
    }

    @Test
    fun savedCatalogRoutesDirectlyToInventory() = runBlocking {
        val catalog = sampleCatalog()
        val viewModel = AppViewModel(
            FakeCatalogStore(catalog),
            FakeLanDiscoveryCoordinator(),
            FakeLocalStatusCoordinator(),
            FakeLocalControlCoordinator(),
        )

        val state = withTimeout(5_000) {
            viewModel.state.first { it is AppUiState.Inventory }
        } as AppUiState.Inventory

        assertEquals("saved-device", state.catalog.devices.single().id)
        assertTrue(state.catalog.toString().contains("[REDACTED]"))
        assertTrue(!state.catalog.toString().contains("saved-local-key"))
    }

    @Test
    fun deletingLocalDataReturnsToOnboarding() = runBlocking {
        val store = FakeCatalogStore(sampleCatalog())
        val viewModel = AppViewModel(
            store,
            FakeLanDiscoveryCoordinator(),
            FakeLocalStatusCoordinator(),
            FakeLocalControlCoordinator(),
        )
        withTimeout(5_000) {
            viewModel.state.first { it is AppUiState.Inventory }
        }

        viewModel.deleteAllLocalData()

        val state = withTimeout(5_000) {
            viewModel.state.first { it is AppUiState.Onboarding }
        }
        assertTrue(store.deleted)
        assertTrue(state is AppUiState.Onboarding)
    }

    @Test
    fun successfulDiscoveryPublishesPersistedLanState() = runBlocking {
        val original = sampleCatalog()
        val discovered = original.copy(
            schemaVersion = 2,
            lastDiscoveryAtEpochMillis = 5L,
            lanDevices = listOf(
                LanDeviceRecord(
                    id = "saved-device",
                    ip = "192.168.10.42",
                    protocolVersion = "3.5",
                    productKey = "product-key",
                    mac = "",
                    origin = "broadcast",
                    lastSeenAtEpochMillis = 5L,
                )
            ),
        )
        val coordinator = FakeLanDiscoveryCoordinator(discovered)
        val localStatusCoordinator = FakeLocalStatusCoordinator(discovered)
        val viewModel = AppViewModel(
            FakeCatalogStore(original),
            coordinator,
            localStatusCoordinator,
            FakeLocalControlCoordinator(),
        )
        withTimeout(5_000) {
            viewModel.state.first { it is AppUiState.Inventory }
        }

        viewModel.discoverLan()

        val state = withTimeout(5_000) {
            viewModel.state.first {
                it is AppUiState.Inventory && it.discovery is LanDiscoveryUiState.Completed
            }
        } as AppUiState.Inventory
        assertEquals("192.168.10.42", state.catalog.lanDevices.single().ip)
        assertEquals(1, coordinator.callCount)
        assertEquals(1, localStatusCoordinator.callCount)
        assertTrue(state.control is LocalControlUiState.Ready)
    }

    @Test
    fun localControlPublishesOnlyTheCoordinatorConfirmedCatalog() = runBlocking {
        val original = sampleCatalog()
        val discovered = original.copy(
            schemaVersion = 3,
            lastDiscoveryAtEpochMillis = 5L,
            lanDevices = listOf(
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
        val confirmed = discovered.copy(lastLocalPollAtEpochMillis = 6L)
        val controlCoordinator = FakeLocalControlCoordinator(confirmed)
        val viewModel = AppViewModel(
            FakeCatalogStore(original),
            FakeLanDiscoveryCoordinator(discovered),
            FakeLocalStatusCoordinator(discovered),
            controlCoordinator,
        )
        withTimeout(5_000) { viewModel.state.first { it is AppUiState.Inventory } }
        viewModel.discoverLan()
        withTimeout(5_000) {
            viewModel.state.first {
                it is AppUiState.Inventory && it.control is LocalControlUiState.Ready
            }
        }

        viewModel.setBooleanControl("saved-device", "1", true)

        val state = withTimeout(5_000) {
            viewModel.state.first {
                it is AppUiState.Inventory && it.control is LocalControlUiState.Confirmed
            }
        } as AppUiState.Inventory
        assertEquals(6L, state.catalog.lastLocalPollAtEpochMillis)
        assertEquals(1, controlCoordinator.callCount)
        assertEquals(NETWORK, controlCoordinator.expectedNetwork)
    }

    private class FakeLanDiscoveryCoordinator(
        private val result: DeviceCatalog? = null,
    ) : LanDiscoveryCoordinator {
        var callCount = 0

        override suspend fun discover(catalog: DeviceCatalog): LanDiscoveryOutcome {
            callCount += 1
            return LanDiscoveryOutcome(
                catalog = result ?: catalog,
                network = NETWORK,
            )
        }
    }

    private class FakeLocalStatusCoordinator(
        private val result: DeviceCatalog? = null,
    ) : LocalStatusCoordinator {
        var callCount = 0

        override suspend fun poll(
            catalog: DeviceCatalog,
            network: LanNetworkContext,
        ): DeviceCatalog {
            callCount += 1
            return result ?: catalog
        }
    }

    private class FakeLocalControlCoordinator(
        private val result: DeviceCatalog? = null,
    ) : LocalControlCoordinator {
        var callCount = 0
        var expectedNetwork: LanNetworkContext? = null

        override suspend fun setBoolean(
            catalog: DeviceCatalog,
            expectedNetwork: LanNetworkContext,
            deviceId: String,
            dataPointId: String,
            value: Boolean,
        ): DeviceCatalog {
            callCount += 1
            this.expectedNetwork = expectedNetwork
            return result ?: catalog
        }
    }

    private class FakeCatalogStore(
        private var catalog: DeviceCatalog? = null,
    ) : DeviceCatalogStore {
        var deleted = false

        override suspend fun load(): DeviceCatalog? = catalog

        override suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog =
            error("Cloud replacement is not used by this app-routing test.")

        override suspend fun mergeLanDiscovery(result: LanDiscoveryResult): DeviceCatalog =
            error("LAN replacement is not used by this app-routing test.")

        override suspend fun mergeLocalPoll(result: LocalPollResult): DeviceCatalog =
            error("Local status is not used by this app-routing test.")

        override suspend fun deleteAll() {
            deleted = true
            catalog = null
        }
    }

    private companion object {
        val NETWORK = LanNetworkContext(
            interfaceName = "wlan0",
            localIpv4 = "192.168.10.5",
            prefixLength = 24,
            broadcastIpv4 = "192.168.10.255",
        )

        fun sampleCatalog() = DeviceCatalog(
            schemaVersion = 1,
            importedAtEpochMillis = 1L,
            region = TuyaCloudRegion.WESTERN_AMERICA,
            devices = listOf(
                CloudImportedDevice(
                    id = "saved-device",
                    name = "Saved lamp",
                    localKey = SensitiveString.of("saved-local-key"),
                    category = "dj",
                    productId = "",
                    productName = "Lamp",
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
    }
}
