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
import com.prfd.tinytuya.data.lan.LocalStatusCoordinator
import com.prfd.tinytuya.data.lan.LocalStatusException
import com.prfd.tinytuya.data.lan.NoOpLanNetworkObserver
import com.prfd.tinytuya.data.lan.UnavailableKnownDeviceRefreshCoordinator
import com.prfd.tinytuya.data.lan.hasCurrentKnownStatusTargets
import com.prfd.tinytuya.data.lan.hasPreviouslyMatchedStatusTargets
import com.prfd.tinytuya.data.local.AppSettingsStorageException
import com.prfd.tinytuya.data.local.AppSettingsStore
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStorageException
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.local.InMemoryAppSettingsStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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

sealed interface LocalControlUiState {
    data object Unavailable : LocalControlUiState

    data object Ready : LocalControlUiState

    data class Sending(
        val deviceId: String,
        val dataPointId: String,
        val value: Boolean,
    ) : LocalControlUiState {
        override fun toString(): String =
            "Sending(deviceId=[REDACTED], dataPointId=$dataPointId, value=$value)"
    }

    data class Confirmed(
        val deviceId: String,
        val dataPointId: String,
        val value: Boolean,
    ) : LocalControlUiState {
        override fun toString(): String =
            "Confirmed(deviceId=[REDACTED], dataPointId=$dataPointId, value=$value)"
    }

    data class Error(
        val deviceId: String,
        val dataPointId: String,
        val code: String,
        val message: String,
    ) : LocalControlUiState {
        override fun toString(): String =
            "Error(deviceId=[REDACTED], dataPointId=$dataPointId, code=$code, message=$message)"
    }
}

sealed interface LanDiscoveryUiState {
    data object Idle : LanDiscoveryUiState

    data object Scanning : LanDiscoveryUiState

    data object ReadingStatus : LanDiscoveryUiState

    data object Completed : LanDiscoveryUiState

    data class Error(
        val code: String,
        val message: String,
    ) : LanDiscoveryUiState
}

