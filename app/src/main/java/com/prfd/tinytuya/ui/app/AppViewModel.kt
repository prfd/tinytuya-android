package com.prfd.tinytuya.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

    data class Inventory(val catalog: DeviceCatalog) : AppUiState

    data class Recovery(
        val code: String,
        val message: String,
    ) : AppUiState
}

class AppViewModel(
    private val catalogStore: DeviceCatalogStore,
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
        fun factory(catalogStore: DeviceCatalogStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AppViewModel::class.java))
                    return AppViewModel(catalogStore) as T
                }
            }
    }
}
