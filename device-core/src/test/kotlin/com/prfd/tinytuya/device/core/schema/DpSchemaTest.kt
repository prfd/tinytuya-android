package com.prfd.tinytuya.device.core.schema

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DpSchemaTest {
  @Test
  fun `normalizes definitions constraints and stable numeric ordering`() {
    val schema =
      DpSchema.normalize(
        listOf(
          DpDefinitionInput(
            id = "20",
            code = " TEMP_CURRENT ",
            declaredType = " Value ",
            minimum = "-100",
            maximum = "600",
            step = "1",
            scale = "1",
            unit = " °C ",
          ),
          DpDefinitionInput(
            id = "2",
            code = "work_mode",
            declaredType = "ENUM",
            enumValues = listOf("white", "colour", "white"),
          ),
        )
      )

    assertEquals(listOf("2", "20"), schema.definitions.map(DpDefinition::id))
    assertEquals(DpDeclaredType.ENUM, schema["2"]?.declaredType)
    assertEquals(listOf("white", "colour"), schema["2"]?.constraints?.enumValues)
    assertEquals("temp_current", schema["20"]?.code)
    assertEquals(DpDeclaredType.VALUE, schema["20"]?.declaredType)
    assertEquals(BigDecimal("-100"), schema["20"]?.constraints?.minimum)
    assertEquals(BigDecimal("600"), schema["20"]?.constraints?.maximum)
    assertEquals(BigDecimal.ONE, schema["20"]?.constraints?.step)
    assertEquals(1, schema["20"]?.constraints?.scale)
    assertEquals("°C", schema["20"]?.constraints?.unit)
    assertFalse(schema.rejectedAsOversized)
  }

  @Test
  fun `keeps a definition but removes unsafe optional metadata`() {
    val schema =
      DpSchema.normalize(
        listOf(
          DpDefinitionInput(
            id = "1",
            code = "../../secret",
            declaredType = "new-vendor-type",
            minimum = "1e100",
            maximum = "not-a-number",
            scale = "1.5",
            unit = "line\nbreak",
            enumValues = listOf("safe", "bad\u0000value"),
          )
        )
      )

    val definition = schema["1"]
    assertNull(definition?.code)
    assertEquals(DpDeclaredType.UNKNOWN, definition?.declaredType)
    assertNull(definition?.constraints?.minimum)
    assertNull(definition?.constraints?.maximum)
    assertNull(definition?.constraints?.scale)
    assertNull(definition?.constraints?.unit)
    assertEquals(listOf("safe"), definition?.constraints?.enumValues)
  }

  @Test
  fun `drops invalid data point ids`() {
    val schema =
      DpSchema.normalize(
        listOf(
          DpDefinitionInput("", "switch", "Boolean"),
          DpDefinitionInput("0", "switch", "Boolean"),
          DpDefinitionInput("00000000", "switch", "Boolean"),
          DpDefinitionInput("123456789", "switch", "Boolean"),
          DpDefinitionInput("one", "switch", "Boolean"),
          DpDefinitionInput(" 2 ", "switch", "Boolean"),
          DpDefinitionInput("1", "switch", "Boolean"),
        )
      )

    assertEquals(listOf("1"), schema.definitions.map(DpDefinition::id))
  }

  @Test
  fun `duplicate ids fail closed independently of order`() {
    val schema =
      DpSchema.normalize(
        listOf(
          DpDefinitionInput("1", "switch", "Boolean"),
          DpDefinitionInput("2", "temperature", "Integer"),
          DpDefinitionInput("1", "switch_led", "Boolean"),
          DpDefinitionInput("1", "switch_1", "Boolean"),
        )
      )

    assertNull(schema["1"])
    assertEquals("temperature", schema["2"]?.code)
  }

  @Test
  fun `oversized schema is rejected rather than partially selected`() {
    val schema =
      DpSchema.normalize(
        (1..(DpSchema.MAX_DEFINITION_COUNT + 1)).map { id ->
          DpDefinitionInput(id.toString(), "switch_$id", "Boolean")
        }
      )

    assertTrue(schema.isEmpty)
    assertTrue(schema.rejectedAsOversized)
  }

  @Test
  fun `oversized enum range is removed without dropping definition`() {
    val schema =
      DpSchema.normalize(
        listOf(
          DpDefinitionInput(
            id = "1",
            code = "mode",
            declaredType = "Enum",
            enumValues = (0..DpSchema.MAX_ENUM_VALUE_COUNT).map { value -> value.toString() },
          )
        )
      )

    assertEquals(DpDeclaredType.ENUM, schema["1"]?.declaredType)
    assertTrue(schema["1"]?.constraints?.enumValues.orEmpty().isEmpty())
  }

  @Test
  fun `ids enums and numeric constraints remain exact`() {
    val schema =
      DpSchema.normalize(
        listOf(
          DpDefinitionInput(
            id = "1",
            code = "mode",
            declaredType = "Enum",
            minimum = " 1",
            maximum = "2 ",
            scale = " 0 ",
            enumValues = listOf(" white ", "colour"),
          )
        )
      )

    assertNull(schema["1"]?.constraints?.minimum)
    assertNull(schema["1"]?.constraints?.maximum)
    assertNull(schema["1"]?.constraints?.scale)
    assertEquals(listOf(" white ", "colour"), schema["1"]?.constraints?.enumValues)
  }
}