data class AppSettingsUiState(
    val refreshWhenAppOpens: Boolean = true,
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

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
    private val mutableSettingsState = MutableStateFlow(AppSettingsUiState())
    val settingsState: StateFlow<AppSettingsUiState> = mutableSettingsState.asStateFlow()
    private var controlNetwork: LanNetworkContext? = null
    private var controlDiscoveryAtEpochMillis: Long? = null
    private var controlSessionVersion = 0L
    private var latestNetworkObservation: LanNetworkObservation? = null
    private var foregroundRefreshPending = false
    private var lastLocalRefreshStartedAtMillis: Long? = null

    init {
        observeLanNetwork()
        loadSettings()
        refreshCatalog()
    }

    fun refreshCatalog() {
        clearControlSession()
        mutableState.value = AppUiState.Loading
        viewModelScope.launch {
            try {
                val catalog = catalogStore.load()
                val destination = if (catalog == null || catalog.devices.isEmpty()) {
                    AppUiState.Onboarding
                } else {
                    inventoryFromCatalog(catalog)
                }
                mutableState.value = destination
                maybeStartForegroundRefresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: DeviceCatalogStorageException) {
                mutableState.value = AppUiState.Recovery(
                    code = error.code,
                    message = error.message ?: "Encrypted device storage is unavailable.",
                )
            } catch (_: Exception) {
                mutableState.value = AppUiState.Recovery(
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

    fun setRefreshWhenAppOpens(enabled: Boolean) {
        val current = mutableSettingsState.value
        if (!current.isLoaded || current.isSaving || current.refreshWhenAppOpens == enabled) return
        if (!enabled) foregroundRefreshPending = false
        mutableSettingsState.value = current.copy(
            refreshWhenAppOpens = enabled,
            isSaving = true,
            errorCode = null,
            errorMessage = null,
        )
        viewModelScope.launch {
            try {
                val saved = settingsStore.setRefreshWhenAppOpens(enabled)
                mutableSettingsState.value = AppSettingsUiState(
                    refreshWhenAppOpens = saved.refreshWhenAppOpens,
                    isLoaded = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppSettingsStorageException) {
                mutableSettingsState.value = current.copy(
                    isSaving = false,
                    errorCode = error.code,
                    errorMessage = error.message ?: "The refresh preference could not be saved.",
                )
            } catch (_: Exception) {
                mutableSettingsState.value = current.copy(
                    isSaving = false,
                    errorCode = "SETTINGS_WRITE_FAILED",
                    errorMessage = "The refresh preference could not be saved.",
                )
            }
        }
    }

    fun dismissSettingsError() {
        mutableSettingsState.value = mutableSettingsState.value.copy(
            errorCode = null,
            errorMessage = null,
        )
    }

    fun refreshKnownDevices() {
        refreshKnownDevices(fallbackToDiscovery = false)
    }

    private fun refreshKnownDevices(fallbackToDiscovery: Boolean) {
        val current = mutableState.value as? AppUiState.Inventory ?: return
        if (
            current.discovery is LanDiscoveryUiState.Scanning ||
            current.discovery is LanDiscoveryUiState.ReadingStatus ||
            current.control is LocalControlUiState.Sending ||
            !current.isLanSnapshotCurrent ||
            !current.catalog.hasCurrentKnownStatusTargets()
        ) return
        markLocalRefreshStarted()
        clearControlSession()
        mutableState.value = current.copy(
            discovery = LanDiscoveryUiState.ReadingStatus,
            control = LocalControlUiState.Unavailable,
        )
        viewModelScope.launch {
            try {
                val outcome = knownDeviceRefreshCoordinator.refresh(current.catalog)
                if (!observationMatches(outcome.network)) {
                    publishChangedNetwork(outcome.catalog)
                    return@launch
                }
                openControlSession(
                    network = outcome.network,
                    discoveryAtEpochMillis = outcome.catalog.lastDiscoveryAtEpochMillis,
                )
                mutableState.value = AppUiState.Inventory(
                    catalog = outcome.catalog,
                    discovery = LanDiscoveryUiState.Completed,
                    control = LocalControlUiState.Ready,
                    isLanSnapshotCurrent = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: LocalStatusException) {
                if (
                    fallbackToDiscovery &&
                    error.code == LOCAL_REFRESH_DISCOVERY_REQUIRED
                ) {
                    mutableState.value = current.copy(
                        discovery = LanDiscoveryUiState.Idle,
                        control = LocalControlUiState.Unavailable,
                        isLanSnapshotCurrent = false,
                    )
                    discoverLan()
                    return@launch
                }
                val snapshotCurrent = if (error.code == LOCAL_REFRESH_DISCOVERY_REQUIRED) {
                    false
                } else {
                    catalogIsCurrentNow(
                        catalog = current.catalog,
                        fallback = current.isLanSnapshotCurrent,
                    )
                }
                mutableState.value = current.copy(
                    discovery = if (snapshotCurrent) {
                        LanDiscoveryUiState.Error(
                            code = error.code,
                            message = error.message ?: "Local device status could not be read.",
                        )
                    } else {
                        changedNetworkError()
                    },
                    control = LocalControlUiState.Unavailable,
                    isLanSnapshotCurrent = snapshotCurrent,
                )
            } catch (error: DeviceCatalogStorageException) {
                mutableState.value = current.copy(
                    discovery = LanDiscoveryUiState.Error(
                        code = error.code,
                        message = error.message ?: "Local device status could not be saved.",
                    ),
                    control = LocalControlUiState.Unavailable,
                )
            } catch (_: Exception) {
                mutableState.value = current.copy(
                    discovery = LanDiscoveryUiState.Error(
                        code = "LOCAL_POLL_FAILED",
                        message = "Local device status could not be read.",
                    ),
                    control = LocalControlUiState.Unavailable,
                )
            }
        }
    }

    fun discoverLan() {
        val current = mutableState.value as? AppUiState.Inventory ?: return
        if (
            current.discovery is LanDiscoveryUiState.Scanning ||
            current.discovery is LanDiscoveryUiState.ReadingStatus ||
            current.control is LocalControlUiState.Sending
        ) return
        markLocalRefreshStarted()
        clearControlSession()
        mutableState.value = current.copy(
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
                    publishChangedNetwork(latestCatalog)
                    return@launch
                }
                openControlSession(
                    network = discovery.network,
                    discoveryAtEpochMillis = latestCatalog.lastDiscoveryAtEpochMillis,
                )
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = LanDiscoveryUiState.ReadingStatus,
                    control = LocalControlUiState.Unavailable,
                    isLanSnapshotCurrent = true,
                )
                readingStatus = true
                latestCatalog = localStatusCoordinator.poll(
                    catalog = latestCatalog,
                    network = discovery.network,
                )
                if (!controlSessionMatches(discovery.network, latestCatalog)) {
                    publishChangedNetwork(latestCatalog)
                    return@launch
                }
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = LanDiscoveryUiState.Completed,
                    control = LocalControlUiState.Ready,
                    isLanSnapshotCurrent = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: LanDiscoveryException) {
                val snapshotCurrent = catalogIsCurrentNow(
                    catalog = current.catalog,
                    fallback = current.isLanSnapshotCurrent,
                )
                mutableState.value = current.copy(
                    discovery = if (snapshotCurrent) {
                        LanDiscoveryUiState.Error(
                            code = error.code,
                            message = error.message
                                ?: "Local Tuya discovery could not be completed.",
                        )
                    } else {
                        changedNetworkError()
                    },
                    control = LocalControlUiState.Unavailable,
                    isLanSnapshotCurrent = snapshotCurrent,
                )
            } catch (error: LocalStatusException) {
                if (!activeDiscoverySessionIsCurrent(latestCatalog)) {
                    publishChangedNetwork(latestCatalog)
                    return@launch
                }
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = LanDiscoveryUiState.Error(
                        code = error.code,
                        message = error.message ?: "Local device status could not be read.",
                    ),
                    control = LocalControlUiState.Ready,
                    isLanSnapshotCurrent = true,
                )
            } catch (error: DeviceCatalogStorageException) {
                if (readingStatus && !activeDiscoverySessionIsCurrent(latestCatalog)) {
                    publishChangedNetwork(latestCatalog)
                    return@launch
                }
                val snapshotCurrent = if (readingStatus) {
                    true
                } else {
                    catalogIsCurrentNow(
                        catalog = current.catalog,
                        fallback = current.isLanSnapshotCurrent,
                    )
                }
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = if (snapshotCurrent) {
                        LanDiscoveryUiState.Error(
                            code = error.code,
                            message = error.message
                                ?: "The discovery result could not be stored safely.",
                        )
                    } else {
                        changedNetworkError()
                    },
                    control = if (readingStatus) {
                        LocalControlUiState.Ready
                    } else {
                        LocalControlUiState.Unavailable
                    },
                    isLanSnapshotCurrent = snapshotCurrent,
                )
            } catch (_: Exception) {
                if (readingStatus && !activeDiscoverySessionIsCurrent(latestCatalog)) {
                    publishChangedNetwork(latestCatalog)
                    return@launch
                }
                val snapshotCurrent = if (readingStatus) {
                    true
                } else {
                    catalogIsCurrentNow(
                        catalog = current.catalog,
                        fallback = current.isLanSnapshotCurrent,
                    )
                }
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = if (snapshotCurrent) {
                        LanDiscoveryUiState.Error(
                            code = if (readingStatus) {
                                "LOCAL_POLL_FAILED"
                            } else {
                                "LAN_SCAN_FAILED"
                            },
                            message = if (readingStatus) {
                                "Local device status could not be read."
                            } else {
                                "Local Tuya discovery could not be completed."
                            },
                        )
                    } else {
                        changedNetworkError()
                    },
                    control = if (readingStatus) {
                        LocalControlUiState.Ready
                    } else {
                        LocalControlUiState.Unavailable
                    },
                    isLanSnapshotCurrent = snapshotCurrent,
                )
            }
        }
    }

    fun setBooleanControl(
        deviceId: String,
        dataPointId: String,
        value: Boolean,
    ) {
        val current = mutableState.value as? AppUiState.Inventory ?: return
        if (
            current.discovery is LanDiscoveryUiState.Scanning ||
            current.discovery is LanDiscoveryUiState.ReadingStatus ||
            current.control is LocalControlUiState.Unavailable ||
            current.control is LocalControlUiState.Sending
        ) return
        val network = controlNetwork ?: return
        val discoveryAt = controlDiscoveryAtEpochMillis ?: return
        if (
            discoveryAt != current.catalog.lastDiscoveryAtEpochMillis ||
            !current.isLanSnapshotCurrent ||
            !observationMatches(network)
        ) return
        val sessionVersion = controlSessionVersion

        mutableState.value = current.copy(
            control = LocalControlUiState.Sending(deviceId, dataPointId, value)
        )
        viewModelScope.launch {
            try {
                val updated = localControlCoordinator.setBoolean(
                    catalog = current.catalog,
                    expectedNetwork = network,
                    deviceId = deviceId,
                    dataPointId = dataPointId,
                    value = value,
                )
                val live = activeControlInventory(
                    sessionVersion = sessionVersion,
                    network = network,
                    discoveryAtEpochMillis = discoveryAt,
                )
                if (live == null) {
                    publishStaleControlResult(updated)
                    return@launch
                }
                mutableState.value = live.copy(
                    catalog = updated,
                    control = LocalControlUiState.Confirmed(deviceId, dataPointId, value),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: LocalControlException) {
                val observedCatalog = error.updatedCatalog
                val live = activeControlInventory(
                    sessionVersion = sessionVersion,
                    network = network,
                    discoveryAtEpochMillis = discoveryAt,
                )
                if (live == null) {
                    publishStaleControlResult(observedCatalog)
                    return@launch
                }
                mutableState.value = live.copy(
                    catalog = observedCatalog ?: live.catalog,
                    control = LocalControlUiState.Error(
                        deviceId = deviceId,
                        dataPointId = dataPointId,
                        code = error.code,
                        message = error.message ?: "The local command could not be confirmed safely.",
                    ),
                )
            } catch (error: DeviceCatalogStorageException) {
                val live = activeControlInventory(
                    sessionVersion = sessionVersion,
                    network = network,
                    discoveryAtEpochMillis = discoveryAt,
                ) ?: return@launch
                mutableState.value = live.copy(
                    control = LocalControlUiState.Error(
                        deviceId = deviceId,
                        dataPointId = dataPointId,
                        code = error.code,
                        message = error.message ?: "The confirmed state could not be stored safely.",
                    ),
                )
            } catch (_: Exception) {
                val live = activeControlInventory(
                    sessionVersion = sessionVersion,
                    network = network,
                    discoveryAtEpochMillis = discoveryAt,
                ) ?: return@launch
                mutableState.value = live.copy(
                    control = LocalControlUiState.Error(
                        deviceId = deviceId,
                        dataPointId = dataPointId,
                        code = "LOCAL_CONTROL_FAILED",
                        message = "The local command could not be confirmed safely.",
                    ),
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
                settingsStore.deleteAll()
                mutableSettingsState.value = AppSettingsUiState(
                    refreshWhenAppOpens = true,
                    isLoaded = true,
                )
                mutableState.value = AppUiState.Onboarding
            } catch (error: CancellationException) {
                throw error
            } catch (error: DeviceCatalogStorageException) {
                mutableState.value = AppUiState.Recovery(
                    code = error.code,
                    message = error.message ?: "The local device data could not be deleted.",
                )
            } catch (error: AppSettingsStorageException) {
                mutableState.value = AppUiState.Recovery(
                    code = error.code,
                    message = error.message ?: "The local app settings could not be deleted.",
                )
            } catch (_: Exception) {
                mutableState.value = AppUiState.Recovery(
                    code = "CATALOG_DELETE_FAILED",
                    message = "The local device data could not be deleted.",
                )
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            try {
                val settings = settingsStore.load()
                mutableSettingsState.value = AppSettingsUiState(
                    refreshWhenAppOpens = settings.refreshWhenAppOpens,
                    isLoaded = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppSettingsStorageException) {
                mutableSettingsState.value = AppSettingsUiState(
                    refreshWhenAppOpens = false,
                    isLoaded = true,
                    errorCode = error.code,
                    errorMessage = error.message ?: "App settings could not be read safely.",
                )
            } catch (_: Exception) {
                mutableSettingsState.value = AppSettingsUiState(
                    refreshWhenAppOpens = false,
                    isLoaded = true,
                    errorCode = "SETTINGS_READ_FAILED",
                    errorMessage = "App settings could not be read safely.",
                )
            }
            maybeStartForegroundRefresh()
        }
    }

    private fun maybeStartForegroundRefresh() {
        if (!foregroundRefreshPending) return
        val settings = mutableSettingsState.value
        if (!settings.isLoaded) return
        if (!settings.refreshWhenAppOpens) {
            foregroundRefreshPending = false
            return
        }
        val current = when (val destination = mutableState.value) {
            AppUiState.Loading -> return
            is AppUiState.Inventory -> destination
            AppUiState.Onboarding,
            is AppUiState.Recovery -> {
                foregroundRefreshPending = false
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
            return
        }
        if (!current.catalog.hasPreviouslyMatchedStatusTargets()) {
            foregroundRefreshPending = false
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
            return
        }

        if (current.isLanSnapshotCurrent && current.catalog.hasCurrentKnownStatusTargets()) {
            refreshKnownDevices(fallbackToDiscovery = true)
        } else {
            discoverLan()
        }
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
                    val busy = current.discovery is LanDiscoveryUiState.Scanning ||
                        current.discovery is LanDiscoveryUiState.ReadingStatus
                    mutableState.value = current.copy(
                        discovery = if (busy) current.discovery else changedNetworkError(),
                        control = LocalControlUiState.Unavailable,
                        isLanSnapshotCurrent = false,
                    )
                } else if (!current.isLanSnapshotCurrent) {
                    val priorError = current.discovery as? LanDiscoveryUiState.Error
                    mutableState.value = current.copy(
                        discovery = if (priorError?.code == LAN_NETWORK_CHANGED) {
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
        val snapshotCurrent = latestNetworkObservation?.let { observation ->
            catalogMatchesObservation(catalog, observation)
        } ?: true
        return AppUiState.Inventory(
            catalog = catalog,
            discovery = if (catalog.lastDiscoveryAtEpochMillis != null && !snapshotCurrent) {
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
    ): Boolean = latestNetworkObservation?.let { observation ->
        catalogMatchesObservation(catalog, observation)
    } ?: fallback

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
        mutableState.value = live.copy(
            catalog = catalog,
            discovery = changedNetworkError(),
            control = LocalControlUiState.Unavailable,
            isLanSnapshotCurrent = false,
        )
    }

    private fun publishStaleControlResult(catalog: DeviceCatalog?) {
        val live = mutableState.value as? AppUiState.Inventory ?: return
        mutableState.value = live.copy(
            catalog = catalog ?: live.catalog,
            control = LocalControlUiState.Unavailable,
        )
    }

    private fun changedNetworkError() = LanDiscoveryUiState.Error(
        code = LAN_NETWORK_CHANGED,
        message = "The active Wi-Fi no longer matches the last local refresh. Find devices again before using saved addresses.",
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
                    ) as T
                }
            }

        private const val LAN_NETWORK_CHANGED = "LAN_NETWORK_CHANGED"
        private const val LOCAL_REFRESH_DISCOVERY_REQUIRED =
            "LOCAL_REFRESH_DISCOVERY_REQUIRED"
        private const val FOREGROUND_REFRESH_COOLDOWN_MILLIS = 30_000L
    }
}
