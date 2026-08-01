package com.prfd.tinytuya.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prfd.tinytuya.data.lan.LanDiscoveryCoordinator
import com.prfd.tinytuya.data.lan.LanDiscoveryException
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
    ) : LanDiscoveryUiState
}

class AppViewModel(
    private val catalogStore: DeviceCatalogStore,
    private val lanDiscoveryCoordinator: LanDiscoveryCoordinator,
    private val localStatusCoordinator: LocalStatusCoordinator,
) : ViewModel() {
    private val mutableState = MutableStateFlow<AppUiState>(AppUiState.Loading)
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    init {
        refreshCatalog()
    }

    fun refreshCatalog() {
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
        mutableState.value = AppUiState.Onboarding
    }

    fun discoverLan() {
        val current = mutableState.value as? AppUiState.Inventory ?: return
        if (
            current.discovery is LanDiscoveryUiState.Scanning ||
            current.discovery is LanDiscoveryUiState.ReadingStatus
        ) return
        mutableState.value = current.copy(discovery = LanDiscoveryUiState.Scanning)
        viewModelScope.launch {
            var latestCatalog = current.catalog
            var readingStatus = false
            try {
                val discovery = lanDiscoveryCoordinator.discover(current.catalog)
                latestCatalog = discovery.catalog
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = LanDiscoveryUiState.ReadingStatus,
                )
                readingStatus = true
                latestCatalog = localStatusCoordinator.poll(
                    catalog = latestCatalog,
                    network = discovery.network,
                )
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = LanDiscoveryUiState.Completed,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: LanDiscoveryException) {
                mutableState.value = current.copy(
                    discovery = LanDiscoveryUiState.Error(
                        code = error.code,
                        message = error.message ?: "Local Tuya discovery could not be completed.",
                    )
                )
            } catch (error: LocalStatusException) {
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = LanDiscoveryUiState.Error(
                        code = error.code,
                        message = error.message ?: "Local device status could not be read.",
                    ),
                )
            } catch (error: DeviceCatalogStorageException) {
                mutableState.value = AppUiState.Inventory(
                    catalog = latestCatalog,
                    discovery = LanDiscoveryUiState.Error(
                        code = error.code,
                        message = error.message ?: "The discovery result could not be stored safely.",
                    ),
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
                )
            }
        }
    }

    fun deleteAllLocalData() {
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

    companion object {
        fun factory(
            catalogStore: DeviceCatalogStore,
            lanDiscoveryCoordinator: LanDiscoveryCoordinator,
            localStatusCoordinator: LocalStatusCoordinator,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AppViewModel::class.java))
                    return AppViewModel(
                        catalogStore,
                        lanDiscoveryCoordinator,
                        localStatusCoordinator,
                    ) as T
                }
            }
    }
}
