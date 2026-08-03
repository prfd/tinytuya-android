package com.prfd.tinytuya

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.prfd.tinytuya.ui.app.AppSettingsUiState
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
    fun backActionIsHoisted() {
        var backCalled = false
        setSettingsContent(onBack = { backCalled = true })

        composeRule.onNodeWithTag("settings_back").performClick()

        composeRule.runOnIdle { assertTrue(backCalled) }
    }

    private fun setSettingsContent(
        state: AppSettingsUiState = AppSettingsUiState(
            refreshWhenAppOpens = true,
            isLoaded = true,
        ),
        onChanged: (Boolean) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            TinytuyaTheme(darkTheme = false) {
                SettingsScreen(
                    state = state,
                    onRefreshWhenAppOpensChanged = onChanged,
                    onDismissError = {},
                    onBack = onBack,
                )
            }
        }
    }
}
