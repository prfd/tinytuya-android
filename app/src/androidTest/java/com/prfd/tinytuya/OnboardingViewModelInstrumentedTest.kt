package com.prfd.tinytuya

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.lan.LanDiscoveryRequest
import com.prfd.tinytuya.data.lan.LanDiscoveryResult
import com.prfd.tinytuya.data.lan.LocalPollRequest
import com.prfd.tinytuya.data.lan.LocalPollResult
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStorageException
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.python.CloudCredentials
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.PythonBridgeException
import com.prfd.tinytuya.data.python.PythonRuntimeHealth
import com.prfd.tinytuya.data.python.SensitiveString
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
            gateway = FakeGateway { _, _ -> successfulEmptyImport() },
            catalogStore = FakeCatalogStore(),
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

        viewModel.clearSession()
        assertTrue(viewModel.state.value.cloudImport is CloudImportUiState.Idle)
    }

    @Test
    fun safeFailureKeepsInMemoryValuesForCorrection() = runBlocking {
        val viewModel = OnboardingViewModel(
            gateway = FakeGateway { _, _ ->
                throw PythonBridgeException(
                    code = "CLOUD_CREDENTIALS_INVALID",
                    message = "Tuya did not accept these cloud credentials.",
                )
            },
            catalogStore = FakeCatalogStore(),
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

    @Test
    fun nonEmptyImportReceivesPreviousCatalogAndIsPersisted() = runBlocking {
        val previousDevice = sampleDevice(id = "previous-device")
        val importedDevice = sampleDevice(id = "imported-device")
        val store = FakeCatalogStore(
            loaded = DeviceCatalog(
                schemaVersion = 1,
                importedAtEpochMillis = 1L,
                region = TuyaCloudRegion.WESTERN_AMERICA,
                devices = listOf(previousDevice),
            )
        )
        var previousReceived: List<CloudImportedDevice> = emptyList()
        val viewModel = OnboardingViewModel(
            gateway = FakeGateway { _, previous ->
                previousReceived = previous
                successfulImport(importedDevice)
            },
            catalogStore = store,
        )
        viewModel.updateClientId("client-id")
        viewModel.updateClientSecret("client-secret")

        viewModel.importDevices()

        withTimeout(5_000) {
            viewModel.state.first { it.cloudImport is CloudImportUiState.Success }
        }
        assertEquals(listOf("previous-device"), previousReceived.map { it.id })
        assertEquals(listOf("imported-device"), store.savedResult?.devices?.map { it.id })
    }

    @Test
    fun acceptedCloudCredentialsAreClearedEvenWhenEncryptedWriteFails() = runBlocking {
        val store = FakeCatalogStore(
            replaceError = DeviceCatalogStorageException(
                code = "CATALOG_WRITE_FAILED",
                message = "The imported devices could not be stored safely.",
            )
        )
        val viewModel = OnboardingViewModel(
            gateway = FakeGateway { _, _ -> successfulImport(sampleDevice("device")) },
            catalogStore = store,
        )
        viewModel.updateClientId("accepted-client-id")
        viewModel.updateClientSecret("accepted-client-secret")

        viewModel.importDevices()

        val state = withTimeout(5_000) {
            viewModel.state.first { it.cloudImport is CloudImportUiState.Error }
        }
        assertEquals(
            "CATALOG_WRITE_FAILED",
            (state.cloudImport as CloudImportUiState.Error).code,
        )
        assertTrue(state.clientId.isBlank)
        assertTrue(state.clientSecret.isBlank)
    }

    private class FakeGateway(
        private val response: suspend (
            CloudCredentials,
            List<CloudImportedDevice>,
        ) -> CloudImportResult,
    ) : TuyaPythonGateway {
        override suspend fun health(): PythonRuntimeHealth =
            error("Health is not used by this onboarding test.")

        override suspend fun importCloud(
            credentials: CloudCredentials,
            previousDevices: List<CloudImportedDevice>,
        ): CloudImportResult = response(credentials, previousDevices)

        override suspend fun discoverLan(request: LanDiscoveryRequest): LanDiscoveryResult =
            error("LAN discovery is not used by this onboarding test.")

        override suspend fun pollLocal(request: LocalPollRequest): LocalPollResult =
            error("Local polling is not used by this onboarding test.")

        override suspend fun setLocalValues(
            request: com.prfd.tinytuya.data.lan.LocalControlRequest,
        ): com.prfd.tinytuya.data.lan.LocalControlResult =
            error("Local control is not used by this onboarding test.")
    }

    private class FakeCatalogStore(
        private val loaded: DeviceCatalog? = null,
        private val replaceError: DeviceCatalogStorageException? = null,
    ) : DeviceCatalogStore {
        var savedResult: CloudImportResult? = null

        override suspend fun load(): DeviceCatalog? = loaded

        override suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog {
            replaceError?.let { throw it }
            savedResult = result
            return DeviceCatalog(
                schemaVersion = 1,
                importedAtEpochMillis = 2L,
                region = result.region,
                devices = result.devices,
            )
        }

        override suspend fun mergeLanDiscovery(
            result: LanDiscoveryResult,
            network: com.prfd.tinytuya.data.lan.LanNetworkContext,
        ): DeviceCatalog =
            error("LAN discovery is not used by this onboarding test.")

        override suspend fun mergeLocalPoll(result: LocalPollResult): DeviceCatalog =
            error("Local polling is not used by this onboarding test.")

        override suspend fun deleteAll() = Unit
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

        fun successfulImport(device: CloudImportedDevice) = CloudImportResult(
            contractVersion = 1,
            region = TuyaCloudRegion.WESTERN_AMERICA,
            deviceCount = 1,
            missingLocalKeyCount = 0,
            warnings = emptyList(),
            devices = listOf(device),
        )

        fun sampleDevice(id: String) = CloudImportedDevice(
            id = id,
            name = "Lamp",
            localKey = SensitiveString.of("local-key"),
            category = "dj",
            productId = "product-id",
            productName = "Lamp",
            model = "L1",
            mac = "",
            uuid = "",
            isSubDevice = false,
            gatewayId = "",
            nodeId = "",
            protocolVersion = "3.5",
            lastIp = "",
            mappingJson = "{}",
        )
    }
}
