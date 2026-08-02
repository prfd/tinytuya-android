package com.prfd.tinytuya.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prfd.tinytuya.data.lan.LanDiscoveryCoordinator
import com.prfd.tinytuya.data.lan.LanDiscoveryException
import com.prfd.tinytuya.data.lan.LanNetworkContext
import com.prfd.tinytuya.data.lan.LocalControlCoordinator
import com.prfd.tinytuya.data.lan.LocalControlException
import com.prfd.tinytuya.data.lan.LocalStatusCoordinator
import com.prfd.tinytuya.data.lan.LocalStatusException
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStorageException
import com.prfd.tinytuya.data.local.DeviceCatalogStore
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

class AppViewModel(
    private val catalogStore: DeviceCatalogStore,
    private val lanDiscoveryCoordinator: LanDiscoveryCoordinator,
    private val localStatusCoordinator: LocalStatusCoordinator,
    private val localControlCoordinator: LocalControlCoordinator,
) : ViewModel() {
    private val mutableState = MutableStateFlow<AppUiState>(AppUiState.Loading)
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var controlNetwork: LanNetworkContext? = null
    private var controlDiscoveryAtEpochMillis: Long? = null

    init {
        refreshCatalog()
    }

    fun refreshCatalog() {
        clearControlSession()
        mutableState.value = AppUiState.Loading
        viewModelScope.launch {
            try {
                val catalog = catalogStore.load()
                mutableState.value = if (catalog == null || catalog.devices.isEmpty()) {
                    AppUiState.Onboarding
                } else {
                    AppUiState.Inventory(catalog)
                }
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
        clearControlSession()
        mutableState.value = AppUiState.Onboarding
    }

    fun discoverLan() {
        val current = mutableState.value as? AppUiState.Inventory ?: return
        if (
            current.discovery is LanDiscoveryUiState.Scanning ||
            current.discovery is LanDiscoveryUiState.ReadingStatus ||
            current.control is LocalControlUiState.Sending
        ) return
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
                controlNetwork = discovery.network
                controlDiscoveryAtEpochMillis = latestCatalog.lastDiscoveryAtEpochMillis
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = LanDiscoveryUiState.ReadingStatus,
                    control = LocalControlUiState.Unavailable,
                )
                readingStatus = true
                latestCatalog = localStatusCoordinator.poll(
                    catalog = latestCatalog,
                    network = discovery.network,
                )
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = LanDiscoveryUiState.Completed,
                    control = LocalControlUiState.Ready,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: LanDiscoveryException) {
                mutableState.value = current.copy(
                    discovery = LanDiscoveryUiState.Error(
                        code = error.code,
                        message = error.message ?: "Local Tuya discovery could not be completed.",
                    ),
                    control = LocalControlUiState.Unavailable,
                )
            } catch (error: LocalStatusException) {
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = LanDiscoveryUiState.Error(
                        code = error.code,
                        message = error.message ?: "Local device status could not be read.",
                    ),
                    control = LocalControlUiState.Ready,
                )
            } catch (error: DeviceCatalogStorageException) {
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = LanDiscoveryUiState.Error(
                        code = error.code,
                        message = error.message ?: "The discovery result could not be stored safely.",
                    ),
                    control = if (readingStatus) {
                        LocalControlUiState.Ready
                    } else {
                        LocalControlUiState.Unavailable
                    },
                )
            } catch (_: Exception) {
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = LanDiscoveryUiState.Error(
                        code = if (readingStatus) "LOCAL_POLL_FAILED" else "LAN_SCAN_FAILED",
                        message = if (readingStatus) {
                            "Local device status could not be read."
                        } else {
                            "Local Tuya discovery could not be completed."
                        },
                    ),
                    control = if (readingStatus) {
                        LocalControlUiState.Ready
                    } else {
                        LocalControlUiState.Unavailable
                    },
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
        if (controlDiscoveryAtEpochMillis != current.catalog.lastDiscoveryAtEpochMillis) return

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
                mutableState.value = current.copy(
                    catalog = updated,
                    control = LocalControlUiState.Confirmed(deviceId, dataPointId, value),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: LocalControlException) {
                mutableState.value = current.copy(
                    catalog = error.updatedCatalog ?: current.catalog,
                    control = LocalControlUiState.Error(
                        deviceId = deviceId,
                        dataPointId = dataPointId,
                        code = error.code,
                        message = error.message ?: "The local command could not be confirmed safely.",
                    ),
                )
            } catch (error: DeviceCatalogStorageException) {
                mutableState.value = current.copy(
                    control = LocalControlUiState.Error(
                        deviceId = deviceId,
                        dataPointId = dataPointId,
                        code = error.code,
                        message = error.message ?: "The confirmed state could not be stored safely.",
                    ),
                )
            } catch (_: Exception) {
                mutableState.value = current.copy(
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
        clearControlSession()
        mutableState.value = AppUiState.Loading
        viewModelScope.launch {
            try {
                catalogStore.deleteAll()
                mutableState.value = AppUiState.Onboarding
            } catch (error: CancellationException) {
                throw error
            } catch (error: DeviceCatalogStorageException) {
                mutableState.value = AppUiState.Recovery(
                    code = error.code,
                    message = error.message ?: "The local device data could not be deleted.",
                )
            } catch (_: Exception) {
                mutableState.value = AppUiState.Recovery(
                    code = "CATALOG_DELETE_FAILED",
                    message = "The local device data could not be deleted.",
                )
            }
        }
    }

    private fun clearControlSession() {
        controlNetwork = null
        controlDiscoveryAtEpochMillis = null
    }

    companion object {
        fun factory(
            catalogStore: DeviceCatalogStore,
            lanDiscoveryCoordinator: LanDiscoveryCoordinator,
            localStatusCoordinator: LocalStatusCoordinator,
            localControlCoordinator: LocalControlCoordinator,
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
                    ) as T
                }
            }
    }
}
