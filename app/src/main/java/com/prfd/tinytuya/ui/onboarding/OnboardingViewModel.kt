package com.prfd.tinytuya.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prfd.tinytuya.data.local.CloudCredentialStorageException
import com.prfd.tinytuya.data.local.CloudCredentialStore
import com.prfd.tinytuya.data.local.DeviceCatalogStorageException
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.local.EncryptedCloudCredentialStore
import com.prfd.tinytuya.data.local.EncryptedDeviceCatalogStore
import com.prfd.tinytuya.data.local.InMemoryCloudCredentialStore
import com.prfd.tinytuya.data.python.ChaquopyTuyaPythonGateway
import com.prfd.tinytuya.data.python.CloudCredentials
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.PythonBridgeException
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.data.python.TuyaPythonGateway
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingPage {
    WELCOME,
    SETUP_GUIDE,
    CREDENTIALS,
}

sealed interface CloudImportUiState {
    data object Idle : CloudImportUiState

    data object Loading : CloudImportUiState

    data class Error(
        val code: String,
        val message: String,
    ) : CloudImportUiState

    data class Success(val result: CloudImportResult) : CloudImportUiState
}

data class OnboardingUiState(
    val page: OnboardingPage = OnboardingPage.WELCOME,
    val region: TuyaCloudRegion = TuyaCloudRegion.WESTERN_AMERICA,
    val clientId: SensitiveString = SensitiveString.of(""),
    val clientSecret: SensitiveString = SensitiveString.of(""),
    val sampleDeviceId: SensitiveString = SensitiveString.of(""),
    val showAdvanced: Boolean = false,
    val validationAttempted: Boolean = false,
    val isCredentialUpdate: Boolean = false,
    val usedSavedCredentials: Boolean = false,
    val cloudImport: CloudImportUiState = CloudImportUiState.Idle,
) {
    val canImport: Boolean
        get() = !clientId.isBlank && !clientSecret.isBlank
}

