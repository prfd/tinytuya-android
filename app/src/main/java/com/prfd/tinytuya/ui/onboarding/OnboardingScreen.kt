package com.prfd.tinytuya.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.TuyaCloudRegion

private const val TUYA_SETUP_GUIDE_URL =
  "https://github.com/prfd/tinytuya-android/blob/master/TUYA_CLOUD.md"

private sealed interface OnboardingDestination {
  data object Welcome : OnboardingDestination

  data object SetupGuide : OnboardingDestination

  data object Credentials : OnboardingDestination

  data object Importing : OnboardingDestination

  data class Error(val error: CloudImportUiState.Error) : OnboardingDestination

  data class Success(val result: CloudImportResult) : OnboardingDestination
}

@Composable
fun OnboardingRoute(
  viewModel: OnboardingViewModel,
  onOpenInventory: () -> Unit,
  onCancel: () -> Unit,
) {
  val context = LocalContext.current
  val state by viewModel.state.collectAsState()

  SecureWindow(
    enabled =
      !state.clientId.isBlank || !state.clientSecret.isBlank || !state.sampleDeviceId.isBlank
  )

  val destination = state.destination()
  val back = { if (!viewModel.goBack()) onCancel() }
  BackHandler(enabled = destination != OnboardingDestination.Welcome || state.fromSettings) {
    back()
  }

  OnboardingScreen(
    state = state,
    onStartSetup = viewModel::showSetupGuide,
    onSkipGuide = viewModel::showCredentials,
    onBack = back,
    onOpenGuide = {
      runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, TUYA_SETUP_GUIDE_URL.toUri()))
      }
    },
    onRegionChanged = viewModel::updateRegion,
    onClientIdChanged = viewModel::updateClientId,
    onClientSecretChanged = viewModel::updateClientSecret,
    onSampleDeviceIdChanged = viewModel::updateSampleDeviceId,
    onToggleAdvanced = viewModel::toggleAdvanced,
    onImport = viewModel::importDevices,
    onDismissError = viewModel::dismissError,
    onReturnToCredentials = viewModel::returnToCredentials,
    onReviewSetup = viewModel::showSetupGuide,
    onOpenInventory = onOpenInventory,
  )
}

@Composable
private fun SecureWindow(enabled: Boolean) {
  val view = LocalView.current
  DisposableEffect(view, enabled) {
    val window = view.context.findActivity()?.window
    if (enabled) {
      window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    onDispose {
      if (enabled) {
        window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
      }
    }
  }
}

private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

@Composable
fun OnboardingScreen(
  state: OnboardingUiState,
  onStartSetup: () -> Unit,
  onSkipGuide: () -> Unit,
  onBack: () -> Unit,
  onOpenGuide: () -> Unit,
  onRegionChanged: (TuyaCloudRegion) -> Unit,
  onClientIdChanged: (String) -> Unit,
  onClientSecretChanged: (String) -> Unit,
  onSampleDeviceIdChanged: (String) -> Unit,
  onToggleAdvanced: () -> Unit,
  onImport: () -> Unit,
  onDismissError: () -> Unit,
  onReturnToCredentials: () -> Unit,
  onReviewSetup: () -> Unit,
  onOpenInventory: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Box(
      modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
      contentAlignment = Alignment.TopCenter,
    ) {
      AnimatedContent(
        targetState = state.destination(),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "onboarding destination",
        modifier = Modifier.fillMaxHeight().fillMaxWidth().widthIn(max = 680.dp),
      ) { destination ->
        when (destination) {
          OnboardingDestination.Welcome ->
            WelcomeScreen(
              onStartSetup = onStartSetup,
              onSkipGuide = onSkipGuide,
            )

          OnboardingDestination.SetupGuide ->
            SetupGuideScreen(
              onBack = onBack,
              onOpenGuide = onOpenGuide,
              onContinue = onSkipGuide,
            )

          OnboardingDestination.Credentials ->
            CredentialsScreen(
              state = state,
              onBack = onBack,
              onRegionChanged = onRegionChanged,
              onClientIdChanged = onClientIdChanged,
              onClientSecretChanged = onClientSecretChanged,
              onSampleDeviceIdChanged = onSampleDeviceIdChanged,
              onToggleAdvanced = onToggleAdvanced,
              onImport = onImport,
            )

          OnboardingDestination.Importing -> ImportingScreen()
          is OnboardingDestination.Error ->
            ErrorScreen(
              error = destination.error,
              onRetry = onDismissError,
              onReviewSetup = onReviewSetup,
            )

          is OnboardingDestination.Success ->
            SuccessScreen(
              result = destination.result,
              onImportAgain = onReturnToCredentials,
              onOpenInventory = onOpenInventory,
            )
        }
      }
    }
  }
}

private fun OnboardingUiState.destination(): OnboardingDestination =
  when (cloudImport) {
    CloudImportUiState.Loading -> OnboardingDestination.Importing
    is CloudImportUiState.Error -> OnboardingDestination.Error(cloudImport)
    is CloudImportUiState.Success -> OnboardingDestination.Success(cloudImport.result)
    CloudImportUiState.Idle ->
      when (page) {
        OnboardingPage.WELCOME -> OnboardingDestination.Welcome
        OnboardingPage.SETUP_GUIDE -> OnboardingDestination.SetupGuide
        OnboardingPage.CREDENTIALS -> OnboardingDestination.Credentials
      }
  }
