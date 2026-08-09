package com.prfd.tinytuya.ui.app

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prfd.tinytuya.data.lan.KnownDeviceRefreshCoordinator
import com.prfd.tinytuya.data.lan.LanDiscoveryCoordinator
import com.prfd.tinytuya.data.lan.LanDiscoveryException
import com.prfd.tinytuya.data.lan.LanNetworkContext
import com.prfd.tinytuya.data.lan.LanNetworkObservation
import com.prfd.tinytuya.data.lan.LanNetworkObserver
import com.prfd.tinytuya.data.lan.LocalControlCoordinator
import com.prfd.tinytuya.data.lan.LocalControlException
import com.prfd.tinytuya.data.lan.LocalRefreshDiagnostics
import com.prfd.tinytuya.data.lan.LocalRefreshMode
import com.prfd.tinytuya.data.lan.LocalRefreshTrigger
import com.prfd.tinytuya.data.lan.LocalStatusCoordinator
import com.prfd.tinytuya.data.lan.LocalStatusException
import com.prfd.tinytuya.data.lan.NoOpLanNetworkObserver
import com.prfd.tinytuya.data.lan.UnavailableKnownDeviceRefreshCoordinator
import com.prfd.tinytuya.data.lan.hasCurrentKnownStatusTargets
import com.prfd.tinytuya.data.lan.hasPreviouslyMatchedStatusTargets
import com.prfd.tinytuya.data.local.AppSettingsStorageException
import com.prfd.tinytuya.data.local.AppSettingsStore
import com.prfd.tinytuya.data.local.CloudCredentialStorageException
import com.prfd.tinytuya.data.local.CloudCredentialStore
import com.prfd.tinytuya.data.local.CloudCredentialSummary
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStorageException
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.local.InMemoryAppSettingsStore
import com.prfd.tinytuya.data.local.InMemoryCloudCredentialStore
import com.prfd.tinytuya.data.local.InventoryDisplayMode
import com.prfd.tinytuya.data.python.PythonBridgeException
import com.prfd.tinytuya.data.python.PythonRuntimeHealth
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.ui.DeviceControlUiState as LocalControlUiState
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AppUiState {
  data object Loading : AppUiState

  data object Onboarding : AppUiState

  data class Inventory(
    val catalog: DeviceCatalog,
    val discovery: LanDiscoveryUiState = LanDiscoveryUiState.Idle,
    val control: LocalControlUiState = LocalControlUiState.Unavailable,
    val isLanSnapshotCurrent: Boolean = true,
  ) : AppUiState

  data class Recovery(
    val code: String,
    val message: String,
  ) : AppUiState
}

sealed interface LanDiscoveryUiState {
  data object Idle : LanDiscoveryUiState

  data object Scanning : LanDiscoveryUiState

  data object ReadingStatus : LanDiscoveryUiState

  data object Completed : LanDiscoveryUiState

  data class Error(
    val code: String,
    val message: String,
    val phase: LocalRefreshPhase = LocalRefreshPhase.DISCOVERY,
  ) : LanDiscoveryUiState
}

enum class LocalRefreshPhase {
  DISCOVERY,
  STATUS,
}

data class AppSettingsUiState(
  val refreshWhenAppOpens: Boolean = true,
  val inventoryDisplayMode: InventoryDisplayMode = InventoryDisplayMode.COMPACT,
  val isLoaded: Boolean = false,
  val isSaving: Boolean = false,
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val cloudAccount: CloudAccountUiState = CloudAccountUiState.Loading,
  val tinyTuyaHealth: TinyTuyaHealthUiState = TinyTuyaHealthUiState.Loading,
)

sealed interface TinyTuyaHealthUiState {
  data object Loading : TinyTuyaHealthUiState

  data object Unavailable : TinyTuyaHealthUiState

  data class Ready(val health: PythonRuntimeHealth) : TinyTuyaHealthUiState

  data class Error(
    val code: String,
    val message: String,
  ) : TinyTuyaHealthUiState
}

sealed interface CloudAccountUiState {
  data object Loading : CloudAccountUiState

  data object Missing : CloudAccountUiState

  data class Saved(val summary: CloudCredentialSummary) : CloudAccountUiState

  data object Forgetting : CloudAccountUiState

  data class Recovery(
    val code: String,
    val message: String,
  ) : CloudAccountUiState
}

