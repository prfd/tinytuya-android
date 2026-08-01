package com.prfd.tinytuya.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prfd.tinytuya.data.lan.LanDiscoveryCoordinator
import com.prfd.tinytuya.data.lan.LanDiscoveryException
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

    data object Completed : LanDiscoveryUiState

    data class Error(
        val code: String,
        val message: String,
    ) : LanDiscoveryUiState
}

class AppViewModel(
    private val catalogStore: DeviceCatalogStore,
    private val lanDiscoveryCoordinator: LanDiscoveryCoordinator,
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
        if (current.discovery is LanDiscoveryUiState.Scanning) return
        mutableState.value = current.copy(discovery = LanDiscoveryUiState.Scanning)
        viewModelScope.launch {
            try {
                val updatedCatalog = lanDiscoveryCoordinator.discover(current.catalog)
                mutableState.value = AppUiState.Inventory(
                    catalog = updatedCatalog,
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
            } catch (error: DeviceCatalogStorageException) {
                mutableState.value = current.copy(
                    discovery = LanDiscoveryUiState.Error(
                        code = error.code,
                        message = error.message ?: "The discovery result could not be stored safely.",
                    )
                )
            } catch (_: Exception) {
                mutableState.value = current.copy(
                    discovery = LanDiscoveryUiState.Error(
                        code = "LAN_SCAN_FAILED",
                        message = "Local Tuya discovery could not be completed.",
                    )
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
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AppViewModel::class.java))
                    return AppViewModel(catalogStore, lanDiscoveryCoordinator) as T
                }
            }
    }
}
