package com.prfd.tinytuya.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.prfd.tinytuya.ui.inventory.InventoryScreen
import com.prfd.tinytuya.ui.onboarding.OnboardingRoute
import com.prfd.tinytuya.ui.onboarding.OnboardingViewModel
import com.prfd.tinytuya.ui.settings.SettingsScreen
import com.prfd.tinytuya.ui.settings.SettingsViewModel

@Composable
fun AppRoute(
  appViewModel: AppViewModel,
  settingsViewModel: SettingsViewModel,
  onboardingViewModel: OnboardingViewModel,
) {
  val state by appViewModel.state.collectAsState()
  val settingsState by settingsViewModel.state.collectAsState()
  val settingsInventory = state as? AppUiState.Inventory
  var showSettings by rememberSaveable { mutableStateOf(false) }

  LaunchedEffect(state is AppUiState.Inventory) {
    if (state !is AppUiState.Inventory) showSettings = false
  }

  LaunchedEffect(showSettings) { if (showSettings) settingsViewModel.checkPythonHealth() }

  BackHandler(enabled = showSettings) { showSettings = false }

  if (showSettings && settingsInventory != null) {
    SettingsScreen(
      state = settingsState,
      onRefreshWhenAppOpensChanged = settingsViewModel::setRefreshWhenAppOpens,
      onSyncFromCloud = {
        showSettings = false
        onboardingViewModel.prepareForCloudSync(settingsInventory.catalog.region)
        appViewModel.showOnboarding()
      },
      onUpdateCredentials = {
        showSettings = false
        onboardingViewModel.prepareForCredentialUpdate(settingsInventory.catalog.region)
        appViewModel.showOnboarding()
      },
      onForgetCredentials = settingsViewModel::forgetCloudCredentials,
      onDismissError = settingsViewModel::dismissSettingsError,
      onDeleteAllLocalData = {
        onboardingViewModel.clearSession()
        appViewModel.deleteAllLocalData()
        settingsViewModel.deleteAllSettingsData()
      },
      onBack = { showSettings = false },
    )
    return
  }

  AnimatedContent(
    targetState = state,
    contentKey = { destination -> destination::class },
    transitionSpec = { fadeIn() togetherWith fadeOut() },
    label = "app destination",
  ) { destination ->
    when (destination) {
      AppUiState.Loading -> AppLoadingScreen()
      AppUiState.Onboarding ->
        OnboardingRoute(
          viewModel = onboardingViewModel,
          onOpenInventory = {
            onboardingViewModel.clearSession()
            appViewModel.refreshCatalogAfterCloudImport()
            settingsViewModel.refreshCloudAccount()
          },
          onCancel = {
            onboardingViewModel.clearSession()
            appViewModel.refreshCatalog()
            settingsViewModel.refreshCloudAccount()
            showSettings = true
          },
        )
      is AppUiState.Inventory ->
        InventoryScreen(
          catalog = destination.catalog,
          discovery = destination.discovery,
          control = destination.control,
          isLanSnapshotCurrent = destination.isLanSnapshotCurrent,
          networkReverificationPending = destination.networkReverificationPending,
          displayMode = settingsState.inventoryDisplayMode,
          isDisplayModeSaving = settingsState.isSaving,
          onDisplayModeChanged = settingsViewModel::setInventoryDisplayMode,
          onRefreshKnownDevices = appViewModel::refreshKnownDevices,
          onDiscoverLan = appViewModel::discoverLan,
          onIntent = appViewModel::submitControl,
          onOpenSettings = { showSettings = true },
        )
      is AppUiState.Recovery ->
        CatalogRecoveryScreen(
          error = destination,
          onRetry = appViewModel::refreshCatalog,
          onDeleteAllLocalData = {
            onboardingViewModel.clearSession()
            appViewModel.deleteAllLocalData()
            settingsViewModel.deleteAllSettingsData()
          },
        )
    }
  }
}
