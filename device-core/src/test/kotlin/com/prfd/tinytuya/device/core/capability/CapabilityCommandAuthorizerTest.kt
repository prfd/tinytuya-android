package com.prfd.tinytuya.device.core.capability

import com.prfd.tinytuya.device.core.schema.DpDefinitionInput
import com.prfd.tinytuya.device.core.schema.DpSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilityCommandAuthorizerTest {
  @Test
  fun `semantic intents encode bounded primitive writes without caller DPS`() {
    val capabilities = resolveWritableCapabilities()
    val intents =
      listOf(
        DeviceIntent.SetToggle(DEVICE_ID, CapabilityId("power"), false),
        DeviceIntent.SetRange(DEVICE_ID, CapabilityId("light.brightness"), 500),
        DeviceIntent.SetChoice(DEVICE_ID, CapabilityId("light.mode"), "colour"),
        DeviceIntent.SetColor(
          DEVICE_ID,
          CapabilityId("light.color"),
          TuyaHsvColor(300, 700, 500),
        ),
        DeviceIntent.InvokeAction(DEVICE_ID, CapabilityId("cover.actions"), "open"),
      )

    assertEquals(
      listOf(
        PrimitiveCapabilityWrite("20", PrimitiveWriteKind.BOOLEAN, "false"),
        PrimitiveCapabilityWrite("22", PrimitiveWriteKind.INTEGER, "500"),
        PrimitiveCapabilityWrite("21", PrimitiveWriteKind.STRING, "colour"),
        PrimitiveCapabilityWrite("24", PrimitiveWriteKind.STRING, "012c02bc01f4"),
        PrimitiveCapabilityWrite("30", PrimitiveWriteKind.STRING, "open"),
      ),
      intents.map { intent ->
        CapabilityCommandAuthorizer.authorize(capabilities, intent)?.writes?.single()
      },
    )
    assertFalse(intents.joinToString().contains(DEVICE_ID))
  }

  @Test
  fun `forged capability type and semantic values fail closed`() {
    val capabilities = resolveWritableCapabilities()

    listOf(
        DeviceIntent.SetToggle(DEVICE_ID, CapabilityId("missing"), true),
        DeviceIntent.SetToggle(DEVICE_ID, CapabilityId("light.brightness"), true),
        DeviceIntent.SetRange(DEVICE_ID, CapabilityId("light.brightness"), 1_001),
        DeviceIntent.SetChoice(DEVICE_ID, CapabilityId("light.mode"), "private_mode"),
        DeviceIntent.SetColor(
          DEVICE_ID,
          CapabilityId("light.color"),
          TuyaHsvColor(361, 700, 500),
        ),
      )
      .forEach { intent -> assertNull(CapabilityCommandAuthorizer.authorize(capabilities, intent)) }
  }

  @Test
  fun `read only resolution cannot be promoted by an intent`() {
    val writable = resolveWritableCapabilities()
    val readOnly =
      CapabilityResolver.resolve(
        specs = SPECS,
        dpSchema = SCHEMA,
        observation = OBSERVATION,
        access = CapabilityAccess.READ_ONLY,
      )

    assertEquals(5, writable.capabilities.count(ResolvedCapability::writable))
    assertNull(
      CapabilityCommandAuthorizer.authorize(
        readOnly,
        DeviceIntent.SetToggle(DEVICE_ID, CapabilityId("power"), false),
      )
    )
  }

  private fun resolveWritableCapabilities(): ResolvedDeviceCapabilities =
    CapabilityResolver.resolve(
      specs = SPECS,
      dpSchema = SCHEMA,
      observation = OBSERVATION,
      access = CapabilityAccess.READ_WRITE,
    )

  private companion object {
    const val DEVICE_ID = "semantic-command-fixture"
    val SPECS =
      listOf(
        ToggleCapabilitySpec(CapabilityId("power"), "Power", listOf("switch_led"), true),
        RangeCapabilitySpec(
          CapabilityId("light.brightness"),
          "Brightness",
          listOf("bright_value_v2"),
          true,
        ),
        ChoiceCapabilitySpec(
          CapabilityId("light.mode"),
          "Mode",
          listOf("work_mode"),
          true,
          listOf(
            CapabilityChoice("white", "White"),
            CapabilityChoice("colour", "Colour"),
          ),
        ),
        ColorCapabilitySpec(
          CapabilityId("light.color"),
          "Color",
          listOf("colour_data_v2"),
          true,
        ),
        ActionGroupCapabilitySpec(
          CapabilityId("cover.actions"),
          "Cover",
          listOf("control"),
          true,
          listOf(
            CapabilityChoice("open", "Open"),
            CapabilityChoice("stop", "Stop"),
            CapabilityChoice("close", "Close"),
          ),
        ),
      )
    val SCHEMA =
      DpSchema.normalize(
        listOf(
          DpDefinitionInput("20", "switch_led", "Boolean"),
          DpDefinitionInput(
            "22",
            "bright_value_v2",
            "Integer",
            minimum = "10",
            maximum = "1000",
            step = "1",
            scale = "0",
          ),
          DpDefinitionInput(
            "21",
            "work_mode",
            "Enum",
            enumValues = listOf("white", "colour"),
          ),
          DpDefinitionInput("24", "colour_data_v2", "Json"),
          DpDefinitionInput(
            "30",
            "control",
            "Enum",
            enumValues = listOf("open", "stop", "close"),
          ),
        )
      )
    val OBSERVATION =
      DeviceObservation.normalize(
        listOf(
          ObservedDataPointInput("20", ObservedDataPointKind.BOOLEAN, "true"),
          ObservedDataPointInput("22", ObservedDataPointKind.INTEGER, "730"),
          ObservedDataPointInput("21", ObservedDataPointKind.STRING, "white"),
          ObservedDataPointInput("24", ObservedDataPointKind.STRING, "000003e803e8"),
          ObservedDataPointInput("30", ObservedDataPointKind.STRING, "stop"),
        ),
        isFresh = true,
      )
  }
}
