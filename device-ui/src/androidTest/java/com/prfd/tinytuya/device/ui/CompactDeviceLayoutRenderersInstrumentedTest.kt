package com.prfd.tinytuya.device.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.core.profile.StandardDeviceLayoutIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class CompactDeviceLayoutRenderersInstrumentedTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun customPowerButtonEmitsTypedIntent() {
    val power = ToggleUiModel(CapabilityId("switch.1"), "Power", true, true)
    val device = device(StandardDeviceLayoutIds.GENERIC_CONTROLS, listOf(power))
    var emitted: DeviceIntent? = null

    setHost(device = device, onIntent = { emitted = it })

    composeRule.onNodeWithTag("compact_toggle_switch_1").assertIsOn().performClick()
    composeRule.runOnIdle {
      assertEquals(DeviceIntent.SetToggle(DEVICE_ID, power.id, false), emitted)
    }
  }

  @Test
  fun pendingPowerButtonKeepsTheLastConfirmedState() {
    val power = ToggleUiModel(CapabilityId("switch.1"), "Power", true, true)
    val device = device(StandardDeviceLayoutIds.GENERIC_CONTROLS, listOf(power))

    setHost(
      device = device,
      controlState =
        DeviceControlUiState.Sending(DeviceIntent.SetToggle(DEVICE_ID, power.id, false)),
    )
    composeRule.onNodeWithTag("compact_toggle_switch_1").assertIsOn().assertIsNotEnabled()
    composeRule.onNodeWithTag("compact_control_progress").assertExists()
  }

  @Test
  fun multiGangRendererExposesEveryResolvedToggleWithoutAnAggregateIntent() {
    val first = ToggleUiModel(CapabilityId("switch.1"), "Switch 1", true, true)
    val second = ToggleUiModel(CapabilityId("switch.2"), "Switch 2", true, false)
    val third = ToggleUiModel(CapabilityId("switch.3"), "Switch 3", true, true)
    var emitted: DeviceIntent? = null

    setHost(
      device =
        device(
          StandardDeviceLayoutIds.GENERIC_CONTROLS,
          listOf(first, second, third),
        ),
      onIntent = { emitted = it },
    )

    composeRule.onNodeWithTag("compact_toggle_switch_1").assertExists()
    composeRule.onNodeWithTag("compact_toggle_switch_2").performClick()
    composeRule.onNodeWithTag("compact_toggle_switch_3").assertExists()
    composeRule.runOnIdle {
      assertEquals(DeviceIntent.SetToggle(DEVICE_ID, second.id, true), emitted)
    }
  }

  @Test
  fun lightRendererConsumesOnlySemanticPower() {
    val power = ToggleUiModel(CapabilityId("power"), "Power", true, true)
    val extra = ToggleUiModel(CapabilityId("auxiliary"), "Auxiliary", true, false)

    setHost(device(StandardDeviceLayoutIds.LIGHT, listOf(power, extra)))

    composeRule.onNodeWithTag("compact_toggle_power").assertExists()
    composeRule.onNodeWithTag("compact_toggle_auxiliary").assertDoesNotExist()
  }

  @Test
  fun coverRendererPreservesResolvedOpenStopCloseWireValues() {
    val actions =
      ActionGroupUiModel(
        id = CapabilityId("cover.actions"),
        label = "Cover",
        writable = true,
        currentWireValue = "00",
        actions =
          listOf(
            ChoiceUiOption("01", "Open"),
            ChoiceUiOption("00", "Stop"),
            ChoiceUiOption("02", "Close"),
          ),
      )
    var emitted: DeviceIntent? = null

    setHost(
      device(StandardDeviceLayoutIds.COVER, listOf(actions)),
      onIntent = { emitted = it },
    )

    composeRule.onNodeWithTag("compact_action_open").assertExists()
    composeRule.onNodeWithTag("compact_action_stop").performClick()
    composeRule.onNodeWithTag("compact_action_close").assertExists()
    composeRule.runOnIdle {
      assertEquals(DeviceIntent.InvokeAction(DEVICE_ID, actions.id, "00"), emitted)
    }
  }

  @Test
  fun duplicateCompactLayoutRegistrationsAreRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      CompactDeviceLayoutRendererRegistry(
        listOf(GenericToggleCompactLayoutRenderer, GenericToggleCompactLayoutRenderer)
      )
    }
  }

  private fun setHost(
    device: DeviceUiModel,
    controlState: DeviceControlUiState = DeviceControlUiState.Ready,
    onIntent: (DeviceIntent) -> Unit = {},
  ) {
    val registry =
      CompactDeviceLayoutRendererRegistry(
        listOf(
          GenericToggleCompactLayoutRenderer,
          LightPowerCompactLayoutRenderer,
          CoverActionsCompactLayoutRenderer,
        )
      )
    composeRule.setContent {
      MaterialTheme {
        CompactDeviceLayoutHost(
          device = device,
          registry = registry,
          controlState = controlState,
          onIntent = onIntent,
        )
      }
    }
  }

  private fun device(
    layoutId: com.prfd.tinytuya.device.core.profile.DeviceLayoutId,
    capabilities: List<CapabilityUiModel>,
  ): DeviceUiModel = DeviceUiModel(DEVICE_ID, layoutId, capabilities)

  private companion object {
    const val DEVICE_ID = "compact-device"
  }
}
