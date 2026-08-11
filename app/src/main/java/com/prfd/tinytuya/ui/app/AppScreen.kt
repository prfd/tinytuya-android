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

@Composable
fun AppRoute(
  appViewModel: AppViewModel,
  onboardingViewModel: OnboardingViewModel,
) {
  val state by appViewModel.state.collectAsState()
  val settingsState by appViewModel.settingsState.collectAsState()
  val settingsInventory = state as? AppUiState.Inventory
  var showSettings by rememberSaveable { mutableStateOf(false) }

  LaunchedEffect(state is AppUiState.Inventory) {
    if (state !is AppUiState.Inventory) showSettings = false
  }

  LaunchedEffect(showSettings) { if (showSettings) appViewModel.checkPythonHealth() }

  BackHandler(enabled = showSettings) { showSettings = false }

  if (showSettings && settingsInventory != null) {
    SettingsScreen(
      state = settingsState,
      onRefreshWhenAppOpensChanged = appViewModel::setRefreshWhenAppOpens,
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
      onForgetCredentials = appViewModel::forgetCloudCredentials,
      onDismissError = appViewModel::dismissSettingsError,
      onDeleteAllLocalData = {
        onboardingViewModel.clearSession()
        appViewModel.deleteAllLocalData()
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
          },
          onCancel = {
            onboardingViewModel.clearSession()
            appViewModel.refreshCatalog()
            showSettings = true
          },
        )
      is AppUiState.Inventory ->
        InventoryScreen(
          catalog = destination.catalog,
          discovery = destination.discovery,
          control = destination.control,
          isLanSnapshotCurrent = destination.isLanSnapshotCurrent,
          displayMode = settingsState.inventoryDisplayMode,
          isDisplayModeSaving = settingsState.isSaving,
          onDisplayModeChanged = appViewModel::setInventoryDisplayMode,
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
          },
        )
    }
  }
}
