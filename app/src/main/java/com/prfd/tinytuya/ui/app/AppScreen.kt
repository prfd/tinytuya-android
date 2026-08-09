package com.prfd.tinytuya.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.prfd.tinytuya.ui.components.BrandMark
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

@Composable
private fun AppLoadingScreen() {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        BrandMark(Modifier.size(64.dp))
        CircularProgressIndicator(Modifier.size(88.dp), strokeWidth = 3.dp)
      }
      Text(
        text = "Opening your local home…",
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 22.dp),
      )
    }
  }
}

@Composable
private fun CatalogRecoveryScreen(
  error: AppUiState.Recovery,
  onRetry: () -> Unit,
  onDeleteAllLocalData: () -> Unit,
) {
  var confirmDelete by remember { mutableStateOf(false) }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
          Box(Modifier.size(82.dp), contentAlignment = Alignment.Center) {
            Text("!", style = MaterialTheme.typography.headlineMedium)
          }
        }
        Text(
          text = "Encrypted storage needs attention",
          style = MaterialTheme.typography.headlineMedium,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 24.dp),
        )
        Text(
          text = error.message,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 10.dp),
        )
        Text(
          text =
            "TinyTuya will not bypass a failed integrity check or silently replace your data. Reference · ${error.code}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 14.dp),
        )
        Spacer(Modifier.height(28.dp))
        Button(
          onClick = onRetry,
          modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
          Text("Try again")
        }
        OutlinedButton(
          onClick = { confirmDelete = true },
          modifier = Modifier.fillMaxWidth().height(54.dp).padding(top = 8.dp),
        ) {
          Text("Delete unreadable local data")
        }
      }
    }
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("Start over with an empty catalog?") },
      text = {
        Text(
          "This permanently removes the encrypted catalog and Keystore key. " +
            "You will need to import from Tuya again."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            confirmDelete = false
            onDeleteAllLocalData()
          }
        ) {
          Text("Delete and start over")
        }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
  }
}
