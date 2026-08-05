package com.prfd.tinytuya.device.profiles

import com.prfd.tinytuya.device.core.capability.ActionGroupCapabilitySpec
import com.prfd.tinytuya.device.core.capability.CapabilityAccess
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.CapabilityResolver
import com.prfd.tinytuya.device.core.capability.DeviceObservation
import com.prfd.tinytuya.device.core.capability.ObservedDataPointInput
import com.prfd.tinytuya.device.core.capability.ObservedDataPointKind
import com.prfd.tinytuya.device.core.capability.RangeCapabilitySpec
import com.prfd.tinytuya.device.core.capability.ResolvedActionGroup
import com.prfd.tinytuya.device.core.capability.ResolvedMeasurement
import com.prfd.tinytuya.device.core.capability.ResolvedRange
import com.prfd.tinytuya.device.core.schema.DpDefinitionInput
import com.prfd.tinytuya.device.core.schema.DpSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverDeviceProfileTest {
  @Test
  fun `alternate DPS IDs and version 2 codes resolve actions target and current position`() {
    val schema =
      schema(
        enumDefinition("101", "control_2", listOf("open", "stop", "close", "continue")),
        rangeDefinition("105", "percent_control_2", step = "5"),
        rangeDefinition("106", "percent_state_2"),
      )
    val capabilities =
      resolve(
        schema,
        observation(
          point("101", ObservedDataPointKind.STRING, "stop"),
          point("105", ObservedDataPointKind.INTEGER, "55"),
          point("106", ObservedDataPointKind.INTEGER, "60"),
        ),
      )

    val actions = capabilities.ofType<ResolvedActionGroup>().single()
    assertEquals("101", actions.dataPointId)
    assertEquals(listOf("open", "stop", "close"), actions.actions.map { it.wireValue })
    assertTrue(actions.writable)
    val target = capabilities.ofType<ResolvedRange>().single()
    assertEquals(CapabilityId("cover.position"), target.id)
    assertEquals("105", target.dataPointId)
    assertEquals("55%", target.displayValue)
    assertTrue(target.writable)
    val current = capabilities.ofType<ResolvedMeasurement>().single()
    assertEquals(CapabilityId("cover.position.reading"), current.id)
    assertEquals("60%", current.displayValue)
  }

  @Test
  fun `only upstream known vocabularies with open stop and close are declared`() {
    listOf(
        Vocabulary(listOf("open", "stop", "close"), listOf("open", "stop", "close")),
        Vocabulary(listOf("0", "1", "2"), listOf("1", "0", "2")),
        Vocabulary(listOf("00", "01", "02", "03"), listOf("01", "00", "02")),
        Vocabulary(listOf("on", "off", "stop"), listOf("on", "stop", "off")),
        Vocabulary(listOf("up", "down", "stop"), listOf("up", "stop", "down")),
        Vocabulary(listOf("ZZ", "FZ", "STOP"), listOf("ZZ", "STOP", "FZ")),
      )
      .forEach { vocabulary ->
        val specs = specs(schema(enumDefinition("7", "control", vocabulary.declaredValues)))
        val actions = specs.filterIsInstance<ActionGroupCapabilitySpec>().single()

        assertEquals(vocabulary.actionValues, actions.actions.map { action -> action.wireValue })
        assertEquals(
          listOf("Open", "Stop", "Close"),
          actions.actions.map { action -> action.label },
        )
        assertTrue(actions.writable)
      }
  }

  @Test
  fun `unknown incomplete malformed and ambiguous controls fail closed without a default`() {
    val schemas =
      listOf(
        schema(enumDefinition("1", "control", listOf("open", "close"))),
        schema(enumDefinition("1", "control", listOf("raise", "pause", "lower"))),
        schema(DpDefinitionInput("1", "control", "String")),
        schema(
          enumDefinition("1", "control", listOf("open", "stop", "close")),
          enumDefinition("2", "control", listOf("open", "stop", "close")),
        ),
      )

    schemas.forEach { candidate ->
      assertTrue(specs(candidate).filterIsInstance<ActionGroupCapabilitySpec>().isEmpty())
    }
  }

  @Test
  fun `position remains optional and malformed or stale writable evidence is rejected`() {
    val readOnlySchema = schema(rangeDefinition("9", "percent_state"))
    val readOnly =
      resolve(
        readOnlySchema,
        observation(point("9", ObservedDataPointKind.INTEGER, "42")),
      )
    assertTrue(readOnly.ofType<ResolvedRange>().isEmpty())
    assertEquals("42%", readOnly.ofType<ResolvedMeasurement>().single().displayValue)

    val malformedTarget =
      schema(
        DpDefinitionInput(
          id = "8",
          code = "percent_control",
          declaredType = "Integer",
          minimum = "0",
          maximum = "100",
          step = "0",
          scale = "0",
        )
      )
    assertTrue(
      resolve(
          malformedTarget,
          observation(point("8", ObservedDataPointKind.INTEGER, "50")),
        )
        .capabilities
        .isEmpty()
    )

    val completeSchema =
      schema(
        enumDefinition("1", "control", listOf("open", "stop", "close")),
        rangeDefinition("2", "percent_control"),
      )
    val stale =
      observation(
        point("1", ObservedDataPointKind.STRING, "stop"),
        point("2", ObservedDataPointKind.INTEGER, "50"),
        fresh = false,
      )
    assertTrue(resolve(completeSchema, stale).capabilities.isEmpty())
  }

  @Test
  fun `read only access cannot be promoted by writable cover specs`() {
    val schema = schema(enumDefinition("1", "control", listOf("open", "stop", "close")))
    val observation = observation(point("1", ObservedDataPointKind.STRING, "stop"))

    val readOnly =
      resolve(schema, observation, CapabilityAccess.READ_ONLY)
        .ofType<ResolvedActionGroup>()
        .single()
    assertFalse(readOnly.writable)
    assertTrue(resolve(schema, observation, CapabilityAccess.DENIED).capabilities.isEmpty())
  }

  private fun specs(schema: DpSchema) = coverDefinition.capabilitySpecs(schema)

  private fun resolve(
    schema: DpSchema,
    observation: DeviceObservation,
    access: CapabilityAccess = CapabilityAccess.READ_WRITE,
  ) = CapabilityResolver.resolve(specs(schema), schema, observation, access)

  private fun schema(vararg definitions: DpDefinitionInput) =
    DpSchema.normalize(definitions.toList())

  private fun enumDefinition(id: String, code: String, values: List<String>) =
    DpDefinitionInput(
      id = id,
      code = code,
      declaredType = "Enum",
      enumValues = values,
    )

  private fun rangeDefinition(id: String, code: String, step: String = "1") =
    DpDefinitionInput(
      id = id,
      code = code,
      declaredType = "Integer",
      minimum = "0",
      maximum = "100",
      step = step,
      scale = "0",
    )

  private fun observation(
    vararg points: ObservedDataPointInput,
    fresh: Boolean = true,
  ) = DeviceObservation.normalize(points.toList(), isFresh = fresh)

  private fun point(id: String, kind: ObservedDataPointKind, value: String) =
    ObservedDataPointInput(id, kind, value)

  private data class Vocabulary(
    val declaredValues: List<String>,
    val actionValues: List<String>,
  )

  private companion object {
    val coverDefinition =
      BuiltinDeviceFamilies.definitions.single { definition ->
        definition.id == BuiltinDeviceFamilyIds.COVER
      }
  }
}
