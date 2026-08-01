package com.prfd.tinytuya

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.ui.app.AppUiState
import com.prfd.tinytuya.ui.app.AppViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppViewModelInstrumentedTest {
    @Test
    fun missingCatalogRoutesToOnboarding() = runBlocking {
        val viewModel = AppViewModel(FakeCatalogStore())

        val state = withTimeout(5_000) {
            viewModel.state.first { it !is AppUiState.Loading }
        }

        assertTrue(state is AppUiState.Onboarding)
    }

    @Test
    fun savedCatalogRoutesDirectlyToInventory() = runBlocking {
        val catalog = sampleCatalog()
        val viewModel = AppViewModel(FakeCatalogStore(catalog))

        val state = withTimeout(5_000) {
            viewModel.state.first { it is AppUiState.Inventory }
        } as AppUiState.Inventory

        assertEquals("saved-device", state.catalog.devices.single().id)
        assertTrue(state.catalog.toString().contains("[REDACTED]"))
        assertTrue(!state.catalog.toString().contains("saved-local-key"))
    }

    @Test
    fun deletingLocalDataReturnsToOnboarding() = runBlocking {
        val store = FakeCatalogStore(sampleCatalog())
        val viewModel = AppViewModel(store)
        withTimeout(5_000) {
            viewModel.state.first { it is AppUiState.Inventory }
        }

        viewModel.deleteAllLocalData()

        val state = withTimeout(5_000) {
            viewModel.state.first { it is AppUiState.Onboarding }
        }
        assertTrue(store.deleted)
        assertTrue(state is AppUiState.Onboarding)
    }

    private class FakeCatalogStore(
        private var catalog: DeviceCatalog? = null,
    ) : DeviceCatalogStore {
        var deleted = false

        override suspend fun load(): DeviceCatalog? = catalog

        override suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog =
            error("Cloud replacement is not used by this app-routing test.")

        override suspend fun deleteAll() {
            deleted = true
            catalog = null
        }
    }

    private companion object {
        fun sampleCatalog() = DeviceCatalog(
            schemaVersion = 1,
            importedAtEpochMillis = 1L,
            region = TuyaCloudRegion.WESTERN_AMERICA,
            devices = listOf(
                CloudImportedDevice(
                    id = "saved-device",
                    name = "Saved lamp",
                    localKey = SensitiveString.of("saved-local-key"),
                    category = "dj",
                    productId = "",
                    productName = "Lamp",
                    model = "",
                    mac = "",
                    uuid = "",
                    isSubDevice = false,
                    gatewayId = "",
                    nodeId = "",
                    protocolVersion = "3.5",
                    lastIp = "",
                    mappingJson = "{}",
                )
            ),
        )
    }
}
