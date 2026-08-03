package com.prfd.tinytuya

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.prfd.tinytuya.data.local.CloudCredentialSummary
import com.prfd.tinytuya.data.python.PythonCryptoHealth
import com.prfd.tinytuya.data.python.PythonRuntimeHealth
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.ui.app.AppSettingsUiState
import com.prfd.tinytuya.ui.app.CloudAccountUiState
import com.prfd.tinytuya.ui.app.TinyTuyaHealthUiState
import com.prfd.tinytuya.ui.settings.SettingsScreen
import com.prfd.tinytuya.ui.theme.TinytuyaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun defaultSettingExplainsAndInvokesLocalOnlyRefreshPreference() {
        var requestedValue: Boolean? = null
        setSettingsContent(
            state = AppSettingsUiState(
                refreshWhenAppOpens = true,
                isLoaded = true,
            ),
            onChanged = { requestedValue = it },
        )

        composeRule.onNodeWithTag("refresh_when_open_switch")
            .assertIsOn()
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle { assertEquals(false, requestedValue) }
        composeRule.onNodeWithText("goes straight to the saved devices", substring = true)
            .assertExists()
        composeRule.onNodeWithText("never signs in to Tuya Cloud", substring = true).assertExists()
        composeRule.onNodeWithText("hidden background service", substring = true).assertExists()
    }

    @Test
    fun savingStatePreventsASecondPreferenceWrite() {
        setSettingsContent(
            state = AppSettingsUiState(
                refreshWhenAppOpens = false,
                isLoaded = true,
                isSaving = true,
            ),
        )

        composeRule.onNodeWithTag("refresh_when_open_switch").assertIsNotEnabled()
        composeRule.onNodeWithText("MANUAL ONLY").assertExists()
    }

    @Test
    fun healthyTinyTuyaRuntimeShowsSafeDiagnostics() {
        setSettingsContent(
            state = AppSettingsUiState(
                refreshWhenAppOpens = true,
                isLoaded = true,
                tinyTuyaHealth = TinyTuyaHealthUiState.Ready(
                    PythonRuntimeHealth(
                        contractVersion = 1,
                        pythonVersion = "3.11.13",
                        tinytuyaVersion = "1.20.0",
                        crypto = PythonCryptoHealth(
                            library = "cryptography",
                            version = "45.0.0",
                            gcmAvailable = true,
                            selfTestPassed = true,
                        ),
                        supportedProtocols = listOf("3.1", "3.2", "3.3", "3.4", "3.5"),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag("tinytuya_health_card").assertExists()
        composeRule.onNodeWithText("TinyTuya Info").assertExists()
        composeRule.onNodeWithText("READY").assertExists()
        composeRule.onNodeWithText("3.11.13").assertExists()
        composeRule.onNodeWithText("1.20.0").assertExists()
        composeRule.onNodeWithText("Passed").assertExists()
        composeRule.onNodeWithText("3.1, 3.2, 3.3, 3.4, 3.5").assertExists()
    }

    @Test
    fun failedTinyTuyaRuntimeShowsSafeReference() {
        setSettingsContent(
            state = AppSettingsUiState(
                refreshWhenAppOpens = true,
                isLoaded = true,
                tinyTuyaHealth = TinyTuyaHealthUiState.Error(
                    code = "BRIDGE_HEALTH_FAILED",
                    message = "The embedded TinyTuya runtime could not be initialized.",
                ),
            ),
        )

        composeRule.onNodeWithText("CHECK REQUIRED").assertExists()
        composeRule.onNodeWithText("The embedded TinyTuya runtime could not be initialized.")
            .assertExists()
        composeRule.onNodeWithText("Reference · BRIDGE_HEALTH_FAILED").assertExists()
    }

    @Test
    fun backActionIsHoisted() {
        var backCalled = false
        setSettingsContent(onBack = { backCalled = true })

        composeRule.onNodeWithTag("settings_back").performClick()
        composeRule.onNodeWithContentDescription("TinyTuya").assertExists()

        composeRule.runOnIdle { assertTrue(backCalled) }
    }

    @Test
    fun savedCloudAccountIsMaskedAndExposesExplicitActions() {
        var syncCalled = false
        var updateCalled = false
        setSettingsContent(
            state = AppSettingsUiState(
                refreshWhenAppOpens = true,
                isLoaded = true,
                cloudAccount = CloudAccountUiState.Saved(
                    CloudCredentialSummary(
                        region = TuyaCloudRegion.CENTRAL_EUROPE,
                        maskedClientId = "abcd••••wxyz",
                        savedAtEpochMillis = 1L,
                    )
                ),
            ),
            onSync = { syncCalled = true },
            onUpdate = { updateCalled = true },
        )

        composeRule.onNodeWithText("Central Europe").assertExists()
        composeRule.onNodeWithText("Client ID · abcd••••wxyz").assertExists()
        composeRule.onNodeWithText("client-secret-must-never-appear").assertDoesNotExist()
        composeRule.onNodeWithTag("sync_from_tuya").performClick()
        composeRule.onNodeWithTag("update_cloud_credentials").performClick()

        composeRule.runOnIdle {
            assertTrue(syncCalled)
            assertTrue(updateCalled)
        }
    }

    @Test
    fun forgettingCredentialsRequiresConfirmation() {
        var forgetCalled = false
        setSettingsContent(
            state = AppSettingsUiState(
                refreshWhenAppOpens = true,
                isLoaded = true,
                cloudAccount = CloudAccountUiState.Saved(
                    CloudCredentialSummary(
                        region = TuyaCloudRegion.WESTERN_AMERICA,
                        maskedClientId = "ab••••yz",
                        savedAtEpochMillis = 1L,
                    )
                ),
            ),
            onForget = { forgetCalled = true },
        )

        composeRule.onNodeWithTag("forget_cloud_credentials").performClick()
        composeRule.runOnIdle { assertTrue(!forgetCalled) }
        composeRule.onNodeWithText("Your imported devices and local controls will remain available.", substring = true)
            .assertExists()
        composeRule.onNodeWithTag("confirm_forget_credentials").performClick()

        composeRule.runOnIdle { assertTrue(forgetCalled) }
    }

    private fun setSettingsContent(
        state: AppSettingsUiState = AppSettingsUiState(
            refreshWhenAppOpens = true,
            isLoaded = true,
        ),
        onChanged: (Boolean) -> Unit = {},
        onSync: () -> Unit = {},
        onUpdate: () -> Unit = {},
        onForget: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            TinytuyaTheme(darkTheme = false) {
                SettingsScreen(
                    state = state,
                    onRefreshWhenAppOpensChanged = onChanged,
                    onSyncFromCloud = onSync,
                    onUpdateCredentials = onUpdate,
                    onForgetCredentials = onForget,
                    onDismissError = {},
                    onBack = onBack,
                )
            }
        }
    }
}
