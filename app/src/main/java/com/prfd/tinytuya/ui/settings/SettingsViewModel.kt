package com.prfd.tinytuya.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prfd.tinytuya.data.local.AppSettingsStorageException
import com.prfd.tinytuya.data.local.AppSettingsStore
import com.prfd.tinytuya.data.local.CloudCredentialStorageException
import com.prfd.tinytuya.data.local.CloudCredentialStore
import com.prfd.tinytuya.data.local.CloudCredentialSummary
import com.prfd.tinytuya.data.local.InMemoryAppSettingsStore
import com.prfd.tinytuya.data.local.InMemoryCloudCredentialStore
import com.prfd.tinytuya.data.local.InventoryDisplayMode
import com.prfd.tinytuya.data.python.PythonBridgeException
import com.prfd.tinytuya.data.python.PythonRuntimeHealth
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

class SettingsViewModel(
  private val settingsStore: AppSettingsStore = InMemoryAppSettingsStore(),
  private val credentialStore: CloudCredentialStore = InMemoryCloudCredentialStore(),
  private val pythonHealthCheck: (suspend () -> PythonRuntimeHealth)? = null,
) : ViewModel() {
  private val mutableState = MutableStateFlow(AppSettingsUiState())
  val state: StateFlow<AppSettingsUiState> = mutableState.asStateFlow()
  private var cloudAccountOperationVersion = 0L
  private var pythonHealthOperationVersion = 0L

  init {
    loadSettings()
    refreshCloudAccount()
  }

  fun checkPythonHealth() {
    val healthCheck = pythonHealthCheck
    if (healthCheck == null) {
      mutableState.value =
        mutableState.value.copy(tinyTuyaHealth = TinyTuyaHealthUiState.Unavailable)
      return
    }

    val operationVersion = ++pythonHealthOperationVersion
    mutableState.value = mutableState.value.copy(tinyTuyaHealth = TinyTuyaHealthUiState.Loading)
    viewModelScope.launch {
      try {
        val health = healthCheck()
        if (operationVersion != pythonHealthOperationVersion) return@launch
        mutableState.value =
          mutableState.value.copy(tinyTuyaHealth = TinyTuyaHealthUiState.Ready(health))
      } catch (error: CancellationException) {
        throw error
      } catch (error: PythonBridgeException) {
        if (operationVersion != pythonHealthOperationVersion) return@launch
        mutableState.value =
          mutableState.value.copy(
            tinyTuyaHealth =
              TinyTuyaHealthUiState.Error(
                code = error.code,
                message =
                  error.message ?: "The embedded TinyTuya runtime could not be initialized.",
              )
          )
      } catch (_: Exception) {
        if (operationVersion != pythonHealthOperationVersion) return@launch
        mutableState.value =
          mutableState.value.copy(
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
    val current = mutableState.value
    if (!current.isLoaded || current.isSaving || current.refreshWhenAppOpens == enabled) return
    mutableState.value =
      current.copy(
        refreshWhenAppOpens = enabled,
        isSaving = true,
        errorCode = null,
        errorMessage = null,
      )
    viewModelScope.launch {
      try {
        val saved = settingsStore.setRefreshWhenAppOpens(enabled)
        mutableState.value =
          mutableState.value.copy(
            refreshWhenAppOpens = saved.refreshWhenAppOpens,
            isLoaded = true,
            isSaving = false,
            errorCode = null,
            errorMessage = null,
          )
      } catch (error: CancellationException) {
        throw error
      } catch (error: AppSettingsStorageException) {
        mutableState.value =
          mutableState.value.copy(
            refreshWhenAppOpens = current.refreshWhenAppOpens,
            isSaving = false,
            errorCode = error.code,
            errorMessage = error.message ?: "The refresh preference could not be saved.",
          )
      } catch (_: Exception) {
        mutableState.value =
          mutableState.value.copy(
            refreshWhenAppOpens = current.refreshWhenAppOpens,
            isSaving = false,
            errorCode = "SETTINGS_WRITE_FAILED",
            errorMessage = "The refresh preference could not be saved.",
          )
      }
    }
  }

  fun setInventoryDisplayMode(mode: InventoryDisplayMode) {
    val current = mutableState.value
    if (!current.isLoaded || current.isSaving || current.inventoryDisplayMode == mode) return
    mutableState.value =
      current.copy(
        inventoryDisplayMode = mode,
        isSaving = true,
        errorCode = null,
        errorMessage = null,
      )
    viewModelScope.launch {
      try {
        val saved = settingsStore.setInventoryDisplayMode(mode)
        mutableState.value =
          mutableState.value.copy(
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
        mutableState.value =
          mutableState.value.copy(
            inventoryDisplayMode = current.inventoryDisplayMode,
            isSaving = false,
            errorCode = error.code,
            errorMessage = error.message ?: "The inventory view preference could not be saved.",
          )
      } catch (_: Exception) {
        mutableState.value =
          mutableState.value.copy(
            inventoryDisplayMode = current.inventoryDisplayMode,
            isSaving = false,
            errorCode = "SETTINGS_WRITE_FAILED",
            errorMessage = "The inventory view preference could not be saved.",
          )
      }
    }
  }

  fun dismissSettingsError() {
    mutableState.value =
      mutableState.value.copy(
        errorCode = null,
        errorMessage = null,
      )
  }

  fun forgetCloudCredentials() {
    val current = mutableState.value.cloudAccount
    if (
      current is CloudAccountUiState.Loading ||
        current is CloudAccountUiState.Forgetting ||
        current is CloudAccountUiState.Missing
    )
      return
    val operationVersion = ++cloudAccountOperationVersion
    mutableState.value = mutableState.value.copy(cloudAccount = CloudAccountUiState.Forgetting)
    viewModelScope.launch {
      try {
        credentialStore.deleteAll()
        if (operationVersion != cloudAccountOperationVersion) return@launch
        mutableState.value = mutableState.value.copy(cloudAccount = CloudAccountUiState.Missing)
      } catch (error: CancellationException) {
        throw error
      } catch (error: CloudCredentialStorageException) {
        if (operationVersion != cloudAccountOperationVersion) return@launch
        mutableState.value =
          mutableState.value.copy(
            cloudAccount =
              CloudAccountUiState.Recovery(
                code = error.code,
                message = error.message ?: "The saved Tuya Cloud credentials could not be deleted.",
              )
          )
      } catch (_: Exception) {
        if (operationVersion != cloudAccountOperationVersion) return@launch
        mutableState.value =
          mutableState.value.copy(
            cloudAccount =
              CloudAccountUiState.Recovery(
                code = "CREDENTIAL_VAULT_DELETE_FAILED",
                message = "The saved Tuya Cloud credentials could not be deleted.",
              )
          )
      }
    }
  }

  fun refreshCloudAccount() {
    val operationVersion = ++cloudAccountOperationVersion
    mutableState.value = mutableState.value.copy(cloudAccount = CloudAccountUiState.Loading)
    viewModelScope.launch {
      try {
        val summary = credentialStore.loadSummary()
        if (operationVersion != cloudAccountOperationVersion) return@launch
        mutableState.value =
          mutableState.value.copy(
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
        mutableState.value =
          mutableState.value.copy(
            cloudAccount =
              CloudAccountUiState.Recovery(
                code = error.code,
                message =
                  error.message ?: "The saved Tuya Cloud credentials could not be opened safely.",
              )
          )
      } catch (_: Exception) {
        if (operationVersion != cloudAccountOperationVersion) return@launch
        mutableState.value =
          mutableState.value.copy(
            cloudAccount =
              CloudAccountUiState.Recovery(
                code = "CREDENTIAL_VAULT_READ_FAILED",
                message = "The saved Tuya Cloud credentials could not be opened safely.",
              )
          )
      }
    }
  }

  fun deleteAllSettingsData() {
    cloudAccountOperationVersion += 1
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
        settingsStore.deleteAll()
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        if (firstFailure == null) firstFailure = error
      }

      when (val failure = firstFailure) {
        null -> {
          mutableState.value =
            AppSettingsUiState(
              refreshWhenAppOpens = true,
              isLoaded = true,
              cloudAccount = CloudAccountUiState.Missing,
            )
        }
        is AppSettingsStorageException -> {
          mutableState.value =
            mutableState.value.copy(
              errorCode = failure.code,
              errorMessage = failure.message ?: "The local app settings could not be deleted.",
            )
        }

        is CloudCredentialStorageException -> {
          mutableState.value =
            mutableState.value.copy(
              errorCode = failure.code,
              errorMessage =
                failure.message ?: "The saved Tuya Cloud credentials could not be deleted.",
            )
        }

        else -> {
          mutableState.value =
            mutableState.value.copy(
              errorCode = "LOCAL_DATA_DELETE_FAILED",
              errorMessage = "All local app data could not be deleted.",
            )
        }
      }
    }
  }

  private fun loadSettings() {
    viewModelScope.launch {
      try {
        val settings = settingsStore.load()
        mutableState.value =
          mutableState.value.copy(
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
        mutableState.value =
          mutableState.value.copy(
            refreshWhenAppOpens = false,
            inventoryDisplayMode = InventoryDisplayMode.COMPACT,
            isLoaded = true,
            isSaving = false,
            errorCode = error.code,
            errorMessage = error.message ?: "App settings could not be read safely.",
          )
      } catch (_: Exception) {
        mutableState.value =
          mutableState.value.copy(
            refreshWhenAppOpens = false,
            inventoryDisplayMode = InventoryDisplayMode.COMPACT,
            isLoaded = true,
            isSaving = false,
            errorCode = "SETTINGS_READ_FAILED",
            errorMessage = "App settings could not be read safely.",
          )
      }
    }
  }

  companion object {
    fun factory(
      settingsStore: AppSettingsStore = InMemoryAppSettingsStore(),
      credentialStore: CloudCredentialStore = InMemoryCloudCredentialStore(),
      pythonHealthCheck: (suspend () -> PythonRuntimeHealth)? = null,
    ): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
          return SettingsViewModel(
            settingsStore,
            credentialStore,
            pythonHealthCheck,
          )
            as T
        }
      }
  }
}
