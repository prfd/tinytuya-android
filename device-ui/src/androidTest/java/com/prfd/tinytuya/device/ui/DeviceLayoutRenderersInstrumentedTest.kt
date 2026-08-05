package com.prfd.tinytuya.device.ui

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.CapabilityTone
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.core.profile.DeviceLayoutId
import com.prfd.tinytuya.device.core.profile.StandardDeviceLayoutIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DeviceLayoutRenderersInstrumentedTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun absentRendererFallsBackToEveryAtomicCapability() {
    val device =
      device(
        layoutId = DeviceLayoutId("custom.absent"),
        capabilities = listOf(power()),
      )

    setHost(device, DeviceLayoutRendererRegistry.EMPTY)

    composeRule.onNodeWithTag("capability_toggle_power").assertIsOn()
  }

  @Test
  fun throwingOrInvalidPreparationFallsBackWithoutEnteringCustomContent() {
    val layoutId = DeviceLayoutId("custom.broken")
    val throwingRenderer =
      testRenderer(
        layoutId = layoutId,
        prepare = { error("fixture preparation failure") },
      )
    val invalidRenderer =
      testRenderer(
        layoutId = layoutId,
        prepare = { setOf(CapabilityId("missing.capability")) },
      )
    val device = device(layoutId, listOf(power()))

    setHost(device, DeviceLayoutRendererRegistry(listOf(throwingRenderer)))
    composeRule.onNodeWithTag("capability_toggle_power").assertExists()
    composeRule.onNodeWithText("Custom content").assertDoesNotExist()

    composeRule.runOnIdle {
      assertEquals(null, DeviceLayoutRendererRegistry(listOf(invalidRenderer)).prepare(device))
    }
  }

  @Test
  fun customRendererUsesSafeModelAndLeavesUnknownCapabilitiesToAtomicFallback() {
    val layoutId = DeviceLayoutId("custom.thermostat")
    val targetId = CapabilityId("target.temperature")
    val measurementId = CapabilityId("room.temperature")
    val customRenderer =
      object : DeviceLayoutRenderer {
        override val layoutId = layoutId

        override fun prepare(device: DeviceUiModel): Set<CapabilityId> = setOf(targetId)

        @Composable
        override fun Content(
          device: DeviceUiModel,
          controlState: DeviceControlUiState,
          onIntent: (DeviceIntent) -> Unit,
          modifier: Modifier,
        ) {
          Button(
            onClick = { onIntent(DeviceIntent.SetRange(device.deviceId, targetId, 22)) },
            modifier = modifier.testTag("custom_thermostat"),
          ) {
            Text("Custom thermostat")
          }
        }
      }
    val device =
      device(
        layoutId,
        listOf(
          RangeUiModel(targetId, "Target", true, 10, 30, 1, 21, "21 °C"),
          MeasurementUiModel(measurementId, "Room", "20 °C"),
        ),
      )
    var emitted: DeviceIntent? = null

    setHost(
      device = device,
      registry = DeviceLayoutRendererRegistry(listOf(customRenderer)),
      onIntent = { emitted = it },
    )

    composeRule.onNodeWithTag("custom_thermostat").performClick()
    composeRule.onNodeWithTag("capability_measurement_room_temperature").assertExists()
    composeRule.onNodeWithTag("capability_range_target_temperature").assertDoesNotExist()
    composeRule.runOnIdle { assertEquals(DeviceIntent.SetRange(DEVICE_ID, targetId, 22), emitted) }
  }

  @Test
  fun sensorSummaryArrangesPrimaryAndSecondarySafeReadings() {
    val device =
      device(
        StandardDeviceLayoutIds.SENSOR_SUMMARY,
        listOf(
          BinaryStateUiModel(
            CapabilityId("sensor.water"),
            "Water",
            "Leak detected",
            CapabilityTone.ALERT,
          ),
          MeasurementUiModel(CapabilityId("sensor.battery"), "Battery", "87%"),
        ),
      )

    setHost(
      device,
      DeviceLayoutRendererRegistry(listOf(SensorSummaryLayoutRenderer)),
    )

    composeRule.onNodeWithTag("local_sensor_summary").assertExists()
    composeRule.onNodeWithText("Current reading").assertExists()
    composeRule.onNodeWithText("Leak detected").assertExists()
    composeRule.onNodeWithText("87%").assertExists()
    composeRule.onNodeWithTag("capability_binary_sensor_water").assertDoesNotExist()
  }

  @Test
  fun lightLayoutConsumesCompoundCapabilitiesAndEmitsSemanticModeIntent() {
    val device =
      device(
        StandardDeviceLayoutIds.LIGHT,
        listOf(
          power(),
          ChoiceUiModel(
            CapabilityId("light.mode"),
            "Mode",
            true,
            "white",
            "White",
            listOf(
              ChoiceUiOption("white", "White"),
              ChoiceUiOption("colour", "Color"),
            ),
          ),
          RangeUiModel(
            CapabilityId("light.brightness"),
            "Brightness",
            true,
            10,
            1_000,
            10,
            730,
            "73%",
          ),
          RangeUiModel(
            CapabilityId("light.temperature"),
            "Color temperature",
            true,
            0,
            1_000,
            10,
            420,
            "42%",
          ),
          MeasurementUiModel(
            CapabilityId("light.brightness.reading"),
            "Brightness",
            "73%",
          ),
        ),
      )
    var emitted: DeviceIntent? = null

    setHost(
      device,
      DeviceLayoutRendererRegistry(listOf(LightDeviceLayoutRenderer)),
      onIntent = { emitted = it },
    )

    composeRule.onNodeWithTag("capability_toggle_power").assertIsOn()
    composeRule.onNodeWithTag("light_controls").assertExists()
    composeRule.onNodeWithTag("light_slider_light_brightness").assertExists()
    composeRule
      .onNodeWithTag("capability_measurement_light_brightness_reading")
      .assertDoesNotExist()
    composeRule.onNodeWithTag("light_mode_color").performClick()
    composeRule.runOnIdle {
      assertEquals(
        DeviceIntent.SetChoice(DEVICE_ID, CapabilityId("light.mode"), "colour"),
        emitted,
      )
    }
  }

  @Test
  fun lightRangeSliderTapEmitsSemanticIntentWithoutWaitingForRecomposition() {
    val brightnessId = CapabilityId("light.brightness")
    val device =
      lightDevice(
        mode = "white",
        brightnessCapability =
          RangeUiModel(
            brightnessId,
            "Brightness",
            true,
            10,
            1_000,
            10,
            730,
            "73%",
          ),
      )
    var emitted: DeviceIntent? = null

    setHost(
      device,
      DeviceLayoutRendererRegistry(listOf(LightDeviceLayoutRenderer)),
      onIntent = { emitted = it },
    )

    composeRule.onNodeWithTag("light_slider_light_brightness").performTouchInput {
      click(Offset(width * 0.25f, height / 2f))
    }
    composeRule.runOnIdle {
      val intent = emitted as DeviceIntent.SetRange
      assertEquals(brightnessId, intent.capabilityId)
      assertTrue(intent.value < 730)
    }
  }

  @Test
  fun lightColorBrightnessSliderTapEmitsSemanticIntentWithoutWaitingForRecomposition() {
    val colorId = CapabilityId("light.color")
    val device =
      lightDevice(
        mode = "colour",
        colorCapability = ColorUiModel(colorId, "Color", true, 120, 600, 700),
      )
    var emitted: DeviceIntent? = null

    setHost(
      device,
      DeviceLayoutRendererRegistry(listOf(LightDeviceLayoutRenderer)),
      onIntent = { emitted = it },
    )

    composeRule.onNodeWithTag("light_slider_color_brightness").performTouchInput {
      click(Offset(width * 0.25f, height / 2f))
    }
    composeRule.runOnIdle {
      val intent = emitted as DeviceIntent.SetColor
      assertEquals(colorId, intent.capabilityId)
      assertTrue(intent.color.brightness < 700)
    }
  }

  @Test
  fun lightLayoutRejectsAnIncompatibleModeVocabulary() {
    val incompatible =
      device(
        StandardDeviceLayoutIds.LIGHT,
        listOf(
          power(),
          ChoiceUiModel(
            CapabilityId("light.mode"),
            "Mode",
            true,
            "scene",
            "Scene",
            listOf(ChoiceUiOption("scene", "Scene")),
          ),
        ),
      )

    assertEquals(
      null,
      DeviceLayoutRendererRegistry(listOf(LightDeviceLayoutRenderer)).prepare(incompatible),
    )
  }

  @Test
  fun coverLayoutReusesActionsRangeAndReadingAndEmitsSemanticIntent() {
    val actionsId = CapabilityId("cover.actions")
    val device =
      device(
        StandardDeviceLayoutIds.COVER,
        listOf(
          ActionGroupUiModel(
            actionsId,
            "Cover",
            true,
            "stop",
            listOf(
              ChoiceUiOption("open", "Open"),
              ChoiceUiOption("stop", "Stop"),
              ChoiceUiOption("close", "Close"),
            ),
          ),
          RangeUiModel(
            CapabilityId("cover.position"),
            "Target position",
            true,
            0,
            100,
            1,
            62,
            "62%",
          ),
          MeasurementUiModel(
            CapabilityId("cover.position.reading"),
            "Current position",
            "60%",
          ),
        ),
      )
    var emitted: DeviceIntent? = null

    setHost(
      device,
      DeviceLayoutRendererRegistry(listOf(CoverDeviceLayoutRenderer)),
      onIntent = { emitted = it },
    )

    composeRule.onNodeWithText("Cover controls").assertExists()
    composeRule.onNodeWithTag("capability_actions_cover_actions").assertExists()
    composeRule.onNodeWithTag("capability_range_cover_position").assertExists()
    composeRule.onNodeWithTag("capability_measurement_cover_position_reading").assertExists()
    composeRule.onNodeWithText("Open").performClick()
    composeRule.runOnIdle {
      assertEquals(DeviceIntent.InvokeAction(DEVICE_ID, actionsId, "open"), emitted)
    }
  }

  @Test
  fun duplicateRendererIdsAreRejectedAtRegistryAssembly() {
    val layoutId = DeviceLayoutId("custom.duplicate")
    val first = testRenderer(layoutId) { setOf(CapabilityId("power")) }
    val second = testRenderer(layoutId) { setOf(CapabilityId("power")) }

    assertThrows(IllegalArgumentException::class.java) {
      DeviceLayoutRendererRegistry(listOf(first, second))
    }
  }

  private fun setHost(
    device: DeviceUiModel,
    registry: DeviceLayoutRendererRegistry,
    onIntent: (DeviceIntent) -> Unit = {},
  ) {
    composeRule.setContent {
      MaterialTheme {
        DeviceLayoutHost(
          device = device,
          registry = registry,
          controlState = DeviceControlUiState.Ready,
          onIntent = onIntent,
        )
      }
    }
  }

  private fun testRenderer(
    layoutId: DeviceLayoutId,
    prepare: (DeviceUiModel) -> Set<CapabilityId>?,
  ): DeviceLayoutRenderer =
    object : DeviceLayoutRenderer {
      override val layoutId = layoutId

      override fun prepare(device: DeviceUiModel): Set<CapabilityId>? = prepare(device)

      @Composable
      override fun Content(
        device: DeviceUiModel,
        controlState: DeviceControlUiState,
        onIntent: (DeviceIntent) -> Unit,
        modifier: Modifier,
      ) {
        Text("Custom content")
      }
    }

  private fun device(
    layoutId: DeviceLayoutId,
    capabilities: List<CapabilityUiModel>,
  ) = DeviceUiModel(DEVICE_ID, layoutId, capabilities)

  private fun power() = ToggleUiModel(CapabilityId("power"), "Power", true, true)

  private fun lightDevice(
    mode: String,
    brightnessCapability: RangeUiModel? = null,
    colorCapability: ColorUiModel? = null,
  ) =
    device(
      StandardDeviceLayoutIds.LIGHT,
      buildList {
        add(power())
        add(
          ChoiceUiModel(
            CapabilityId("light.mode"),
            "Mode",
            true,
            mode,
            if (mode == "white") "White" else "Color",
            listOf(
              ChoiceUiOption("white", "White"),
              ChoiceUiOption("colour", "Color"),
            ),
          )
        )
        brightnessCapability?.let(::add)
        colorCapability?.let(::add)
      },
    )

  private companion object {
    const val DEVICE_ID = "private-device-route"
  }
}
