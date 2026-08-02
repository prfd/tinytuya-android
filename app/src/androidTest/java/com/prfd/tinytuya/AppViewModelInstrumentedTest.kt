package com.prfd.tinytuya

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.lan.LanDiscoveryCoordinator
import com.prfd.tinytuya.data.lan.LanDiscoveryException
import com.prfd.tinytuya.data.lan.LanDiscoveryOutcome
import com.prfd.tinytuya.data.lan.LanDiscoveryResult
import com.prfd.tinytuya.data.lan.LanNetworkContext
import com.prfd.tinytuya.data.lan.LanNetworkObservation
import com.prfd.tinytuya.data.lan.LanNetworkObserver
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

    @Test
    fun savedLanSnapshotIsRejectedOnAnotherNetworkWithTheSameSubnet() = runBlocking {
        val observer = FakeLanNetworkObserver(
            LanNetworkObservation.Available(
                NETWORK.copy(networkHandle = NETWORK.networkHandle + 1)
            )
        )
        val viewModel = AppViewModel(
            FakeCatalogStore(discoveredCatalog()),
            FakeLanDiscoveryCoordinator(),
            FakeLocalStatusCoordinator(),
            FakeLocalControlCoordinator(),
            observer,
        )

        val state = withTimeout(5_000) {
            viewModel.state.first {
                it is AppUiState.Inventory &&
                    it.discovery is LanDiscoveryUiState.Error &&
                    !it.isLanSnapshotCurrent
            }
        } as AppUiState.Inventory

        assertEquals(
            "LAN_NETWORK_CHANGED",
            (state.discovery as LanDiscoveryUiState.Error).code,
        )
        assertTrue(state.control is LocalControlUiState.Unavailable)
        assertEquals(NETWORK.localIpv4, state.catalog.lastDiscoveryNetwork?.localIpv4)
    }

    @Test
    fun losingWifiInvalidatesACompletedRefreshAndDisablesControl() = runBlocking {
        val discovered = discoveredCatalog()
        val observer = FakeLanNetworkObserver(LanNetworkObservation.Available(NETWORK))
        val controlCoordinator = FakeLocalControlCoordinator()
        val viewModel = AppViewModel(
            FakeCatalogStore(sampleCatalog()),
            FakeLanDiscoveryCoordinator(discovered),
            FakeLocalStatusCoordinator(discovered),
            controlCoordinator,
            observer,
        )
        withTimeout(5_000) { viewModel.state.first { it is AppUiState.Inventory } }
        viewModel.discoverLan()
        withTimeout(5_000) {
            viewModel.state.first {
                it is AppUiState.Inventory && it.control is LocalControlUiState.Ready
            }
        }

        observer.emit(LanNetworkObservation.Unavailable)

        val invalidated = withTimeout(5_000) {
            viewModel.state.first {
                it is AppUiState.Inventory && !it.isLanSnapshotCurrent
            }
        } as AppUiState.Inventory
        assertTrue(invalidated.discovery is LanDiscoveryUiState.Error)
        assertTrue(invalidated.control is LocalControlUiState.Unavailable)

        viewModel.setBooleanControl("saved-device", "1", true)
        assertEquals(0, controlCoordinator.callCount)
    }

    @Test
    fun controlResultCannotReauthorizeSessionInvalidatedWhileCommandWasRunning() = runBlocking {
        val discovered = discoveredCatalog()
        val confirmed = discovered.copy(lastLocalPollAtEpochMillis = 6L)
        val observer = FakeLanNetworkObserver(LanNetworkObservation.Available(NETWORK))
        val controlCoordinator = BlockingLocalControlCoordinator()
        val viewModel = AppViewModel(
            FakeCatalogStore(sampleCatalog()),
            FakeLanDiscoveryCoordinator(discovered),
            FakeLocalStatusCoordinator(discovered),
            controlCoordinator,
            observer,
        )
        withTimeout(5_000) { viewModel.state.first { it is AppUiState.Inventory } }
        viewModel.discoverLan()
        withTimeout(5_000) {
            viewModel.state.first {
                it is AppUiState.Inventory && it.control is LocalControlUiState.Ready
            }
        }

        viewModel.setBooleanControl("saved-device", "1", true)
        withTimeout(5_000) { controlCoordinator.started.await() }
        observer.emit(
            LanNetworkObservation.Available(
                NETWORK.copy(networkHandle = NETWORK.networkHandle + 1)
            )
        )
        withTimeout(5_000) {
            viewModel.state.first {
                it is AppUiState.Inventory && !it.isLanSnapshotCurrent
            }
        }

        controlCoordinator.result.complete(confirmed)

        val finalState = withTimeout(5_000) {
            viewModel.state.first {
                it is AppUiState.Inventory && it.catalog.lastLocalPollAtEpochMillis == 6L
            }
        } as AppUiState.Inventory
        assertTrue(finalState.control is LocalControlUiState.Unavailable)
        assertTrue(finalState.discovery is LanDiscoveryUiState.Error)
        assertTrue(!finalState.isLanSnapshotCurrent)
    }

    @Test
    fun failedScanCannotRestoreSnapshotInvalidatedWhileScanWasRunning() = runBlocking {
        val observer = FakeLanNetworkObserver(LanNetworkObservation.Available(NETWORK))
        val discoveryCoordinator = BlockingLanDiscoveryCoordinator()
        val viewModel = AppViewModel(
            FakeCatalogStore(discoveredCatalog()),
            discoveryCoordinator,
            FakeLocalStatusCoordinator(),
            FakeLocalControlCoordinator(),
            observer,
        )
        withTimeout(5_000) { viewModel.state.first { it is AppUiState.Inventory } }

        viewModel.discoverLan()
        withTimeout(5_000) { discoveryCoordinator.started.await() }
        observer.emit(
            LanNetworkObservation.Available(
                NETWORK.copy(networkHandle = NETWORK.networkHandle + 1)
            )
        )
        withTimeout(5_000) {
            viewModel.state.first {
                it is AppUiState.Inventory && !it.isLanSnapshotCurrent
            }
        }
        discoveryCoordinator.result.completeExceptionally(
            LanDiscoveryException(
                code = "LAN_SCAN_FAILED",
                message = "The old network scan stopped.",
            )
        )

        val finalState = withTimeout(5_000) {
            viewModel.state.first {
                it is AppUiState.Inventory && it.discovery is LanDiscoveryUiState.Error
            }
        } as AppUiState.Inventory
        assertEquals(
            "LAN_NETWORK_CHANGED",
            (finalState.discovery as LanDiscoveryUiState.Error).code,
        )
        assertTrue(!finalState.isLanSnapshotCurrent)
        assertTrue(finalState.control is LocalControlUiState.Unavailable)
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

    private class BlockingLanDiscoveryCoordinator : LanDiscoveryCoordinator {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<LanDiscoveryOutcome>()

        override suspend fun discover(catalog: DeviceCatalog): LanDiscoveryOutcome {
            started.complete(Unit)
            return result.await()
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

    private class BlockingLocalControlCoordinator : LocalControlCoordinator {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<DeviceCatalog>()

        override suspend fun setBoolean(
            catalog: DeviceCatalog,
            expectedNetwork: LanNetworkContext,
            deviceId: String,
            dataPointId: String,
            value: Boolean,
        ): DeviceCatalog {
            started.complete(Unit)
            return result.await()
        }
    }

    private class FakeLanNetworkObserver(initial: LanNetworkObservation) : LanNetworkObserver {
        private val observations = MutableSharedFlow<LanNetworkObservation>(replay = 1).apply {
            tryEmit(initial)
        }

        override fun observe(): Flow<LanNetworkObservation> = observations

        suspend fun emit(observation: LanNetworkObservation) {
            observations.emit(observation)
        }
    }

    private class FakeCatalogStore(
        private var catalog: DeviceCatalog? = null,
    ) : DeviceCatalogStore {
        var deleted = false

        override suspend fun load(): DeviceCatalog? = catalog

        override suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog =
            error("Cloud replacement is not used by this app-routing test.")

        override suspend fun mergeLanDiscovery(
            result: LanDiscoveryResult,
            network: LanNetworkContext,
        ): DeviceCatalog =
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
            networkHandle = 101L,
        )

        fun discoveredCatalog() = sampleCatalog().copy(
            schemaVersion = 4,
            lastDiscoveryAtEpochMillis = 5L,
            lastDiscoveryNetwork = NETWORK,
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
