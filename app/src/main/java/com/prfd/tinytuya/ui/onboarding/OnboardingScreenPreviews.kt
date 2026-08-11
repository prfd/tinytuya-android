package com.prfd.tinytuya.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.ui.theme.TinytuyaTheme

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun WelcomePreview() {
  TinytuyaTheme(darkTheme = false) {
    OnboardingScreen(
      state = OnboardingUiState(),
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
      onOpenInventory = {},
    )
  }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun SuccessPreview() {
  TinytuyaTheme(darkTheme = false) {
    OnboardingScreen(
      state =
        OnboardingUiState(
          cloudImport =
            CloudImportUiState.Success(
              CloudImportResult(
                contractVersion = 1,
                region = TuyaCloudRegion.WESTERN_AMERICA,
                deviceCount = 3,
                missingLocalKeyCount = 1,
                warnings = emptyList(),
                devices =
                  listOf(
                    CloudImportedDevice(
                      id = "1023456789abcdef",
                      name = "Bedroom Lamp",
                      localKey = SensitiveString.of("abcdefghijklmnop"),
                      category = "dj",
                      productId = "qwertyuiop123456",
                      productName = "Smart Bulb",
                      model = "TMB-01",
                      mac = "A1:B2:C3:D4:E5:F6",
                      uuid = "",
                      isSubDevice = false,
                      gatewayId = "",
                      nodeId = "",
                      protocolVersion = "3.3",
                      lastIp = "",
                      mappingJson = "{}",
                    ),
                    CloudImportedDevice(
                      id = "1023456789abcdef",
                      name = "Kitchen Outlet",
                      localKey = SensitiveString.of("abcdefghijklmnop"),
                      category = "cz",
                      productId = "qwertyuiop123456",
                      productName = "Smart Plug",
                      model = "TMP-02",
                      mac = "A1:B2:C3:D4:E5:F7",
                      uuid = "",
                      isSubDevice = false,
                      gatewayId = "",
                      nodeId = "",
                      protocolVersion = "3.3",
                      lastIp = "",
                      mappingJson = "{}",
                    ),
                    CloudImportedDevice(
                      id = "1023456789abcdef",
                      name = "Thermostat",
                      localKey = SensitiveString.of(""),
                      category = "wk",
                      productId = "qwertyuiop123456",
                      productName = "Smart Thermostat",
                      model = "TMT-03",
                      mac = "A1:B2:C3:D4:E5:F8",
                      uuid = "",
                      isSubDevice = false,
                      gatewayId = "",
                      nodeId = "",
                      protocolVersion = "3.5",
                      lastIp = "",
                      mappingJson = "{}",
                    ),
                  ),
              )
            )
        ),
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
      onOpenInventory = {},
    )
  }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun CredentialsPreview() {
  TinytuyaTheme(darkTheme = true) {
    OnboardingScreen(
      state = OnboardingUiState(page = OnboardingPage.CREDENTIALS),
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
      onOpenInventory = {},
    )
  }
}
