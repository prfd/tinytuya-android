package com.prfd.tinytuya.device.core.capability

import com.prfd.tinytuya.device.core.schema.DpDefinitionInput
import com.prfd.tinytuya.device.core.schema.DpSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityResolverTest {
  @Test
  fun `toggle resolves writable only from fresh matching schema observation and access`() {
    val spec =
      ToggleCapabilitySpec(
        CapabilityId("power"),
        "Power",
        listOf("switch"),
        writable = true,
      )
    val schema = schema(definition("7", "switch", "Boolean"))
    val observation = observation(point("7", ObservedDataPointKind.BOOLEAN, "true"))

    val writable = resolve(listOf(spec), schema, observation).ofType<ResolvedToggle>().single()
    val readOnly =
      resolve(
          listOf(spec),
          schema,
          observation,
          CapabilityAccess.READ_ONLY,
        )
        .ofType<ResolvedToggle>()
        .single()

    assertEquals(CapabilityId("power"), writable.id)
    assertEquals("7", writable.dataPointId)
    assertTrue(writable.currentValue)
    assertTrue(writable.writable)
    assertFalse(readOnly.writable)
    assertTrue(
      resolve(
          listOf(spec),
          schema,
          observation,
          CapabilityAccess.DENIED,
        )
        .capabilities
        .isEmpty()
    )
  }

  @Test
  fun `stale missing and wrong typed observations fail closed`() {
    val spec =
      ToggleCapabilitySpec(
        CapabilityId("power"),
        "Power",
        listOf("switch"),
        writable = true,
      )
    val schema = schema(definition("1", "switch", "Boolean"))

    assertTrue(resolve(listOf(spec), schema, DeviceObservation.empty()).capabilities.isEmpty())
    assertTrue(resolve(listOf(spec), schema, observation()).capabilities.isEmpty())
    assertTrue(
      resolve(
          listOf(spec),
          schema,
          observation(point("1", ObservedDataPointKind.STRING, "true")),
        )
        .capabilities
        .isEmpty()
    )
  }

  @Test
  fun `range requires exact bounded integer constraints and aligned current value`() {
    val spec =
      RangeCapabilitySpec(
        CapabilityId("light.brightness"),
        "Brightness",
        listOf("bright_value_v2"),
        writable = true,
        display = MeasurementDisplay.PERCENTAGE,
      )
    val validSchema =
      schema(
        definition(
          "22",
          "bright_value_v2",
          "Integer",
          minimum = "10",
          maximum = "1000",
          step = "10",
          scale = "0",
        )
      )

    val resolved =
      resolve(
          listOf(spec),
          validSchema,
          observation(point("22", ObservedDataPointKind.INTEGER, "730")),
        )
        .ofType<ResolvedRange>()
        .single()

    assertEquals("73%", resolved.displayValue)
    assertTrue(resolved.writable)
    assertTrue(
      resolve(
          listOf(spec),
          validSchema,
          observation(point("22", ObservedDataPointKind.INTEGER, "735")),
        )
        .capabilities
        .isEmpty()
    )
    assertTrue(
      resolve(
          listOf(spec),
          schema(
            definition(
              "22",
              "bright_value_v2",
              "Integer",
              minimum = "1000",
              maximum = "10",
              step = "1",
              scale = "0",
            )
          ),
          observation(point("22", ObservedDataPointKind.INTEGER, "730")),
        )
        .capabilities
        .isEmpty()
    )
  }

  @Test
  fun `choice rejects undeclared observed or required values`() {
    val spec =
      ChoiceCapabilitySpec(
        CapabilityId("light.mode"),
        "Mode",
        listOf("work_mode"),
        writable = true,
        choices =
          listOf(
            CapabilityChoice("white", "White"),
            CapabilityChoice("colour", "Colour"),
          ),
      )

    assertTrue(
      resolve(
          listOf(spec),
          schema(definition("21", "work_mode", "Enum", enums = listOf("white"))),
          observation(point("21", ObservedDataPointKind.STRING, "white")),
        )
        .capabilities
        .isEmpty()
    )
    val declaredButUnknown =
      resolve(
          listOf(spec),
          schema(
            definition(
              "21",
              "work_mode",
              "Enum",
              enums = listOf("white", "colour", "private_mode"),
            )
          ),
          observation(point("21", ObservedDataPointKind.STRING, "private_mode")),
        )
        .ofType<ResolvedChoice>()
        .single()
    assertNull(declaredButUnknown.currentWireValue)
    assertNull(declaredButUnknown.currentLabel)
    assertFalse(declaredButUnknown.toString().contains("private_mode"))
    assertTrue(
      resolve(
          listOf(spec),
          schema(
            definition(
              "21",
              "work_mode",
              "Enum",
              enums = listOf("white", "colour"),
            )
          ),
          observation(point("21", ObservedDataPointKind.STRING, "private")),
        )
        .capabilities
        .isEmpty()
    )
  }

  @Test
  fun `Tuya HSV v2 codec rejects malformed and out of range colors`() {
    assertEquals(TuyaHsvColor(360, 1000, 500), TuyaHsvV2CapabilityCodec.decode("016803e801f4"))
    assertEquals("016803e801f4", TuyaHsvV2CapabilityCodec.encode(TuyaHsvColor(360, 1000, 500)))
    assertNull(TuyaHsvV2CapabilityCodec.decode("016803e801f"))
    assertNull(TuyaHsvV2CapabilityCodec.decode("016903e801f4"))
    assertNull(TuyaHsvV2CapabilityCodec.decode("privatevalue"))
  }

  @Test
  fun `color and action group resolve through their declared codecs`() {
    val specs =
      listOf(
        ColorCapabilitySpec(
          CapabilityId("light.color"),
          "Color",
          listOf("colour_data_v2"),
          writable = true,
        ),
        ActionGroupCapabilitySpec(
          CapabilityId("cover.actions"),
          "Cover",
          listOf("control"),
          writable = true,
          actions =
            listOf(
              CapabilityChoice("open", "Open"),
              CapabilityChoice("stop", "Stop"),
              CapabilityChoice("close", "Close"),
            ),
        ),
      )
    val resolved =
      resolve(
        specs,
        schema(
          definition("24", "colour_data_v2", "Json"),
          definition(
            "1",
            "control",
            "Enum",
            enums = listOf("open", "stop", "close"),
          ),
        ),
        observation(
          point("24", ObservedDataPointKind.STRING, "012c02bc01f4"),
          point("1", ObservedDataPointKind.STRING, "stop"),
        ),
      )

    assertEquals(
      TuyaHsvColor(300, 700, 500),
      resolved.ofType<ResolvedColor>().single().currentColor,
    )
    assertEquals("stop", resolved.ofType<ResolvedActionGroup>().single().currentWireValue)
    assertTrue(resolved.capabilities.all(ResolvedCapability::writable))
  }

  @Test
  fun `read only primitives format bounded values and known state semantics`() {
    val specs =
      listOf(
        MeasurementCapabilitySpec(
          CapabilityId("sensor.temperature"),
          "Temperature",
          listOf("temp_current"),
          unitPolicy = MeasurementUnitPolicy.TEMPERATURE,
        ),
        BinaryStateCapabilitySpec(
          CapabilityId("sensor.contact"),
          "Contact",
          listOf("doorcontact_state"),
          states =
            listOf(
              CapabilityState("false", "Closed", CapabilityTone.NORMAL),
              CapabilityState("true", "Open", CapabilityTone.ACTIVE),
            ),
        ),
        SafeTextCapabilitySpec(
          CapabilityId("display.message"),
          "Message",
          listOf("display_message"),
          allowedValues = listOf("Ready"),
        ),
      )
    val schema =
      schema(
        definition(
          "1",
          "temp_current",
          "Integer",
          minimum = "-100",
          maximum = "600",
          scale = "1",
          unit = "℃",
        ),
        definition("2", "doorcontact_state", "Boolean"),
        definition("3", "display_message", "String"),
      )
    val capabilities =
      resolve(
        specs,
        schema,
        observation(
          point("1", ObservedDataPointKind.INTEGER, "217"),
          point("2", ObservedDataPointKind.BOOLEAN, "true"),
          point("3", ObservedDataPointKind.STRING, "Ready"),
        ),
      )

    assertEquals("21.7 °C", capabilities.ofType<ResolvedMeasurement>().single().displayValue)
    assertEquals(CapabilityTone.ACTIVE, capabilities.ofType<ResolvedBinaryState>().single().tone)
    assertEquals("Open", capabilities.ofType<ResolvedBinaryState>().single().value)
    assertEquals("Ready", capabilities.ofType<ResolvedSafeText>().single().value)
    assertTrue(
      resolve(
          specs,
          schema,
          observation(
            point("1", ObservedDataPointKind.INTEGER, "217"),
            point("2", ObservedDataPointKind.BOOLEAN, "true"),
            point("3", ObservedDataPointKind.STRING, "Private message"),
          ),
        )
        .ofType<ResolvedSafeText>()
        .isEmpty()
    )
  }

  @Test
  fun `duplicate bindings ids and oversized inputs cannot be selected by ordering`() {
    val spec =
      ToggleCapabilitySpec(
        CapabilityId("power"),
        "Power",
        listOf("switch"),
        writable = true,
      )
    val duplicateCodeSchema =
      schema(
        definition("1", "switch", "Boolean"),
        definition("2", "switch", "Boolean"),
      )
    assertTrue(
      resolve(
          listOf(spec),
          duplicateCodeSchema,
          observation(
            point("1", ObservedDataPointKind.BOOLEAN, "true"),
            point("2", ObservedDataPointKind.BOOLEAN, "false"),
          ),
        )
        .capabilities
        .isEmpty()
    )
    assertTrue(
      resolve(
          listOf(spec, spec),
          schema(definition("1", "switch", "Boolean")),
          observation(point("1", ObservedDataPointKind.BOOLEAN, "true")),
        )
        .capabilities
        .isEmpty()
    )

    val oversized =
      DeviceObservation.normalize(
        (1..(DeviceObservation.MAX_DATA_POINT_COUNT + 1)).map { id ->
          point(id.toString(), ObservedDataPointKind.BOOLEAN, "true")
        },
        isFresh = true,
      )
    assertTrue(oversized.rejectedAsOversized)
    assertTrue(
      resolve(
          listOf(spec),
          schema(definition("1", "switch", "Boolean")),
          oversized,
        )
        .capabilities
        .isEmpty()
    )
  }

  private fun resolve(
    specs: List<CapabilitySpec>,
    schema: DpSchema,
    observation: DeviceObservation,
    access: CapabilityAccess = CapabilityAccess.READ_WRITE,
  ): ResolvedDeviceCapabilities = CapabilityResolver.resolve(specs, schema, observation, access)

  private fun schema(vararg definitions: DpDefinitionInput): DpSchema =
    DpSchema.normalize(definitions.toList())

  private fun observation(vararg points: ObservedDataPointInput): DeviceObservation =
    DeviceObservation.normalize(points.toList(), isFresh = true)

  private fun point(
    id: String,
    kind: ObservedDataPointKind,
    value: String,
  ) = ObservedDataPointInput(id, kind, value)

  private fun definition(
    id: String,
    code: String,
    type: String,
    minimum: String? = null,
    maximum: String? = null,
    step: String? = null,
    scale: String? = null,
    unit: String? = null,
    enums: List<String> = emptyList(),
  ) =
    DpDefinitionInput(
      id = id,
      code = code,
      declaredType = type,
      minimum = minimum,
      maximum = maximum,
      step = step,
      scale = scale,
      unit = unit,
      enumValues = enums,
    )
}