class OnboardingViewModel(
    private val gateway: TuyaPythonGateway,
    private val catalogStore: DeviceCatalogStore,
    private val credentialStore: CloudCredentialStore = InMemoryCloudCredentialStore(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = mutableState.asStateFlow()

    fun showWelcome() {
        if (mutableState.value.cloudImport is CloudImportUiState.Loading) return
        mutableState.update { it.copy(page = OnboardingPage.WELCOME) }
    }

    fun showSetupGuide() {
        if (mutableState.value.cloudImport is CloudImportUiState.Loading) return
        mutableState.update {
            it.copy(
                page = OnboardingPage.SETUP_GUIDE,
                cloudImport = CloudImportUiState.Idle,
            )
        }
    }

    fun showCredentials() {
        if (mutableState.value.cloudImport is CloudImportUiState.Loading) return
        mutableState.update {
            it.copy(
                page = OnboardingPage.CREDENTIALS,
                cloudImport = CloudImportUiState.Idle,
            )
        }
    }

    fun goBack(): Boolean {
        val current = mutableState.value
        if (current.cloudImport is CloudImportUiState.Loading) return true

        if (current.cloudImport is CloudImportUiState.Success ||
            current.cloudImport is CloudImportUiState.Error
        ) {
            mutableState.update { it.copy(cloudImport = CloudImportUiState.Idle) }
            return true
        }

        return when (current.page) {
            OnboardingPage.WELCOME -> false
            OnboardingPage.SETUP_GUIDE -> {
                showWelcome()
                true
            }
            OnboardingPage.CREDENTIALS -> {
                showSetupGuide()
                true
            }
        }
    }

    fun updateRegion(region: TuyaCloudRegion) {
        mutableState.update { it.copy(region = region, cloudImport = CloudImportUiState.Idle) }
    }

    fun updateClientId(value: String) {
        mutableState.update {
            it.copy(clientId = SensitiveString.of(value), cloudImport = CloudImportUiState.Idle)
        }
    }

    fun updateClientSecret(value: String) {
        mutableState.update {
            it.copy(clientSecret = SensitiveString.of(value), cloudImport = CloudImportUiState.Idle)
        }
    }

    fun updateSampleDeviceId(value: String) {
        mutableState.update {
            it.copy(sampleDeviceId = SensitiveString.of(value), cloudImport = CloudImportUiState.Idle)
        }
    }

    fun toggleAdvanced() {
        mutableState.update { it.copy(showAdvanced = !it.showAdvanced) }
    }

    fun importDevices() {
        val current = mutableState.value
        if (current.cloudImport is CloudImportUiState.Loading) return
        if (!current.canImport) {
            mutableState.update { it.copy(validationAttempted = true) }
            return
        }

        val credentials = CloudCredentials(
            region = current.region,
            clientId = current.clientId.reveal(),
            clientSecret = SensitiveString.of(current.clientSecret.reveal()),
            sampleDeviceId = current.sampleDeviceId.reveal().ifBlank { null },
        )

        mutableState.update {
            it.copy(
                validationAttempted = false,
                usedSavedCredentials = false,
                cloudImport = CloudImportUiState.Loading,
            )
        }

        runCloudImport(
            credentialsProvider = { credentials },
            saveProvidedCredentials = true,
        )
    }

    /**
     * Starts an explicit cloud sync with the retained account when one exists.
     * No caller other than a user action should invoke this method.
     */
    fun prepareForCloudSync(fallbackRegion: TuyaCloudRegion) {
        if (mutableState.value.cloudImport is CloudImportUiState.Loading) return
        mutableState.value = OnboardingUiState(
            page = OnboardingPage.CREDENTIALS,
            region = fallbackRegion,
            isCredentialUpdate = true,
            usedSavedCredentials = true,
            cloudImport = CloudImportUiState.Loading,
        )
        runCloudImport(
            credentialsProvider = {
                credentialStore.load()?.let { saved ->
                    CloudCredentials(
                        region = saved.region,
                        clientId = saved.clientId.reveal(),
                        clientSecret = SensitiveString.of(saved.clientSecret.reveal()),
                    )
                }
            },
            saveProvidedCredentials = false,
            onCredentialsMissing = {
                mutableState.value = OnboardingUiState(
                    page = OnboardingPage.CREDENTIALS,
                    region = fallbackRegion,
                    isCredentialUpdate = true,
                )
            },
        )
    }

    fun prepareForCredentialUpdate(region: TuyaCloudRegion) {
        if (mutableState.value.cloudImport is CloudImportUiState.Loading) return
        mutableState.value = OnboardingUiState(
            page = OnboardingPage.CREDENTIALS,
            region = region,
            isCredentialUpdate = true,
        )
    }

    private fun runCloudImport(
        credentialsProvider: suspend () -> CloudCredentials?,
        saveProvidedCredentials: Boolean,
        onCredentialsMissing: () -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                val credentials = credentialsProvider()
                if (credentials == null) {
                    onCredentialsMissing()
                    return@launch
                }
                val previousDevices = catalogStore.load()?.devices.orEmpty()
                val result = gateway.importCloud(credentials, previousDevices)
                if (saveProvidedCredentials) {
                    credentialStore.save(
                        region = credentials.region,
                        clientId = SensitiveString.of(credentials.clientId),
                        clientSecret = SensitiveString.of(credentials.clientSecret.reveal()),
                    )
                }
                mutableState.update {
                    it.copy(
                        clientId = SensitiveString.of(""),
                        clientSecret = SensitiveString.of(""),
                        sampleDeviceId = SensitiveString.of(""),
                    )
                }
                if (result.devices.isNotEmpty()) {
                    catalogStore.replaceFromCloud(result)
                }
                mutableState.update {
                    it.copy(cloudImport = CloudImportUiState.Success(result))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: DeviceCatalogStorageException) {
                mutableState.update {
                    it.copy(
                        cloudImport = CloudImportUiState.Error(
                            code = error.code,
                            message = error.message ?: "Encrypted device storage is unavailable.",
                        )
                    )
                }
            } catch (error: CloudCredentialStorageException) {
                mutableState.update {
                    it.copy(
                        cloudImport = CloudImportUiState.Error(
                            code = error.code,
                            message = error.message
                                ?: "The saved Tuya Cloud credentials are unavailable.",
                        )
                    )
                }
            } catch (error: PythonBridgeException) {
                mutableState.update {
                    it.copy(
                        cloudImport = CloudImportUiState.Error(
                            code = error.code,
                            message = error.message ?: "The cloud import could not be completed.",
                        )
                    )
                }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        cloudImport = CloudImportUiState.Error(
                            code = "UNEXPECTED_ERROR",
                            message = "The cloud import could not be completed. Check your connection and try again.",
                        )
                    )
                }
            }
        }
    }

    fun dismissError() {
        mutableState.update { current ->
            if (current.usedSavedCredentials) {
                current.copy(
                    clientId = SensitiveString.of(""),
                    clientSecret = SensitiveString.of(""),
                    sampleDeviceId = SensitiveString.of(""),
                    validationAttempted = false,
                    isCredentialUpdate = true,
                    usedSavedCredentials = false,
                    cloudImport = CloudImportUiState.Idle,
                )
            } else {
                current.copy(cloudImport = CloudImportUiState.Idle)
            }
        }
    }

    fun returnToCredentials() {
        mutableState.update {
            it.copy(
                page = OnboardingPage.CREDENTIALS,
                clientId = SensitiveString.of(""),
                clientSecret = SensitiveString.of(""),
                sampleDeviceId = SensitiveString.of(""),
                validationAttempted = false,
                isCredentialUpdate = true,
                usedSavedCredentials = false,
                cloudImport = CloudImportUiState.Idle,
            )
        }
    }

    fun clearSession() {
        mutableState.value = OnboardingUiState()
    }

    companion object {
        fun factory(
            context: Context,
            catalogStore: DeviceCatalogStore = EncryptedDeviceCatalogStore(context.applicationContext),
            credentialStore: CloudCredentialStore =
                EncryptedCloudCredentialStore(context.applicationContext),
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(OnboardingViewModel::class.java))
                    return OnboardingViewModel(
                        gateway = ChaquopyTuyaPythonGateway(context.applicationContext),
                        catalogStore = catalogStore,
                        credentialStore = credentialStore,
                    ) as T
                }
            }
    }
}
