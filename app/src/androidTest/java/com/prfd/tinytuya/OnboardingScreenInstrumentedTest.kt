package com.prfd.tinytuya

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.ui.onboarding.CloudImportUiState
import com.prfd.tinytuya.ui.onboarding.OnboardingPage
import com.prfd.tinytuya.ui.onboarding.OnboardingScreen
import com.prfd.tinytuya.ui.onboarding.OnboardingUiState
import com.prfd.tinytuya.ui.theme.TinytuyaTheme
import org.junit.Rule
import org.junit.Test

class OnboardingScreenInstrumentedTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun credentialValidationExplainsBothRequiredValues() {
    setOnboardingContent(
      OnboardingUiState(
        page = OnboardingPage.CREDENTIALS,
        validationAttempted = true,
      )
    )

    composeRule.onNodeWithText("Client ID is required").assertExists()
    composeRule.onNodeWithText("Client Secret is required").assertExists()
  }

  @Test
  fun successSummaryNeverRendersLocalKey() {
    val privateLocalKey = "this-local-key-must-never-be-visible"
    setOnboardingContent(successfulState(privateLocalKey))

    composeRule.onNodeWithText("Bedroom lamp").assertExists()
    composeRule.onNodeWithText(privateLocalKey, substring = true).assertDoesNotExist()
  }

  @Test
  fun clearingSessionWhileSuccessScreenExitsDoesNotCrash() {
    var state by mutableStateOf(successfulState("transition-local-key"))
    setOnboardingContent(
      stateProvider = { state },
      onOpenInventory = { state = OnboardingUiState() },
    )

    composeRule.onNodeWithText("Open device list").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Your devices.\nYour network.").assertExists()
  }

  private fun setOnboardingContent(
    state: OnboardingUiState,
    onOpenInventory: () -> Unit = {},
  ) =
    setOnboardingContent(
      stateProvider = { state },
      onOpenInventory = onOpenInventory,
    )

  private fun setOnboardingContent(
    stateProvider: () -> OnboardingUiState,
    onOpenInventory: () -> Unit = {},
  ) {
    composeRule.setContent {
      TinytuyaTheme(darkTheme = false) {
        OnboardingScreen(
          state = stateProvider(),
          onStartSetup = {},
          onSkipGuide = {},
          onBack = {},
          onOpenOfficialGuide = {},
          onRegionChanged = {},
          onClientIdChanged = {},
          onClientSecretChanged = {},
          onSampleDeviceIdChanged = {},
          onToggleAdvanced = {},
          onImport = {},
          onDismissError = {},
          onReturnToCredentials = {},
          onReviewSetup = {},
          onOpenInventory = onOpenInventory,
        )
      }
    }
  }

  private fun successfulState(localKey: String): OnboardingUiState {
    val result =
      CloudImportResult(
        contractVersion = 1,
        region = TuyaCloudRegion.WESTERN_AMERICA,
        deviceCount = 1,
        missingLocalKeyCount = 0,
        warnings = emptyList(),
        devices =
          listOf(
            CloudImportedDevice(
              id = "device-id",
              name = "Bedroom lamp",
              localKey = SensitiveString.of(localKey),
              category = "dj",
              productId = "product-id",
              productName = "Lamp",
              model = "Model",
              mac = "00:00:00:00:00:00",
              uuid = "uuid",
              isSubDevice = false,
              gatewayId = "",
              nodeId = "",
              protocolVersion = "3.5",
              lastIp = "192.0.2.1",
              mappingJson = "{}",
            )
          ),
      )
    return OnboardingUiState(
      page = OnboardingPage.CREDENTIALS,
      cloudImport = CloudImportUiState.Success(result),
    )
  }
}
