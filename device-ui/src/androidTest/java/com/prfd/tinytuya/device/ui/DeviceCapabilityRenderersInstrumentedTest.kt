package com.prfd.tinytuya.device.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.prfd.tinytuya.device.core.capability.CapabilityAccess
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.CapabilityResolver
import com.prfd.tinytuya.device.core.capability.CapabilityTone
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.core.capability.DeviceObservation
import com.prfd.tinytuya.device.core.capability.ObservedDataPointInput
import com.prfd.tinytuya.device.core.capability.ObservedDataPointKind
import com.prfd.tinytuya.device.core.capability.ResolvedDevice
import com.prfd.tinytuya.device.core.capability.ResolvedDeviceCapabilities
import com.prfd.tinytuya.device.core.capability.ToggleCapabilitySpec
import com.prfd.tinytuya.device.core.capability.TuyaHsvColor
import com.prfd.tinytuya.device.core.profile.DeviceLayoutId
import com.prfd.tinytuya.device.core.schema.DpDefinitionInput
import com.prfd.tinytuya.device.core.schema.DpSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DeviceCapabilityRenderersInstrumentedTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun mapperProducesRedactedSafeModelAndToggleEmitsSemanticIntent() {
    val capabilities =
      CapabilityResolver.resolve(
        specs =
          listOf(
            ToggleCapabilitySpec(
              id = CapabilityId("power"),
              label = "Power",
              codeCandidates = listOf("switch"),
              writable = true,
            )
          ),
        dpSchema = DpSchema.normalize(listOf(DpDefinitionInput("19", "switch", "Boolean"))),
        observation =
          DeviceObservation.normalize(
            listOf(ObservedDataPointInput("19", ObservedDataPointKind.BOOLEAN, "true")),
            isFresh = true,
          ),
        access = CapabilityAccess.READ_WRITE,
      )
    val model =
      DeviceUiMapper.map(
        ResolvedDevice.create(
          deviceId = SECRET_DEVICE_ID,
          layoutId = DeviceLayoutId("generic.controls"),
          capabilities = capabilities,
        )
      )
    var emitted: DeviceIntent? = null

    composeRule.setContent {
      MaterialTheme {
        DeviceCapabilityList(
          device = model,
          controlState = DeviceControlUiState.Ready,
          onIntent = { emitted = it },
        )
      }
    }

    assertEquals(DeviceLayoutId("generic.controls"), model.layoutId)
    assertEquals(listOf(CapabilityId("power")), model.capabilities.map { it.id })
    assertFalse(model.toString().contains(SECRET_DEVICE_ID))
    assertFalse(model.toString().contains("19"))
    composeRule.onNodeWithTag("capability_toggle_power").assertIsOn().performClick()
    composeRule.runOnIdle {
      assertEquals(
        DeviceIntent.SetToggle(SECRET_DEVICE_ID, CapabilityId("power"), false),
        emitted,
      )
    }
  }

  @Test
  fun toggleShowsTargetedErrorState() {
    val capability = ToggleUiModel(CapabilityId("power"), "Power", true, true)
    val intent = DeviceIntent.SetToggle(SECRET_DEVICE_ID, capability.id, false)

    composeRule.setContent {
      MaterialTheme {
        ToggleCapability(
          deviceId = SECRET_DEVICE_ID,
          capability = capability,
          controlState =
            DeviceControlUiState.Error(
              intent = intent,
              code = "LOCAL_CONTROL_FAILED",
              message = "The device did not confirm the request.",
            ),
          onIntent = {},
        )
      }
    }

    composeRule.onNodeWithTag("capability_toggle_power").assertIsDisplayed()
    composeRule.onNodeWithText("The device did not confirm the request.").assertIsDisplayed()
  }

  @Test
  fun rangeRendererAlignsAndEmitsSemanticIntent() {
    val capability =
      RangeUiModel(
        CapabilityId("level"),
        "Level",
        true,
        0,
        100,
        10,
        50,
        "50%",
      )
    var emitted: DeviceIntent? = null
    composeRule.setContent {
      MaterialTheme {
        RangeCapability(
          deviceId = SECRET_DEVICE_ID,
          capability = capability,
          controlState = DeviceControlUiState.Ready,
          onIntent = { emitted = it },
        )
      }
    }

    composeRule.onNodeWithTag("capability_range_level").performTouchInput {
      swipe(
        start = Offset(width / 2f, height / 2f),
        end = Offset(width * 0.82f, height / 2f),
        durationMillis = 250,
      )
    }
    composeRule.runOnIdle {
      val intent = emitted as DeviceIntent.SetRange
      assertEquals(CapabilityId("level"), intent.capabilityId)
      assertEquals(0, intent.value % 10)
      assertTrue(intent.value > 50)
    }
  }

  @Test
  fun rangeRendererShowsTicksOnlyForCoarseRanges() {
    assertEquals(9, rangeSliderVisualSteps(intervals = 10))
    assertEquals(0, rangeSliderVisualSteps(intervals = 100))
    assertEquals(0, rangeSliderVisualSteps(intervals = 2_000_000_000))
  }

  @Test
  fun rangeRendererNormalizesLargeIntegerBoundsWithoutFloatRangeCollapse() {
    val capability =
      RangeUiModel(
        CapabilityId("wide.level"),
        "Wide level",
        true,
        -1_000_000_000,
        1_000_000_000,
        1,
        -999_999_999,
        "-999999999",
      )
    var emitted: DeviceIntent? = null
    composeRule.setContent {
      MaterialTheme {
        RangeCapability(
          deviceId = SECRET_DEVICE_ID,
          capability = capability,
          controlState = DeviceControlUiState.Ready,
          onIntent = { emitted = it },
        )
      }
    }

    composeRule.onNodeWithTag("capability_range_wide_level").performTouchInput {
      swipe(
        start = Offset(width * 0.1f, height / 2f),
        end = Offset(width * 0.75f, height / 2f),
        durationMillis = 250,
      )
    }
    composeRule.runOnIdle {
      val intent = emitted as DeviceIntent.SetRange
      assertTrue(intent.value in capability.minimum..capability.maximum)
    }
  }

  @Test
  fun choiceAndActionRenderersEmitTheirDistinctIntentKinds() {
    val choice =
      ChoiceUiModel(
        CapabilityId("mode"),
        "Mode",
        true,
        "auto",
        "Auto",
        listOf(ChoiceUiOption("auto", "Auto"), ChoiceUiOption("manual", "Manual")),
      )
    val actions =
      ActionGroupUiModel(
        CapabilityId("cover.actions"),
        "Cover",
        true,
        null,
        listOf(ChoiceUiOption("open", "Open"), ChoiceUiOption("close", "Close")),
      )
    var emitted: DeviceIntent? = null
    composeRule.setContent {
      MaterialTheme {
        Column {
          ChoiceCapability(
            SECRET_DEVICE_ID,
            choice,
            DeviceControlUiState.Ready,
            { emitted = it },
          )
          ActionGroupCapability(
            SECRET_DEVICE_ID,
            actions,
            DeviceControlUiState.Ready,
            { emitted = it },
          )
        }
      }
    }

    composeRule.onNodeWithText("Manual").performClick()
    composeRule.runOnIdle {
      assertEquals(
        DeviceIntent.SetChoice(SECRET_DEVICE_ID, choice.id, "manual"),
        emitted,
      )
    }
    composeRule.onNodeWithText("Open").performClick()
    composeRule.runOnIdle {
      assertEquals(
        DeviceIntent.InvokeAction(SECRET_DEVICE_ID, actions.id, "open"),
        emitted,
      )
    }
  }

  @Test
  fun colorRendererEmitsBoundedHsvIntent() {
    val capability =
      ColorUiModel(
        CapabilityId("light.color"),
        "Color",
        true,
        120,
        600,
        700,
      )
    var emitted: DeviceIntent? = null
    composeRule.setContent {
      MaterialTheme {
        ColorCapability(
          SECRET_DEVICE_ID,
          capability,
          DeviceControlUiState.Ready,
          { emitted = it },
        )
      }
    }

    composeRule.onNodeWithContentDescription("Hue").performTouchInput {
      swipe(
        start = Offset(width / 3f, height / 2f),
        end = Offset(width * 0.7f, height / 2f),
        durationMillis = 250,
      )
    }
    composeRule.runOnIdle {
      val intent = emitted as DeviceIntent.SetColor
      assertEquals(capability.id, intent.capabilityId)
      assertTrue(intent.color.hue in 0..360)
      assertEquals(TuyaHsvColor(intent.color.hue, 600, 700), intent.color)
    }
  }

  @Test
  fun readOnlyRenderersExposeOnlyFormattedSafeValues() {
    composeRule.setContent {
      MaterialTheme {
        Column {
          MeasurementCapability(
            MeasurementUiModel(CapabilityId("temperature"), "Temperature", "21.5 °C")
          )
          BinaryStateCapability(
            BinaryStateUiModel(
              CapabilityId("contact"),
              "Contact",
              "Closed",
              CapabilityTone.NORMAL,
            )
          )
          SafeTextCapability(SafeTextUiModel(CapabilityId("preset"), "Preset", "Home"))
        }
      }
    }

    composeRule.onNodeWithTag("capability_measurement_temperature").assertIsDisplayed()
    composeRule.onNodeWithText("21.5 °C").assertIsDisplayed()
    composeRule.onNodeWithTag("capability_binary_contact").assertIsDisplayed()
    composeRule.onNodeWithText("Closed").assertIsDisplayed()
    composeRule.onNodeWithTag("capability_text_preset").assertIsDisplayed()
    composeRule.onNodeWithText("Home").assertIsDisplayed()
  }

  @Test
  fun genericCardProvidesSafeEmptyFallback() {
    val model =
      DeviceUiMapper.map(
        ResolvedDevice.create(
          SECRET_DEVICE_ID,
          DeviceLayoutId("generic.controls"),
          ResolvedDeviceCapabilities.EMPTY,
        )
      )
    composeRule.setContent {
      MaterialTheme {
        GenericDeviceCard(
          title = "Unknown device",
          device = model,
          controlState = DeviceControlUiState.Unavailable,
          onIntent = {},
        )
      }
    }

    composeRule.onNodeWithTag("device_capability_fallback").assertIsDisplayed()
    composeRule
      .onNodeWithText("No compatible controls or readings are available for this device profile.")
      .assertIsDisplayed()
  }

  private companion object {
    const val SECRET_DEVICE_ID = "private-device-route"
  }
}
