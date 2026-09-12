package com.prfd.tinytuya

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.prfd.tinytuya.data.local.CloudCredentialSummary
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.ui.settings.AppSettingsUiState
import com.prfd.tinytuya.ui.settings.CloudAccountUiState
import com.prfd.tinytuya.ui.settings.SettingsScreen
import com.prfd.tinytuya.ui.theme.TinytuyaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsScreenInstrumentedTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun savedCloudAccountIsMaskedAndExposesExplicitActions() {
    var syncCalled = false
    var updateCalled = false
    setSettingsContent(
      state =
        AppSettingsUiState(
          refreshWhenAppOpens = true,
          isLoaded = true,
          cloudAccount =
            CloudAccountUiState.Saved(
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
      state =
        AppSettingsUiState(
          refreshWhenAppOpens = true,
          isLoaded = true,
          cloudAccount =
            CloudAccountUiState.Saved(
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
    composeRule
      .onNodeWithText(
        "Your imported devices and local controls will remain available.",
        substring = true,
      )
      .assertExists()
    composeRule.onNodeWithTag("confirm_forget_credentials").performClick()

    composeRule.runOnIdle { assertTrue(forgetCalled) }
  }

  @Test
  fun deletingAllLocalDataRequiresConfirmation() {
    var deleteCalled = false
    setSettingsContent(
      state =
        AppSettingsUiState(
          refreshWhenAppOpens = true,
          isLoaded = true,
        ),
      onDeleteAllLocalData = { deleteCalled = true },
    )

    composeRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("delete_all_data_button"))
    composeRule.onNodeWithTag("delete_all_data_button").performClick()
    composeRule.runOnIdle { assertTrue(!deleteCalled) }
    composeRule
      .onNodeWithText(
        "You will need to import from Tuya again.",
        substring = true,
      )
      .assertExists()
    composeRule.onNodeWithTag("confirm_delete_all_local_data").performClick()

    composeRule.runOnIdle { assertTrue(deleteCalled) }
  }

  private fun setSettingsContent(
    state: AppSettingsUiState =
      AppSettingsUiState(
        refreshWhenAppOpens = true,
        isLoaded = true,
      ),
    onChanged: (Boolean) -> Unit = {},
    onSync: () -> Unit = {},
    onUpdate: () -> Unit = {},
    onForget: () -> Unit = {},
    onDeleteAllLocalData: () -> Unit = {},
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
          onDeleteAllLocalData = onDeleteAllLocalData,
          onBack = onBack,
        )
      }
    }
  }
}