class AppViewModel(
  private val catalogStore: DeviceCatalogStore,
  private val lanDiscoveryCoordinator: LanDiscoveryCoordinator,
  private val localStatusCoordinator: LocalStatusCoordinator,
  private val localControlCoordinator: LocalControlCoordinator,
  private val lanNetworkObserver: LanNetworkObserver = NoOpLanNetworkObserver,
  private val knownDeviceRefreshCoordinator: KnownDeviceRefreshCoordinator =
    UnavailableKnownDeviceRefreshCoordinator,
  private val settingsStore: AppSettingsStore = InMemoryAppSettingsStore(),
  private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
  private val credentialStore: CloudCredentialStore = InMemoryCloudCredentialStore(),
  private val pythonHealthCheck: (suspend () -> PythonRuntimeHealth)? = null,
) : ViewModel() {
  private val mutableState = MutableStateFlow<AppUiState>(AppUiState.Loading)
  val state: StateFlow<AppUiState> = mutableState.asStateFlow()
  private val mutableSettingsState = MutableStateFlow(AppSettingsUiState())
  val settingsState: StateFlow<AppSettingsUiState> = mutableSettingsState.asStateFlow()
  private var controlNetwork: LanNetworkContext? = null
  private var controlDiscoveryAtEpochMillis: Long? = null
  private var controlSessionVersion = 0L
  private var latestNetworkObservation: LanNetworkObservation? = null
  private var foregroundRefreshPending = false
  private var lastLocalRefreshStartedAtMillis: Long? = null
  private var cloudAccountOperationVersion = 0L
  private var pythonHealthOperationVersion = 0L

  init {
    observeLanNetwork()
    loadSettings()
    refreshCatalog()
  }

  fun refreshCatalog() {
    refreshCatalog(discoverAfterLoad = false)
  }

  fun refreshCatalogAfterCloudImport() {
    refreshCatalog(discoverAfterLoad = true)
  }

  private fun refreshCatalog(discoverAfterLoad: Boolean) {
    refreshCloudAccount()
    clearControlSession()
    mutableState.value = AppUiState.Loading
    viewModelScope.launch {
      try {
        val catalog = catalogStore.load()
        val destination =
          if (catalog == null || catalog.devices.isEmpty()) {
            AppUiState.Onboarding
          } else {
            inventoryFromCatalog(catalog)
          }
        mutableState.value = destination
        if (discoverAfterLoad && destination is AppUiState.Inventory) {
          discoverLan(trigger = LocalRefreshTrigger.POST_IMPORT)
        } else {
          maybeStartForegroundRefresh()
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: DeviceCatalogStorageException) {
        mutableState.value =
          AppUiState.Recovery(
            code = error.code,
            message = error.message ?: "Encrypted device storage is unavailable.",
          )
      } catch (_: Exception) {
        mutableState.value =
          AppUiState.Recovery(
            code = "CATALOG_READ_FAILED",
            message = "The saved device catalog could not be opened safely.",
          )
      }
    }
  }

  fun showOnboarding() {
    foregroundRefreshPending = false
    clearControlSession()
    mutableState.value = AppUiState.Onboarding
  }

  fun onAppForegrounded() {
    foregroundRefreshPending = true
    maybeStartForegroundRefresh()
  }

  fun checkPythonHealth() {
    val healthCheck = pythonHealthCheck
    if (healthCheck == null) {
      mutableSettingsState.value =
        mutableSettingsState.value.copy(tinyTuyaHealth = TinyTuyaHealthUiState.Unavailable)
      return
    }

    val operationVersion = ++pythonHealthOperationVersion
    mutableSettingsState.value =
      mutableSettingsState.value.copy(tinyTuyaHealth = TinyTuyaHealthUiState.Loading)
    viewModelScope.launch {
      try {
        val health = healthCheck()
        if (operationVersion != pythonHealthOperationVersion) return@launch
        mutableSettingsState.value =
          mutableSettingsState.value.copy(tinyTuyaHealth = TinyTuyaHealthUiState.Ready(health))
      } catch (error: CancellationException) {
        throw error
      } catch (error: PythonBridgeException) {
        if (operationVersion != pythonHealthOperationVersion) return@launch
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            tinyTuyaHealth =
              TinyTuyaHealthUiState.Error(
                code = error.code,
                message =
                  error.message ?: "The embedded TinyTuya runtime could not be initialized.",
              )
          )
      } catch (_: Exception) {
        if (operationVersion != pythonHealthOperationVersion) return@launch
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            tinyTuyaHealth =
              TinyTuyaHealthUiState.Error(
                code = "BRIDGE_HEALTH_FAILED",
                message = "The embedded TinyTuya runtime could not be initialized.",
              )
          )
      }
    }
  }

  fun setRefreshWhenAppOpens(enabled: Boolean) {
    val current = mutableSettingsState.value
    if (!current.isLoaded || current.isSaving || current.refreshWhenAppOpens == enabled) return
    if (!enabled) foregroundRefreshPending = false
    mutableSettingsState.value =
      current.copy(
        refreshWhenAppOpens = enabled,
        isSaving = true,
        errorCode = null,
        errorMessage = null,
      )
    viewModelScope.launch {
      try {
        val saved = settingsStore.setRefreshWhenAppOpens(enabled)
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            refreshWhenAppOpens = saved.refreshWhenAppOpens,
            isLoaded = true,
            isSaving = false,
            errorCode = null,
            errorMessage = null,
          )
      } catch (error: CancellationException) {
        throw error
      } catch (error: AppSettingsStorageException) {
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            refreshWhenAppOpens = current.refreshWhenAppOpens,
            isSaving = false,
            errorCode = error.code,
            errorMessage = error.message ?: "The refresh preference could not be saved.",
          )
      } catch (_: Exception) {
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            refreshWhenAppOpens = current.refreshWhenAppOpens,
            isSaving = false,
            errorCode = "SETTINGS_WRITE_FAILED",
            errorMessage = "The refresh preference could not be saved.",
          )
      }
    }
  }

  fun setInventoryDisplayMode(mode: InventoryDisplayMode) {
    val current = mutableSettingsState.value
    if (!current.isLoaded || current.isSaving || current.inventoryDisplayMode == mode) return
    mutableSettingsState.value =
      current.copy(
        inventoryDisplayMode = mode,
        isSaving = true,
        errorCode = null,
        errorMessage = null,
      )
    viewModelScope.launch {
      try {
        val saved = settingsStore.setInventoryDisplayMode(mode)
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            refreshWhenAppOpens = saved.refreshWhenAppOpens,
            inventoryDisplayMode = saved.inventoryDisplayMode,
            isLoaded = true,
            isSaving = false,
            errorCode = null,
            errorMessage = null,
          )
      } catch (error: CancellationException) {
        throw error
      } catch (error: AppSettingsStorageException) {
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            inventoryDisplayMode = current.inventoryDisplayMode,
            isSaving = false,
            errorCode = error.code,
            errorMessage = error.message ?: "The inventory view preference could not be saved.",
          )
      } catch (_: Exception) {
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            inventoryDisplayMode = current.inventoryDisplayMode,
            isSaving = false,
            errorCode = "SETTINGS_WRITE_FAILED",
            errorMessage = "The inventory view preference could not be saved.",
          )
      }
    }
  }

  fun dismissSettingsError() {
    mutableSettingsState.value =
      mutableSettingsState.value.copy(
        errorCode = null,
        errorMessage = null,
      )
  }

  fun forgetCloudCredentials() {
    val current = mutableSettingsState.value.cloudAccount
    if (
      current is CloudAccountUiState.Loading ||
        current is CloudAccountUiState.Forgetting ||
        current is CloudAccountUiState.Missing
    )
      return
    val operationVersion = ++cloudAccountOperationVersion
    mutableSettingsState.value =
      mutableSettingsState.value.copy(cloudAccount = CloudAccountUiState.Forgetting)
    viewModelScope.launch {
      try {
        credentialStore.deleteAll()
        if (operationVersion != cloudAccountOperationVersion) return@launch
        mutableSettingsState.value =
          mutableSettingsState.value.copy(cloudAccount = CloudAccountUiState.Missing)
      } catch (error: CancellationException) {
        throw error
      } catch (error: CloudCredentialStorageException) {
        if (operationVersion != cloudAccountOperationVersion) return@launch
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            cloudAccount =
              CloudAccountUiState.Recovery(
                code = error.code,
                message = error.message ?: "The saved Tuya Cloud credentials could not be deleted.",
              )
          )
      } catch (_: Exception) {
        if (operationVersion != cloudAccountOperationVersion) return@launch
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            cloudAccount =
              CloudAccountUiState.Recovery(
                code = "CREDENTIAL_VAULT_DELETE_FAILED",
                message = "The saved Tuya Cloud credentials could not be deleted.",
              )
          )
      }
    }
  }

  fun refreshKnownDevices() {
    refreshKnownDevices(trigger = LocalRefreshTrigger.MANUAL)
  }

  private fun refreshKnownDevices(trigger: LocalRefreshTrigger) {
    val current =
      mutableState.value as? AppUiState.Inventory
        ?: run {
          LocalRefreshDiagnostics.refreshSkipped(
            trigger = trigger,
            mode = LocalRefreshMode.STATUS,
            reason = "invalid_destination",
          )
          return
        }
    val skipReason =
      when {
        current.discovery is LanDiscoveryUiState.Scanning ||
          current.discovery is LanDiscoveryUiState.ReadingStatus ||
          current.control is LocalControlUiState.Sending -> "busy"
        !current.isLanSnapshotCurrent -> "snapshot_not_current"
        !current.catalog.hasCurrentKnownStatusTargets() -> "no_current_targets"
        else -> null
      }
    if (skipReason != null) {
      LocalRefreshDiagnostics.refreshSkipped(
        trigger = trigger,
        mode = LocalRefreshMode.STATUS,
        reason = skipReason,
      )
      return
    }
    markLocalRefreshStarted()
    val refreshTrace = LocalRefreshDiagnostics.refreshStarted(trigger, LocalRefreshMode.STATUS)
    clearControlSession()
    mutableState.value =
      current.copy(
        discovery = LanDiscoveryUiState.ReadingStatus,
        control = LocalControlUiState.Unavailable,
      )
    viewModelScope.launch {
      try {
        val outcome = knownDeviceRefreshCoordinator.refresh(current.catalog)
        if (!observationMatches(outcome.network)) {
          refreshTrace.failed(LAN_NETWORK_CHANGED)
          publishChangedNetwork(outcome.catalog)
          return@launch
        }
        openControlSession(
          network = outcome.network,
          discoveryAtEpochMillis = outcome.catalog.lastDiscoveryAtEpochMillis,
        )
        mutableState.value =
          AppUiState.Inventory(
            catalog = outcome.catalog,
            discovery = LanDiscoveryUiState.Completed,
            control = LocalControlUiState.Ready,
            isLanSnapshotCurrent = true,
          )
        refreshTrace.completed()
      } catch (error: CancellationException) {
        refreshTrace.failed("CANCELLED")
        throw error
      } catch (error: LocalStatusException) {
        refreshTrace.failed(error.code)
        val snapshotCurrent =
          if (error.code == LOCAL_REFRESH_DISCOVERY_REQUIRED) {
            false
          } else {
            catalogIsCurrentNow(
              catalog = current.catalog,
              fallback = current.isLanSnapshotCurrent,
            )
          }
        mutableState.value =
          current.copy(
            discovery =
              if (snapshotCurrent) {
                LanDiscoveryUiState.Error(
                  code = error.code,
                  message = error.message ?: "Local device status could not be read.",
                  phase = LocalRefreshPhase.STATUS,
                )
              } else {
                changedNetworkError()
              },
            control = LocalControlUiState.Unavailable,
            isLanSnapshotCurrent = snapshotCurrent,
          )
      } catch (error: DeviceCatalogStorageException) {
        refreshTrace.failed(error.code)
        mutableState.value =
          current.copy(
            discovery =
              LanDiscoveryUiState.Error(
                code = error.code,
                message = error.message ?: "Local device status could not be saved.",
                phase = LocalRefreshPhase.STATUS,
              ),
            control = LocalControlUiState.Unavailable,
          )
      } catch (_: Exception) {
        refreshTrace.failed("LOCAL_POLL_FAILED")
        mutableState.value =
          current.copy(
            discovery =
              LanDiscoveryUiState.Error(
                code = "LOCAL_POLL_FAILED",
                message = "Local device status could not be read.",
                phase = LocalRefreshPhase.STATUS,
              ),
            control = LocalControlUiState.Unavailable,
          )
      }
    }
  }

  fun discoverLan() {
    discoverLan(trigger = LocalRefreshTrigger.MANUAL)
  }

  private fun discoverLan(trigger: LocalRefreshTrigger) {
    val current =
      mutableState.value as? AppUiState.Inventory
        ?: run {
          LocalRefreshDiagnostics.refreshSkipped(
            trigger = trigger,
            mode = LocalRefreshMode.DISCOVERY,
            reason = "invalid_destination",
          )
          return
        }
    if (
      current.discovery is LanDiscoveryUiState.Scanning ||
        current.discovery is LanDiscoveryUiState.ReadingStatus ||
        current.control is LocalControlUiState.Sending
    ) {
      LocalRefreshDiagnostics.refreshSkipped(
        trigger = trigger,
        mode = LocalRefreshMode.DISCOVERY,
        reason = "busy",
      )
      return
    }
    markLocalRefreshStarted()
    val refreshTrace = LocalRefreshDiagnostics.refreshStarted(trigger, LocalRefreshMode.DISCOVERY)
    clearControlSession()
    mutableState.value =
      current.copy(
        discovery = LanDiscoveryUiState.Scanning,
        control = LocalControlUiState.Unavailable,
      )
    viewModelScope.launch {
      var latestCatalog = current.catalog
      var readingStatus = false
      try {
        val discovery = lanDiscoveryCoordinator.discover(current.catalog)
        latestCatalog = discovery.catalog
        if (!observationMatches(discovery.network)) {
          refreshTrace.failed(LAN_NETWORK_CHANGED)
          publishChangedNetwork(latestCatalog)
          return@launch
        }
        openControlSession(
          network = discovery.network,
          discoveryAtEpochMillis = latestCatalog.lastDiscoveryAtEpochMillis,
        )
        mutableState.value =
          AppUiState.Inventory(
            catalog = latestCatalog,
            discovery = LanDiscoveryUiState.ReadingStatus,
            control = LocalControlUiState.Unavailable,
            isLanSnapshotCurrent = true,
          )
        readingStatus = true
        latestCatalog =
          localStatusCoordinator.poll(
            catalog = latestCatalog,
            network = discovery.network,
          )
        if (!controlSessionMatches(discovery.network, latestCatalog)) {
          refreshTrace.failed(LAN_NETWORK_CHANGED, LocalRefreshMode.STATUS)
          publishChangedNetwork(latestCatalog)
          return@launch
        }
        mutableState.value =
          AppUiState.Inventory(
            catalog = latestCatalog,
            discovery = LanDiscoveryUiState.Completed,
            control = LocalControlUiState.Ready,
            isLanSnapshotCurrent = true,
          )
        refreshTrace.completed()
      } catch (error: CancellationException) {
        val phase = if (readingStatus) LocalRefreshMode.STATUS else LocalRefreshMode.DISCOVERY
        refreshTrace.failed("CANCELLED", phase)
        throw error
      } catch (error: LanDiscoveryException) {
        refreshTrace.failed(error.code)
        val snapshotCurrent =
          catalogIsCurrentNow(
            catalog = current.catalog,
            fallback = current.isLanSnapshotCurrent,
          )
        mutableState.value =
          current.copy(
            discovery =
              if (snapshotCurrent) {
                LanDiscoveryUiState.Error(
                  code = error.code,
                  message = error.message ?: "Local Tuya discovery could not be completed.",
                )
              } else {
                changedNetworkError()
              },
            control = LocalControlUiState.Unavailable,
            isLanSnapshotCurrent = snapshotCurrent,
          )
      } catch (error: LocalStatusException) {
        if (!activeDiscoverySessionIsCurrent(latestCatalog)) {
          refreshTrace.failed(LAN_NETWORK_CHANGED, LocalRefreshMode.STATUS)
          publishChangedNetwork(latestCatalog)
          return@launch
        }
        refreshTrace.failed(error.code, LocalRefreshMode.STATUS)
        mutableState.value =
          AppUiState.Inventory(
            catalog = latestCatalog,
            discovery =
              LanDiscoveryUiState.Error(
                code = error.code,
                message = error.message ?: "Local device status could not be read.",
                phase = LocalRefreshPhase.STATUS,
              ),
            control = LocalControlUiState.Ready,
            isLanSnapshotCurrent = true,
          )
      } catch (error: DeviceCatalogStorageException) {
        if (readingStatus && !activeDiscoverySessionIsCurrent(latestCatalog)) {
          refreshTrace.failed(LAN_NETWORK_CHANGED, LocalRefreshMode.STATUS)
          publishChangedNetwork(latestCatalog)
          return@launch
        }
        val phase = if (readingStatus) LocalRefreshMode.STATUS else LocalRefreshMode.DISCOVERY
        refreshTrace.failed(error.code, phase)
        val snapshotCurrent =
          if (readingStatus) {
            true
          } else {
            catalogIsCurrentNow(
              catalog = current.catalog,
              fallback = current.isLanSnapshotCurrent,
            )
          }
        mutableState.value =
          AppUiState.Inventory(
            catalog = latestCatalog,
            discovery =
              if (snapshotCurrent) {
                LanDiscoveryUiState.Error(
                  code = error.code,
                  message =
                    error.message
                      ?: if (readingStatus) {
                        "Local device status could not be saved."
                      } else {
                        "The discovery result could not be stored safely."
                      },
                  phase =
                    if (readingStatus) {
                      LocalRefreshPhase.STATUS
                    } else {
                      LocalRefreshPhase.DISCOVERY
                    },
                )
              } else {
                changedNetworkError()
              },
            control =
              if (readingStatus) {
                LocalControlUiState.Ready
              } else {
                LocalControlUiState.Unavailable
              },
            isLanSnapshotCurrent = snapshotCurrent,
          )
      } catch (_: Exception) {
        if (readingStatus && !activeDiscoverySessionIsCurrent(latestCatalog)) {
          refreshTrace.failed(LAN_NETWORK_CHANGED, LocalRefreshMode.STATUS)
          publishChangedNetwork(latestCatalog)
          return@launch
        }
        val phase = if (readingStatus) LocalRefreshMode.STATUS else LocalRefreshMode.DISCOVERY
        val code = if (readingStatus) "LOCAL_POLL_FAILED" else "LAN_SCAN_FAILED"
        refreshTrace.failed(code, phase)
        val snapshotCurrent =
          if (readingStatus) {
            true
          } else {
            catalogIsCurrentNow(
              catalog = current.catalog,
              fallback = current.isLanSnapshotCurrent,
            )
          }
        mutableState.value =
          AppUiState.Inventory(
            catalog = latestCatalog,
            discovery =
              if (snapshotCurrent) {
                LanDiscoveryUiState.Error(
                  code =
                    if (readingStatus) {
                      "LOCAL_POLL_FAILED"
                    } else {
                      "LAN_SCAN_FAILED"
                    },
                  message =
                    if (readingStatus) {
                      "Local device status could not be read."
                    } else {
                      "Local Tuya discovery could not be completed."
                    },
                  phase =
                    if (readingStatus) {
                      LocalRefreshPhase.STATUS
                    } else {
                      LocalRefreshPhase.DISCOVERY
                    },
                )
              } else {
                changedNetworkError()
              },
            control =
              if (readingStatus) {
                LocalControlUiState.Ready
              } else {
                LocalControlUiState.Unavailable
              },
            isLanSnapshotCurrent = snapshotCurrent,
          )
      }
    }
  }

  fun submitControl(intent: DeviceIntent) {
    val current = mutableState.value as? AppUiState.Inventory ?: return
    if (
      current.discovery is LanDiscoveryUiState.Scanning ||
        current.discovery is LanDiscoveryUiState.ReadingStatus ||
        current.control is LocalControlUiState.Unavailable ||
        current.control is LocalControlUiState.Sending
    )
      return
    val network = controlNetwork ?: return
    val discoveryAt = controlDiscoveryAtEpochMillis ?: return
    if (
      discoveryAt != current.catalog.lastDiscoveryAtEpochMillis ||
        !current.isLanSnapshotCurrent ||
        !observationMatches(network)
    )
      return
    val sessionVersion = controlSessionVersion

    mutableState.value = current.copy(control = LocalControlUiState.Sending(intent))
    viewModelScope.launch {
      try {
        val updated =
          localControlCoordinator.execute(
            expectedNetwork = network,
            expectedDiscoveryAtEpochMillis = discoveryAt,
            intent = intent,
          )
        val live =
          activeControlInventory(
            sessionVersion = sessionVersion,
            network = network,
            discoveryAtEpochMillis = discoveryAt,
          )
        if (live == null) {
          publishStaleControlResult(updated)
          return@launch
        }
        mutableState.value =
          live.copy(
            catalog = updated,
            control = LocalControlUiState.Confirmed(intent),
          )
      } catch (error: CancellationException) {
        throw error
      } catch (error: LocalControlException) {
        val observedCatalog = error.updatedCatalog
        val live =
          activeControlInventory(
            sessionVersion = sessionVersion,
            network = network,
            discoveryAtEpochMillis = discoveryAt,
          )
        if (live == null) {
          publishStaleControlResult(observedCatalog)
          return@launch
        }
        mutableState.value =
          live.copy(
            catalog = observedCatalog ?: live.catalog,
            control =
              LocalControlUiState.Error(
                intent = intent,
                code = error.code,
                message = error.message ?: "The local command could not be confirmed safely.",
              ),
          )
      } catch (error: DeviceCatalogStorageException) {
        val live =
          activeControlInventory(
            sessionVersion = sessionVersion,
            network = network,
            discoveryAtEpochMillis = discoveryAt,
          ) ?: return@launch
        mutableState.value =
          live.copy(
            control =
              LocalControlUiState.Error(
                intent = intent,
                code = error.code,
                message = error.message ?: "The confirmed state could not be stored safely.",
              )
          )
      } catch (_: Exception) {
        val live =
          activeControlInventory(
            sessionVersion = sessionVersion,
            network = network,
            discoveryAtEpochMillis = discoveryAt,
          ) ?: return@launch
        mutableState.value =
          live.copy(
            control =
              LocalControlUiState.Error(
                intent = intent,
                code = "LOCAL_CONTROL_FAILED",
                message = "The local command could not be confirmed safely.",
              )
          )
      }
    }
  }

  fun deleteAllLocalData() {
    foregroundRefreshPending = false
    cloudAccountOperationVersion += 1
    clearControlSession()
    mutableState.value = AppUiState.Loading
    viewModelScope.launch {
      var firstFailure: Exception? = null
      try {
        credentialStore.deleteAll()
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        firstFailure = error
      }
      try {
        catalogStore.deleteAll()
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        if (firstFailure == null) firstFailure = error
      }
      try {
        settingsStore.deleteAll()
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        if (firstFailure == null) firstFailure = error
      }

      val failure = firstFailure
      if (failure == null) {
        mutableSettingsState.value =
          AppSettingsUiState(
            refreshWhenAppOpens = true,
            isLoaded = true,
            cloudAccount = CloudAccountUiState.Missing,
          )
        mutableState.value = AppUiState.Onboarding
      } else if (failure is DeviceCatalogStorageException) {
        mutableState.value =
          AppUiState.Recovery(
            code = failure.code,
            message = failure.message ?: "The local device data could not be deleted.",
          )
      } else if (failure is AppSettingsStorageException) {
        mutableState.value =
          AppUiState.Recovery(
            code = failure.code,
            message = failure.message ?: "The local app settings could not be deleted.",
          )
      } else if (failure is CloudCredentialStorageException) {
        mutableState.value =
          AppUiState.Recovery(
            code = failure.code,
            message = failure.message ?: "The saved Tuya Cloud credentials could not be deleted.",
          )
      } else {
        mutableState.value =
          AppUiState.Recovery(
            code = "LOCAL_DATA_DELETE_FAILED",
            message = "All local app data could not be deleted.",
          )
      }
    }
  }

  private fun loadSettings() {
    viewModelScope.launch {
      try {
        val settings = settingsStore.load()
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            refreshWhenAppOpens = settings.refreshWhenAppOpens,
            inventoryDisplayMode = settings.inventoryDisplayMode,
            isLoaded = true,
            isSaving = false,
            errorCode = null,
            errorMessage = null,
          )
      } catch (error: CancellationException) {
        throw error
      } catch (error: AppSettingsStorageException) {
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            refreshWhenAppOpens = false,
            inventoryDisplayMode = InventoryDisplayMode.COMPACT,
            isLoaded = true,
            isSaving = false,
            errorCode = error.code,
            errorMessage = error.message ?: "App settings could not be read safely.",
          )
      } catch (_: Exception) {
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            refreshWhenAppOpens = false,
            inventoryDisplayMode = InventoryDisplayMode.COMPACT,
            isLoaded = true,
            isSaving = false,
            errorCode = "SETTINGS_READ_FAILED",
            errorMessage = "App settings could not be read safely.",
          )
      }
      maybeStartForegroundRefresh()
    }
  }

  private fun refreshCloudAccount() {
    val operationVersion = ++cloudAccountOperationVersion
    mutableSettingsState.value =
      mutableSettingsState.value.copy(cloudAccount = CloudAccountUiState.Loading)
    viewModelScope.launch {
      try {
        val summary = credentialStore.loadSummary()
        if (operationVersion != cloudAccountOperationVersion) return@launch
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            cloudAccount =
              if (summary == null) {
                CloudAccountUiState.Missing
              } else {
                CloudAccountUiState.Saved(summary)
              }
          )
      } catch (error: CancellationException) {
        throw error
      } catch (error: CloudCredentialStorageException) {
        if (operationVersion != cloudAccountOperationVersion) return@launch
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            cloudAccount =
              CloudAccountUiState.Recovery(
                code = error.code,
                message =
                  error.message ?: "The saved Tuya Cloud credentials could not be opened safely.",
              )
          )
      } catch (_: Exception) {
        if (operationVersion != cloudAccountOperationVersion) return@launch
        mutableSettingsState.value =
          mutableSettingsState.value.copy(
            cloudAccount =
              CloudAccountUiState.Recovery(
                code = "CREDENTIAL_VAULT_READ_FAILED",
                message = "The saved Tuya Cloud credentials could not be opened safely.",
              )
          )
      }
    }
  }

  private fun maybeStartForegroundRefresh() {
    if (!foregroundRefreshPending) return
    val settings = mutableSettingsState.value
    if (!settings.isLoaded) return
    if (!settings.refreshWhenAppOpens) {
      foregroundRefreshPending = false
      LocalRefreshDiagnostics.refreshSkipped(
        trigger = LocalRefreshTrigger.FOREGROUND,
        mode = LocalRefreshMode.STATUS,
        reason = "disabled",
      )
      return
    }
    val current =
      when (val destination = mutableState.value) {
        AppUiState.Loading -> return
        is AppUiState.Inventory -> destination
        AppUiState.Onboarding,
        is AppUiState.Recovery -> {
          foregroundRefreshPending = false
          LocalRefreshDiagnostics.refreshSkipped(
            trigger = LocalRefreshTrigger.FOREGROUND,
            mode = LocalRefreshMode.STATUS,
            reason = "invalid_destination",
          )
          return
        }
      }
    val observation = latestNetworkObservation ?: return
    if (observation is LanNetworkObservation.Unavailable) return
    if (
      current.discovery is LanDiscoveryUiState.Scanning ||
        current.discovery is LanDiscoveryUiState.ReadingStatus ||
        current.control is LocalControlUiState.Sending
    ) {
      foregroundRefreshPending = false
      LocalRefreshDiagnostics.refreshSkipped(
        trigger = LocalRefreshTrigger.FOREGROUND,
        mode = LocalRefreshMode.STATUS,
        reason = "busy",
      )
      return
    }
    if (!current.catalog.hasPreviouslyMatchedStatusTargets()) {
      foregroundRefreshPending = false
      LocalRefreshDiagnostics.refreshSkipped(
        trigger = LocalRefreshTrigger.FOREGROUND,
        mode = LocalRefreshMode.STATUS,
        reason = "no_previously_matched_targets",
      )
      return
    }
    if (!current.isLanSnapshotCurrent || !current.catalog.hasCurrentKnownStatusTargets()) {
      foregroundRefreshPending = false
      LocalRefreshDiagnostics.refreshSkipped(
        trigger = LocalRefreshTrigger.FOREGROUND,
        mode = LocalRefreshMode.STATUS,
        reason =
          if (current.isLanSnapshotCurrent) {
            "no_current_targets"
          } else {
            "snapshot_not_current"
          },
      )
      return
    }
    val now = elapsedRealtimeMillis()
    val lastStartedAt = lastLocalRefreshStartedAtMillis
    if (
      lastStartedAt != null &&
        now >= lastStartedAt &&
        now - lastStartedAt < FOREGROUND_REFRESH_COOLDOWN_MILLIS
    ) {
      foregroundRefreshPending = false
      LocalRefreshDiagnostics.refreshSkipped(
        trigger = LocalRefreshTrigger.FOREGROUND,
        mode = LocalRefreshMode.STATUS,
        reason = "cooldown",
        remainingMillis = FOREGROUND_REFRESH_COOLDOWN_MILLIS - (now - lastStartedAt),
      )
      return
    }

    refreshKnownDevices(trigger = LocalRefreshTrigger.FOREGROUND)
  }

  private fun markLocalRefreshStarted() {
    foregroundRefreshPending = false
    lastLocalRefreshStartedAtMillis = elapsedRealtimeMillis()
  }

  private fun clearControlSession() {
    controlSessionVersion += 1
    controlNetwork = null
    controlDiscoveryAtEpochMillis = null
  }

  private fun openControlSession(
    network: LanNetworkContext,
    discoveryAtEpochMillis: Long?,
  ) {
    controlSessionVersion += 1
    controlNetwork = network
    controlDiscoveryAtEpochMillis = discoveryAtEpochMillis
  }

  private fun observeLanNetwork() {
    viewModelScope.launch {
      lanNetworkObserver.observe().collect { observation ->
        latestNetworkObservation = observation
        val current = mutableState.value as? AppUiState.Inventory ?: return@collect
        val snapshotCurrent = catalogMatchesObservation(current.catalog, observation)
        if (!snapshotCurrent) {
          clearControlSession()
          val busy =
            current.discovery is LanDiscoveryUiState.Scanning ||
              current.discovery is LanDiscoveryUiState.ReadingStatus
          mutableState.value =
            current.copy(
              discovery = if (busy) current.discovery else changedNetworkError(),
              control = LocalControlUiState.Unavailable,
              isLanSnapshotCurrent = false,
            )
        } else if (!current.isLanSnapshotCurrent) {
          val priorError = current.discovery as? LanDiscoveryUiState.Error
          mutableState.value =
            current.copy(
              discovery =
                if (priorError?.code == LAN_NETWORK_CHANGED) {
                  LanDiscoveryUiState.Idle
                } else {
                  current.discovery
                },
              control = LocalControlUiState.Unavailable,
              isLanSnapshotCurrent = true,
            )
        }
        maybeStartForegroundRefresh()
      }
    }
  }

  private fun inventoryFromCatalog(catalog: DeviceCatalog): AppUiState.Inventory {
    val snapshotCurrent =
      latestNetworkObservation?.let { observation ->
        catalogMatchesObservation(catalog, observation)
      } ?: true
    return AppUiState.Inventory(
      catalog = catalog,
      discovery =
        if (catalog.lastDiscoveryAtEpochMillis != null && !snapshotCurrent) {
          changedNetworkError()
        } else {
          LanDiscoveryUiState.Idle
        },
      control = LocalControlUiState.Unavailable,
      isLanSnapshotCurrent = snapshotCurrent,
    )
  }

  private fun catalogMatchesObservation(
    catalog: DeviceCatalog,
    observation: LanNetworkObservation,
  ): Boolean {
    if (catalog.lastDiscoveryAtEpochMillis == null) return true
    val expected = catalog.lastDiscoveryNetwork ?: return false
    return observation is LanNetworkObservation.Available && observation.network == expected
  }

  private fun catalogIsCurrentNow(
    catalog: DeviceCatalog,
    fallback: Boolean,
  ): Boolean =
    latestNetworkObservation?.let { observation -> catalogMatchesObservation(catalog, observation) }
      ?: fallback

  private fun observationMatches(network: LanNetworkContext): Boolean =
    when (val observation = latestNetworkObservation) {
      null -> true
      is LanNetworkObservation.Available -> observation.network == network
      LanNetworkObservation.Unavailable -> false
    }

  private fun controlSessionMatches(
    network: LanNetworkContext,
    catalog: DeviceCatalog,
  ): Boolean =
    controlNetwork == network &&
      controlDiscoveryAtEpochMillis == catalog.lastDiscoveryAtEpochMillis &&
      observationMatches(network)

  private fun activeDiscoverySessionIsCurrent(catalog: DeviceCatalog): Boolean {
    val network = controlNetwork ?: return false
    return controlSessionMatches(network, catalog)
  }

  private fun activeControlInventory(
    sessionVersion: Long,
    network: LanNetworkContext,
    discoveryAtEpochMillis: Long,
  ): AppUiState.Inventory? {
    val live = mutableState.value as? AppUiState.Inventory ?: return null
    return live.takeIf {
      controlSessionVersion == sessionVersion &&
        controlNetwork == network &&
        controlDiscoveryAtEpochMillis == discoveryAtEpochMillis &&
        it.catalog.lastDiscoveryAtEpochMillis == discoveryAtEpochMillis &&
        it.isLanSnapshotCurrent &&
        observationMatches(network)
    }
  }

  private fun publishChangedNetwork(catalog: DeviceCatalog) {
    clearControlSession()
    val live = mutableState.value as? AppUiState.Inventory ?: return
    mutableState.value =
      live.copy(
        catalog = catalog,
        discovery = changedNetworkError(),
        control = LocalControlUiState.Unavailable,
        isLanSnapshotCurrent = false,
      )
  }

  private fun publishStaleControlResult(catalog: DeviceCatalog?) {
    val live = mutableState.value as? AppUiState.Inventory ?: return
    mutableState.value =
      live.copy(
        catalog = catalog ?: live.catalog,
        control = LocalControlUiState.Unavailable,
      )
  }

  private fun changedNetworkError() =
    LanDiscoveryUiState.Error(
      code = LAN_NETWORK_CHANGED,
      message =
        "The active Wi-Fi no longer matches the last local refresh. Find devices again before using saved addresses.",
    )

  companion object {
    fun factory(
      catalogStore: DeviceCatalogStore,
      lanDiscoveryCoordinator: LanDiscoveryCoordinator,
      localStatusCoordinator: LocalStatusCoordinator,
      localControlCoordinator: LocalControlCoordinator,
      lanNetworkObserver: LanNetworkObserver = NoOpLanNetworkObserver,
      knownDeviceRefreshCoordinator: KnownDeviceRefreshCoordinator =
        UnavailableKnownDeviceRefreshCoordinator,
      settingsStore: AppSettingsStore = InMemoryAppSettingsStore(),
      credentialStore: CloudCredentialStore = InMemoryCloudCredentialStore(),
      pythonHealthCheck: (suspend () -> PythonRuntimeHealth)? = null,
    ): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          require(modelClass.isAssignableFrom(AppViewModel::class.java))
          return AppViewModel(
            catalogStore,
            lanDiscoveryCoordinator,
            localStatusCoordinator,
            localControlCoordinator,
            lanNetworkObserver,
            knownDeviceRefreshCoordinator,
            settingsStore,
            credentialStore = credentialStore,
            pythonHealthCheck = pythonHealthCheck,
          )
            as T
        }
      }

    private const val LAN_NETWORK_CHANGED = "LAN_NETWORK_CHANGED"
    private const val LOCAL_REFRESH_DISCOVERY_REQUIRED = "LOCAL_REFRESH_DISCOVERY_REQUIRED"
    private const val FOREGROUND_REFRESH_COOLDOWN_MILLIS = 30_000L
  }
}
