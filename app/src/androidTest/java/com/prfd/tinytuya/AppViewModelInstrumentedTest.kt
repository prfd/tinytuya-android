package com.prfd.tinytuya

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.lan.KnownDeviceRefreshCoordinator
import com.prfd.tinytuya.data.lan.KnownDeviceRefreshOutcome
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
import com.prfd.tinytuya.data.lan.LocalStatusException
import com.prfd.tinytuya.data.local.AppSettings
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.local.InMemoryAppSettingsStore
import com.prfd.tinytuya.data.local.InMemoryCloudCredentialStore
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.local.StoredCloudCredentials
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.ui.DeviceControlUiState as LocalControlUiState
import com.prfd.tinytuya.ui.app.AppUiState
import com.prfd.tinytuya.ui.app.AppViewModel
import com.prfd.tinytuya.ui.app.CloudAccountUiState
import com.prfd.tinytuya.ui.app.LanDiscoveryUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppViewModelInstrumentedTest {
  @Test
  fun missingCatalogRoutesToOnboarding() = runBlocking {
    val viewModel =
      AppViewModel(
        FakeCatalogStore(),
        FakeLanDiscoveryCoordinator(),
        FakeLocalStatusCoordinator(),
        FakeLocalControlCoordinator(),
      )

    val state = withTimeout(5_000) { viewModel.state.first { it !is AppUiState.Loading } }

    assertTrue(state is AppUiState.Onboarding)
  }

  @Test
  fun savedCatalogRoutesDirectlyToInventory() = runBlocking {
    val catalog = sampleCatalog()
    val viewModel =
      AppViewModel(
        FakeCatalogStore(catalog),
        FakeLanDiscoveryCoordinator(),
        FakeLocalStatusCoordinator(),
        FakeLocalControlCoordinator(),
      )

    val state =
      withTimeout(5_000) { viewModel.state.first { it is AppUiState.Inventory } }
        as AppUiState.Inventory

    assertEquals("saved-device", state.catalog.devices.single().id)
    assertTrue(state.catalog.toString().contains("[REDACTED]"))
    assertTrue(!state.catalog.toString().contains("saved-local-key"))
  }

  @Test
  fun forgettingCloudCredentialsPreservesTheDeviceCatalog() = runBlocking {
    val catalogStore = FakeCatalogStore(sampleCatalog())
    val credentialStore = InMemoryCloudCredentialStore(savedCredentials())
    val viewModel =
      AppViewModel(
        catalogStore = catalogStore,
        lanDiscoveryCoordinator = FakeLanDiscoveryCoordinator(),
        localStatusCoordinator = FakeLocalStatusCoordinator(),
        localControlCoordinator = FakeLocalControlCoordinator(),
        credentialStore = credentialStore,
      )
    withTimeout(5_000) {
      viewModel.settingsState.first { it.cloudAccount is CloudAccountUiState.Saved }
    }

    viewModel.forgetCloudCredentials()

    withTimeout(5_000) {
      viewModel.settingsState.first { it.cloudAccount is CloudAccountUiState.Missing }
    }
    assertNull(credentialStore.load())
    assertTrue(!catalogStore.deleted)
    assertTrue(viewModel.state.value is AppUiState.Inventory)
  }

  @Test
  fun deletingAllLocalDataClearsBothStoresAndReturnsToOnboarding() = runBlocking {
    val catalogStore = FakeCatalogStore(sampleCatalog())
    val credentialStore = InMemoryCloudCredentialStore(savedCredentials())
    val viewModel =
      AppViewModel(
        catalogStore = catalogStore,
        lanDiscoveryCoordinator = FakeLanDiscoveryCoordinator(),
        localStatusCoordinator = FakeLocalStatusCoordinator(),
        localControlCoordinator = FakeLocalControlCoordinator(),
        credentialStore = credentialStore,
      )
    withTimeout(5_000) { viewModel.state.first { it is AppUiState.Inventory } }

    viewModel.deleteAllLocalData()

    withTimeout(5_000) { viewModel.state.first { it is AppUiState.Onboarding } }
    assertTrue(catalogStore.deleted)
    assertNull(credentialStore.load())
    assertTrue(viewModel.settingsState.value.cloudAccount is CloudAccountUiState.Missing)
  }

  @Test
  fun successfulDiscoveryPublishesPersistedLanState() = runBlocking {
    val original = sampleCatalog()
    val discovered =
      original.copy(
        schemaVersion = 2,
        lastDiscoveryAtEpochMillis = 5L,
        lanDevices =
          listOf(
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
    val viewModel =
      AppViewModel(
        FakeCatalogStore(original),
        coordinator,
        localStatusCoordinator,
        FakeLocalControlCoordinator(),
      )
    withTimeout(5_000) { viewModel.state.first { it is AppUiState.Inventory } }

    viewModel.discoverLan()

    val state =
      withTimeout(5_000) {
        viewModel.state.first {
          it is AppUiState.Inventory && it.discovery is LanDiscoveryUiState.Completed
        }
      }
        as AppUiState.Inventory
    assertEquals("192.168.10.42", state.catalog.lanDevices.single().ip)
    assertEquals(1, coordinator.callCount)
    assertEquals(1, localStatusCoordinator.callCount)
    assertTrue(state.control is LocalControlUiState.Ready)
  }

  @Test
  fun localControlPublishesOnlyTheCoordinatorConfirmedCatalog() = runBlocking {
    val original = sampleCatalog()
    val discovered =
      original.copy(
        schemaVersion = 3,
        lastDiscoveryAtEpochMillis = 5L,
        lanDevices =
          listOf(
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
    val viewModel =
      AppViewModel(
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

    val intent = toggleIntent(true)
    viewModel.submitControl(intent)

    val state =
      withTimeout(5_000) {
        viewModel.state.first {
          it is AppUiState.Inventory && it.control is LocalControlUiState.Confirmed
        }
      }
        as AppUiState.Inventory
    assertEquals(6L, state.catalog.lastLocalPollAtEpochMillis)
    assertEquals(1, controlCoordinator.callCount)
    assertEquals(NETWORK, controlCoordinator.expectedNetwork)
    assertEquals(5L, controlCoordinator.expectedDiscoveryAtEpochMillis)
    assertEquals(intent, controlCoordinator.intent)
  }

  @Test
  fun lightControlPublishesItsTypedOperationAndConfirmedCatalog() = runBlocking {
    val original = sampleCatalog()
    val discovered = discoveredCatalog()
    val confirmed = discovered.copy(lastLocalPollAtEpochMillis = 6L)
    val controlCoordinator = FakeLocalControlCoordinator(confirmed)
    val viewModel =
      AppViewModel(
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
    val intent =
      DeviceIntent.SetChoice(
        "saved-device",
        CapabilityId("light.mode"),
        "colour",
      )

    viewModel.submitControl(intent)

    val state =
      withTimeout(5_000) {
        viewModel.state.first {
          it is AppUiState.Inventory && it.control is LocalControlUiState.Confirmed
        }
      }
        as AppUiState.Inventory
    val control = state.control as LocalControlUiState.Confirmed
    assertEquals(intent, control.intent)
    assertEquals(intent, controlCoordinator.intent)
    assertEquals(6L, state.catalog.lastLocalPollAtEpochMillis)
  }

  @Test
  fun savedLanSnapshotIsRejectedOnAnotherNetworkWithTheSameSubnet() = runBlocking {
    val observer =
      FakeLanNetworkObserver(
        LanNetworkObservation.Available(NETWORK.copy(networkHandle = NETWORK.networkHandle + 1))
      )
    val viewModel =
      AppViewModel(
        FakeCatalogStore(discoveredCatalog()),
        FakeLanDiscoveryCoordinator(),
        FakeLocalStatusCoordinator(),
        FakeLocalControlCoordinator(),
        observer,
      )

    val state =
      withTimeout(5_000) {
        viewModel.state.first {
          it is AppUiState.Inventory &&
            it.discovery is LanDiscoveryUiState.Error &&
            !it.isLanSnapshotCurrent
        }
      }
        as AppUiState.Inventory

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
    val viewModel =
      AppViewModel(
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

    val invalidated =
      withTimeout(5_000) {
        viewModel.state.first { it is AppUiState.Inventory && !it.isLanSnapshotCurrent }
      }
        as AppUiState.Inventory
    assertTrue(invalidated.discovery is LanDiscoveryUiState.Error)
    assertTrue(invalidated.control is LocalControlUiState.Unavailable)

    viewModel.submitControl(toggleIntent(true))
    assertEquals(0, controlCoordinator.callCount)
  }

  @Test
  fun controlResultCannotReauthorizeSessionInvalidatedWhileCommandWasRunning() = runBlocking {
    val discovered = discoveredCatalog()
    val confirmed = discovered.copy(lastLocalPollAtEpochMillis = 6L)
    val observer = FakeLanNetworkObserver(LanNetworkObservation.Available(NETWORK))
    val controlCoordinator = BlockingLocalControlCoordinator()
    val viewModel =
      AppViewModel(
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

    viewModel.submitControl(toggleIntent(true))
    withTimeout(5_000) { controlCoordinator.started.await() }
    observer.emit(
      LanNetworkObservation.Available(NETWORK.copy(networkHandle = NETWORK.networkHandle + 1))
    )
    withTimeout(5_000) {
      viewModel.state.first { it is AppUiState.Inventory && !it.isLanSnapshotCurrent }
    }

    controlCoordinator.result.complete(confirmed)

    val finalState =
      withTimeout(5_000) {
        viewModel.state.first {
          it is AppUiState.Inventory && it.catalog.lastLocalPollAtEpochMillis == 6L
        }
      }
        as AppUiState.Inventory
    assertTrue(finalState.control is LocalControlUiState.Unavailable)
    assertTrue(finalState.discovery is LanDiscoveryUiState.Error)
    assertTrue(!finalState.isLanSnapshotCurrent)
  }

  @Test
  fun failedScanCannotRestoreSnapshotInvalidatedWhileScanWasRunning() = runBlocking {
    val observer = FakeLanNetworkObserver(LanNetworkObservation.Available(NETWORK))
    val discoveryCoordinator = BlockingLanDiscoveryCoordinator()
    val viewModel =
      AppViewModel(
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
      LanNetworkObservation.Available(NETWORK.copy(networkHandle = NETWORK.networkHandle + 1))
    )
    withTimeout(5_000) {
      viewModel.state.first { it is AppUiState.Inventory && !it.isLanSnapshotCurrent }
    }
    discoveryCoordinator.result.completeExceptionally(
      LanDiscoveryException(
        code = "LAN_SCAN_FAILED",
        message = "The old network scan stopped.",
      )
    )

    val finalState =
      withTimeout(5_000) {
        viewModel.state.first {
          it is AppUiState.Inventory && it.discovery is LanDiscoveryUiState.Error
        }
      }
        as AppUiState.Inventory
    assertEquals(
      "LAN_NETWORK_CHANGED",
      (finalState.discovery as LanDiscoveryUiState.Error).code,
    )
    assertTrue(!finalState.isLanSnapshotCurrent)
    assertTrue(finalState.control is LocalControlUiState.Unavailable)
  }

  @Test
  fun foregroundOnVerifiedWifiUsesOnlyTheQuickRefreshCoordinator() = runBlocking {
    val refreshed = discoveredCatalog().copy(lastLocalPollAtEpochMillis = 6L)
    val observer = FakeLanNetworkObserver(LanNetworkObservation.Available(NETWORK))
    val discoveryCoordinator = FakeLanDiscoveryCoordinator()
    val knownCoordinator = FakeKnownDeviceRefreshCoordinator(refreshed)
    val viewModel =
      AppViewModel(
        catalogStore = FakeCatalogStore(discoveredCatalog()),
        lanDiscoveryCoordinator = discoveryCoordinator,
        localStatusCoordinator = FakeLocalStatusCoordinator(),
        localControlCoordinator = FakeLocalControlCoordinator(),
        lanNetworkObserver = observer,
        knownDeviceRefreshCoordinator = knownCoordinator,
        settingsStore = InMemoryAppSettingsStore(AppSettings(refreshWhenAppOpens = true)),
        elapsedRealtimeMillis = { 100_000L },
      )
    withTimeout(5_000) { viewModel.state.first { it is AppUiState.Inventory } }
    withTimeout(5_000) { viewModel.settingsState.first { it.isLoaded } }

    viewModel.onAppForegrounded()
    observer.emit(LanNetworkObservation.Available(NETWORK))

    val state =
      withTimeout(5_000) {
        viewModel.state.first {
          it is AppUiState.Inventory && it.discovery is LanDiscoveryUiState.Completed
        }
      }
        as AppUiState.Inventory
    assertEquals(6L, state.catalog.lastLocalPollAtEpochMillis)
    assertEquals(1, knownCoordinator.callCount)
    assertEquals(0, discoveryCoordinator.callCount)
    assertTrue(state.control is LocalControlUiState.Ready)
  }

  @Test
  fun disabledForegroundPreferenceLeavesBothRefreshPathsIdle() = runBlocking {
    val observer = FakeLanNetworkObserver(LanNetworkObservation.Available(NETWORK))
    val discoveryCoordinator = FakeLanDiscoveryCoordinator()
    val knownCoordinator = FakeKnownDeviceRefreshCoordinator()
    val viewModel =
      AppViewModel(
        catalogStore = FakeCatalogStore(discoveredCatalog()),
        lanDiscoveryCoordinator = discoveryCoordinator,
        localStatusCoordinator = FakeLocalStatusCoordinator(),
        localControlCoordinator = FakeLocalControlCoordinator(),
        lanNetworkObserver = observer,
        knownDeviceRefreshCoordinator = knownCoordinator,
        settingsStore = InMemoryAppSettingsStore(AppSettings(refreshWhenAppOpens = false)),
      )
    withTimeout(5_000) { viewModel.state.first { it is AppUiState.Inventory } }
    withTimeout(5_000) { viewModel.settingsState.first { it.isLoaded } }

    viewModel.onAppForegrounded()
    observer.emit(LanNetworkObservation.Available(NETWORK))
    delay(150)

    assertEquals(0, knownCoordinator.callCount)
    assertEquals(0, discoveryCoordinator.callCount)
  }

  @Test
  fun foregroundOnChangedWifiRunsFullDiscoveryInsteadOfSavedAddressPoll() = runBlocking {
    val changedNetwork = NETWORK.copy(networkHandle = 202L)
    val rediscovered = discoveredCatalog().copy(lastDiscoveryNetwork = changedNetwork)
    val observer = FakeLanNetworkObserver(LanNetworkObservation.Available(changedNetwork))
    val discoveryCoordinator =
      FakeLanDiscoveryCoordinator(
        result = rediscovered,
        network = changedNetwork,
      )
    val statusCoordinator = FakeLocalStatusCoordinator(rediscovered)
    val knownCoordinator = FakeKnownDeviceRefreshCoordinator()
    val viewModel =
      AppViewModel(
        catalogStore = FakeCatalogStore(discoveredCatalog()),
        lanDiscoveryCoordinator = discoveryCoordinator,
        localStatusCoordinator = statusCoordinator,
        localControlCoordinator = FakeLocalControlCoordinator(),
        lanNetworkObserver = observer,
        knownDeviceRefreshCoordinator = knownCoordinator,
        settingsStore = InMemoryAppSettingsStore(),
      )
    withTimeout(5_000) {
      viewModel.state.first { it is AppUiState.Inventory && !it.isLanSnapshotCurrent }
    }
    withTimeout(5_000) { viewModel.settingsState.first { it.isLoaded } }

    viewModel.onAppForegrounded()

    withTimeout(5_000) {
      viewModel.state.first {
        it is AppUiState.Inventory && it.discovery is LanDiscoveryUiState.Completed
      }
    }
    assertEquals(0, knownCoordinator.callCount)
    assertEquals(1, discoveryCoordinator.callCount)
    assertEquals(1, statusCoordinator.callCount)
  }

  @Test
  fun foregroundQuickRefreshFallsBackWhenNetworkResolverRejectsTheSnapshot() = runBlocking {
    val observer = FakeLanNetworkObserver(LanNetworkObservation.Available(NETWORK))
    val discoveryCoordinator = FakeLanDiscoveryCoordinator(discoveredCatalog())
    val knownCoordinator =
      FakeKnownDeviceRefreshCoordinator(
        error =
          LocalStatusException(
            code = "LOCAL_REFRESH_DISCOVERY_REQUIRED",
            message = "Find devices again.",
          )
      )
    val viewModel =
      AppViewModel(
        catalogStore = FakeCatalogStore(discoveredCatalog()),
        lanDiscoveryCoordinator = discoveryCoordinator,
        localStatusCoordinator = FakeLocalStatusCoordinator(discoveredCatalog()),
        localControlCoordinator = FakeLocalControlCoordinator(),
        lanNetworkObserver = observer,
        knownDeviceRefreshCoordinator = knownCoordinator,
        settingsStore = InMemoryAppSettingsStore(),
      )
    withTimeout(5_000) { viewModel.state.first { it is AppUiState.Inventory } }
    withTimeout(5_000) { viewModel.settingsState.first { it.isLoaded } }

    viewModel.onAppForegrounded()
    observer.emit(LanNetworkObservation.Available(NETWORK))

    withTimeout(5_000) {
      viewModel.state.first {
        it is AppUiState.Inventory && it.discovery is LanDiscoveryUiState.Completed
      }
    }
    assertEquals(1, knownCoordinator.callCount)
    assertEquals(1, discoveryCoordinator.callCount)
  }

  private class FakeLanDiscoveryCoordinator(
    private val result: DeviceCatalog? = null,
    private val network: LanNetworkContext = NETWORK,
  ) : LanDiscoveryCoordinator {
    var callCount = 0

    override suspend fun discover(catalog: DeviceCatalog): LanDiscoveryOutcome {
      callCount += 1
      return LanDiscoveryOutcome(
        catalog = result ?: catalog,
        network = network,
      )
    }
  }

  private class FakeKnownDeviceRefreshCoordinator(
    private val result: DeviceCatalog? = null,
    private val network: LanNetworkContext = NETWORK,
    private val error: LocalStatusException? = null,
  ) : KnownDeviceRefreshCoordinator {
    var callCount = 0

    override suspend fun refresh(catalog: DeviceCatalog): KnownDeviceRefreshOutcome {
      callCount += 1
      error?.let { throw it }
      return KnownDeviceRefreshOutcome(
        catalog = result ?: catalog,
        network = network,
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

  private class FakeLocalStatusCoordinator(private val result: DeviceCatalog? = null) :
    LocalStatusCoordinator {
    var callCount = 0

    override suspend fun poll(
      catalog: DeviceCatalog,
      network: LanNetworkContext,
    ): DeviceCatalog {
      callCount += 1
      return result ?: catalog
    }
  }

  private class FakeLocalControlCoordinator(private val result: DeviceCatalog? = null) :
    LocalControlCoordinator {
    var callCount = 0
    var expectedNetwork: LanNetworkContext? = null
    var expectedDiscoveryAtEpochMillis: Long? = null
    var intent: DeviceIntent? = null

    override suspend fun execute(
      expectedNetwork: LanNetworkContext,
      expectedDiscoveryAtEpochMillis: Long,
      intent: DeviceIntent,
    ): DeviceCatalog {
      callCount += 1
      this.expectedNetwork = expectedNetwork
      this.expectedDiscoveryAtEpochMillis = expectedDiscoveryAtEpochMillis
      this.intent = intent
      return requireNotNull(result) { "No fake control result configured." }
    }
  }

  private class BlockingLocalControlCoordinator : LocalControlCoordinator {
    val started = CompletableDeferred<Unit>()
    val result = CompletableDeferred<DeviceCatalog>()

    override suspend fun execute(
      expectedNetwork: LanNetworkContext,
      expectedDiscoveryAtEpochMillis: Long,
      intent: DeviceIntent,
    ): DeviceCatalog {
      started.complete(Unit)
      return result.await()
    }
  }

  private class FakeLanNetworkObserver(initial: LanNetworkObservation) : LanNetworkObserver {
    private val observations =
      MutableSharedFlow<LanNetworkObservation>(replay = 1).apply { tryEmit(initial) }

    override fun observe(): Flow<LanNetworkObservation> = observations

    suspend fun emit(observation: LanNetworkObservation) {
      observations.emit(observation)
    }
  }

  private class FakeCatalogStore(private var catalog: DeviceCatalog? = null) : DeviceCatalogStore {
    var deleted = false

    override suspend fun load(): DeviceCatalog? = catalog

    override suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog =
      error("Cloud replacement is not used by this app-routing test.")

    override suspend fun mergeLanDiscovery(
      result: LanDiscoveryResult,
      network: LanNetworkContext,
    ): DeviceCatalog = error("LAN replacement is not used by this app-routing test.")

    override suspend fun mergeLocalPoll(result: LocalPollResult): DeviceCatalog =
      error("Local status is not used by this app-routing test.")

    override suspend fun deleteAll() {
      deleted = true
      catalog = null
    }
  }

  private companion object {
    val NETWORK =
      LanNetworkContext(
        interfaceName = "wlan0",
        localIpv4 = "192.168.10.5",
        prefixLength = 24,
        broadcastIpv4 = "192.168.10.255",
        networkHandle = 101L,
      )

    fun discoveredCatalog() =
      sampleCatalog()
        .copy(
          schemaVersion = 4,
          lastDiscoveryAtEpochMillis = 5L,
          lastDiscoveryNetwork = NETWORK,
          lanDevices =
            listOf(
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

    fun sampleCatalog() =
      DeviceCatalog(
        schemaVersion = 1,
        importedAtEpochMillis = 1L,
        region = TuyaCloudRegion.WESTERN_AMERICA,
        devices =
          listOf(
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

    fun savedCredentials() =
      StoredCloudCredentials(
        region = TuyaCloudRegion.CENTRAL_EUROPE,
        clientId = SensitiveString.of("known-good-client-id"),
        clientSecret = SensitiveString.of("known-good-secret"),
        savedAtEpochMillis = 1L,
      )

    fun toggleIntent(value: Boolean) =
      DeviceIntent.SetToggle(
        deviceId = "saved-device",
        capabilityId = CapabilityId("power"),
        value = value,
      )
  }
}
