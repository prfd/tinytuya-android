package com.prfd.tinytuya

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.python.CloudCredentials
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.PythonBridgeException
import com.prfd.tinytuya.data.python.PythonRuntimeHealth
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.data.python.TuyaPythonGateway
import com.prfd.tinytuya.ui.onboarding.CloudImportUiState
import com.prfd.tinytuya.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingViewModelInstrumentedTest {
    @Test
    fun successfulImportClearsEveryCredentialField() = runBlocking {
        val viewModel = OnboardingViewModel(
            gateway = FakeGateway { successfulEmptyImport() }
        )
        viewModel.updateClientId("private-client-id")
        viewModel.updateClientSecret("private-client-secret")
        viewModel.updateSampleDeviceId("private-device-id")

        viewModel.importDevices()

        val state = withTimeout(5_000) {
            viewModel.state.first { it.cloudImport is CloudImportUiState.Success }
        }
        assertTrue(state.clientId.isBlank)
        assertTrue(state.clientSecret.isBlank)
        assertTrue(state.sampleDeviceId.isBlank)
        assertFalse(state.toString().contains("private-client-secret"))
    }

    @Test
    fun safeFailureKeepsInMemoryValuesForCorrection() = runBlocking {
        val viewModel = OnboardingViewModel(
            gateway = FakeGateway {
                throw PythonBridgeException(
                    code = "CLOUD_CREDENTIALS_INVALID",
                    message = "Tuya did not accept these cloud credentials.",
                )
            }
        )
        viewModel.updateClientId("retry-client-id")
        viewModel.updateClientSecret("retry-client-secret")

        viewModel.importDevices()

        val state = withTimeout(5_000) {
            viewModel.state.first { it.cloudImport is CloudImportUiState.Error }
        }
        val error = state.cloudImport as CloudImportUiState.Error
        assertEquals("CLOUD_CREDENTIALS_INVALID", error.code)
        assertFalse(state.clientId.isBlank)
        assertFalse(state.clientSecret.isBlank)
        assertFalse(state.toString().contains("retry-client-secret"))
    }

    private class FakeGateway(
        private val response: suspend (CloudCredentials) -> CloudImportResult,
    ) : TuyaPythonGateway {
        override suspend fun health(): PythonRuntimeHealth =
            error("Health is not used by this onboarding test.")

        override suspend fun importCloud(
            credentials: CloudCredentials,
            previousDevices: List<CloudImportedDevice>,
        ): CloudImportResult = response(credentials)
    }

    private companion object {
        fun successfulEmptyImport() = CloudImportResult(
            contractVersion = 1,
            region = TuyaCloudRegion.WESTERN_AMERICA,
            deviceCount = 0,
            missingLocalKeyCount = 0,
            warnings = emptyList(),
            devices = emptyList(),
        )
    }
}
