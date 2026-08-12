package com.prfd.tinytuya

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.local.AppSettings
import com.prfd.tinytuya.data.local.InMemoryAppSettingsStore
import com.prfd.tinytuya.data.local.InMemoryCloudCredentialStore
import com.prfd.tinytuya.data.local.InventoryDisplayMode
import com.prfd.tinytuya.data.local.StoredCloudCredentials
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.ui.settings.CloudAccountUiState
import com.prfd.tinytuya.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsViewModelInstrumentedTest {

  @Test
  fun forgettingCloudCredentialsPreservesTheDeviceCatalog() = runBlocking {
    val credentialStore = InMemoryCloudCredentialStore(savedCredentials())
    val viewModel = SettingsViewModel(credentialStore = credentialStore)
    withTimeout(5_000) { viewModel.state.first { it.cloudAccount is CloudAccountUiState.Saved } }

    viewModel.forgetCloudCredentials()

    withTimeout(5_000) { viewModel.state.first { it.cloudAccount is CloudAccountUiState.Missing } }
    assertNull(credentialStore.load())
  }

  @Test
  fun inventoryDisplayModeLoadsAndPersistsWithoutChangingRefreshPreference() = runBlocking {
    val settingsStore =
      InMemoryAppSettingsStore(
        AppSettings(
          refreshWhenAppOpens = true,
          inventoryDisplayMode = InventoryDisplayMode.COMPACT,
        )
      )
    val viewModel = SettingsViewModel(settingsStore = settingsStore)

    val loaded = withTimeout(5_000) { viewModel.state.first { it.isLoaded } }
    assertEquals(InventoryDisplayMode.COMPACT, loaded.inventoryDisplayMode)

    viewModel.setInventoryDisplayMode(InventoryDisplayMode.FULL)

    val saved =
      withTimeout(5_000) {
        viewModel.state.first {
          it.inventoryDisplayMode == InventoryDisplayMode.FULL && !it.isSaving
        }
      }
    assertTrue(saved.refreshWhenAppOpens)
    assertEquals(InventoryDisplayMode.FULL, settingsStore.load().inventoryDisplayMode)
    assertTrue(settingsStore.load().refreshWhenAppOpens)
  }

  private companion object {
    fun savedCredentials() =
      StoredCloudCredentials(
        region = TuyaCloudRegion.CENTRAL_EUROPE,
        clientId = SensitiveString.of("known-good-client-id"),
        clientSecret = SensitiveString.of("known-good-secret"),
        savedAtEpochMillis = 1L,
      )
  }
}
