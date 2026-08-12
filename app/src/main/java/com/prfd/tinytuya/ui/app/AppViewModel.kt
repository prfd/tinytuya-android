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
import com.prfd.tinytuya.data.local.AppSettingsStore
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStorageException
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.local.InMemoryAppSettingsStore
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
) : ViewModel() {
  private val mutableState = MutableStateFlow<AppUiState>(AppUiState.Loading)
  val state: StateFlow<AppUiState> = mutableState.asStateFlow()
  private var controlNetwork: LanNetworkContext? = null
  private var controlDiscoveryAtEpochMillis: Long? = null
  private var controlSessionVersion = 0L
  private var latestNetworkObservation: LanNetworkObservation? = null
  private var foregroundRefreshPending = false
  private var lastLocalRefreshStartedAtMillis: Long? = null

  init {
    observeLanNetwork()
    refreshCatalog()
  }

  fun refreshCatalog() {
    refreshCatalog(discoverAfterLoad = false)
  }

  fun refreshCatalogAfterCloudImport() {
    refreshCatalog(discoverAfterLoad = true)
  }

  private fun refreshCatalog(discoverAfterLoad: Boolean) {
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
    viewModelScope.launch { maybeStartForegroundRefresh() }
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
    clearControlSession()
    mutableState.value = AppUiState.Loading
    viewModelScope.launch {
      try {
        catalogStore.deleteAll()
        mutableState.value = AppUiState.Onboarding
      } catch (error: CancellationException) {
        throw error
      } catch (error: DeviceCatalogStorageException) {
        mutableState.value =
          AppUiState.Recovery(
            code = error.code,
            message = error.message ?: "The local device data could not be deleted.",
          )
      } catch (_: Exception) {
        mutableState.value =
          AppUiState.Recovery(
            code = "LOCAL_DATA_DELETE_FAILED",
            message = "All local app data could not be deleted.",
          )
      }
    }
  }

  private suspend fun maybeStartForegroundRefresh() {
    if (!foregroundRefreshPending) return
    val enabled =
      try {
        settingsStore.load().refreshWhenAppOpens
      } catch (_: Exception) {
        foregroundRefreshPending = false
        LocalRefreshDiagnostics.refreshSkipped(
          trigger = LocalRefreshTrigger.FOREGROUND,
          mode = LocalRefreshMode.STATUS,
          reason = "settings_not_loaded",
        )
        return
      }
    if (!enabled) {
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
          )
            as T
        }
      }

    private const val LAN_NETWORK_CHANGED = "LAN_NETWORK_CHANGED"
    private const val LOCAL_REFRESH_DISCOVERY_REQUIRED = "LOCAL_REFRESH_DISCOVERY_REQUIRED"
    private const val FOREGROUND_REFRESH_COOLDOWN_MILLIS = 30_000L
  }
}
