package com.prfd.tinytuya.data.lan

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.device.core.schema.DpDeclaredType
import com.prfd.tinytuya.device.core.schema.DpSchema
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TuyaDpSchemaAdapterInstrumentedTest {
  @Test
  fun parsesObjectAndStringEncodedValues() {
    val schema =
      parseTuyaDpSchema(
        """
        {
          "1": {
            "code": "switch_led",
            "type": "Boolean"
          },
          "2": {
            "code": "work_mode",
            "type": "Enum",
            "values": "{\"range\":[\"white\",\"colour\"]}"
          },
          "3": {
            "code": "bright_value_v2",
            "type": "Integer",
            "values": {
              "min": 10,
              "max": 1000,
              "step": 1,
              "scale": 0,
              "unit": "%"
            }
          }
        }
        """
          .trimIndent()
      )

    assertEquals(DpDeclaredType.BOOLEAN, schema["1"]?.declaredType)
    assertEquals(listOf("white", "colour"), schema["2"]?.constraints?.enumValues)
    assertEquals(BigDecimal.TEN, schema["3"]?.constraints?.minimum)
    assertEquals(BigDecimal("1000"), schema["3"]?.constraints?.maximum)
    assertEquals(BigDecimal.ONE, schema["3"]?.constraints?.step)
    assertEquals(0, schema["3"]?.constraints?.scale)
    assertEquals("%", schema["3"]?.constraints?.unit)
    assertFalse(schema.rejectedAsOversized)
  }

  @Test
  fun malformedOrWrongShapeJsonProducesNoDefinitions() {
    assertTrue(parseTuyaDpSchema("not-json").isEmpty)
    assertTrue(parseTuyaDpSchema("[]").isEmpty)
    assertTrue(parseTuyaDpSchema("{\"1\":true}").isEmpty)
  }

  @Test
  fun unsafeMetadataIsRemovedBeforeAuthorizationCanUseIt() {
    val schema =
      parseTuyaDpSchema(
        """
        {
          "1": {
            "code": "../switch",
            "type": {"nested": "Boolean"},
            "values": {
              "min": [],
              "range": ["safe", {"nested": true}]
            }
          }
        }
        """
          .trimIndent()
      )

    assertNull(schema["1"]?.code)
    assertEquals(DpDeclaredType.UNKNOWN, schema["1"]?.declaredType)
    assertNull(schema["1"]?.constraints?.minimum)
    assertEquals(listOf("safe"), schema["1"]?.constraints?.enumValues)
  }

  @Test
  fun oversizedDefinitionSetIsRejectedAsAWhole() {
    val mapping = buildString {
      append('{')
      for (id in 1..(DpSchema.MAX_DEFINITION_COUNT + 1)) {
        if (id > 1) append(',')
        append('"').append(id).append("\":{\"code\":\"switch\",\"type\":\"Boolean\"}")
      }
      append('}')
    }

    val schema = parseTuyaDpSchema(mapping)

    assertTrue(schema.isEmpty)
    assertTrue(schema.rejectedAsOversized)
  }
}
