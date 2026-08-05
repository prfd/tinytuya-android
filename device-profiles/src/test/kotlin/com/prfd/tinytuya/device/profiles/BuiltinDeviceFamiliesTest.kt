package com.prfd.tinytuya.device.profiles

import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.RangeCapabilitySpec
import com.prfd.tinytuya.device.core.capability.ToggleCapabilitySpec
import com.prfd.tinytuya.device.core.profile.DeviceFamilyDefinition
import com.prfd.tinytuya.device.core.profile.StandardDeviceLayoutIds
import com.prfd.tinytuya.device.core.schema.DpDefinitionInput
import com.prfd.tinytuya.device.core.schema.DpSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinDeviceFamiliesTest {
  @Test
  fun `category matrix selects the expected family`() {
    mapOf(
        "kg" to BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET,
        "cz" to BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET,
        "pc" to BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET,
        "dj" to BuiltinDeviceFamilyIds.LIGHT,
        "xdd" to BuiltinDeviceFamilyIds.LIGHT,
        "fwd" to BuiltinDeviceFamilyIds.LIGHT,
        "dc" to BuiltinDeviceFamilyIds.LIGHT,
        "dd" to BuiltinDeviceFamilyIds.LIGHT,
        "gyd" to BuiltinDeviceFamilyIds.LIGHT,
        "fsd" to BuiltinDeviceFamilyIds.LIGHT,
        "tyndj" to BuiltinDeviceFamilyIds.LIGHT,
        "cl" to BuiltinDeviceFamilyIds.COVER,
        "clkg" to BuiltinDeviceFamilyIds.COVER,
        "wsdcg" to BuiltinDeviceFamilyIds.CLIMATE_SENSOR,
        "mcs" to BuiltinDeviceFamilyIds.CONTACT_SENSOR,
        "pir" to BuiltinDeviceFamilyIds.MOTION_SENSOR,
        "hps" to BuiltinDeviceFamilyIds.PRESENCE_SENSOR,
        "sj" to BuiltinDeviceFamilyIds.WATER_LEAK_SENSOR,
        "ywbj" to BuiltinDeviceFamilyIds.SMOKE_SENSOR,
        "rqbj" to BuiltinDeviceFamilyIds.GAS_SENSOR,
      )
      .forEach { (category, expectedId) -> assertEquals(expectedId, resolve(category)?.id) }
  }

  @Test
  fun `unknown and blank categories are unsupported`() {
    assertNull(resolve("custom"))
    assertNull(resolve(""))
  }

  @Test
  fun `families select reusable layout contracts instead of product-specific UI`() {
    fun layoutFor(category: String) = requireNotNull(resolve(category)).presentation.layoutId

    assertEquals(StandardDeviceLayoutIds.GENERIC_CONTROLS, layoutFor("kg"))
    assertEquals(StandardDeviceLayoutIds.LIGHT, layoutFor("dj"))
    assertEquals(StandardDeviceLayoutIds.COVER, layoutFor("cl"))
    listOf("wsdcg", "mcs", "pir", "hps", "sj", "ywbj", "rqbj").forEach { category ->
      assertEquals(StandardDeviceLayoutIds.SENSOR_SUMMARY, layoutFor(category))
    }
  }

  @Test
  fun `profile capabilities use semantic ids independent of DPS numbers`() {
    fun idsFor(powerDp: String, brightnessDp: String): List<CapabilityId> {
      val schema =
        DpSchema.normalize(
          listOf(
            definition(powerDp, "switch_led", "Boolean"),
            DpDefinitionInput(
              id = brightnessDp,
              code = "bright_value_v2",
              declaredType = "Integer",
              minimum = "10",
              maximum = "1000",
              step = "1",
              scale = "0",
            ),
          )
        )
      return requireNotNull(resolve("dj")).capabilitySpecs(schema).map { it.id }
    }

    assertEquals(idsFor("1", "2"), idsFor("20", "101"))
    assertTrue(CapabilityId("power") in idsFor("1", "2"))
    assertTrue(CapabilityId("light.brightness") in idsFor("1", "2"))
  }

  @Test
  fun `switch and light profiles declare writable intent without observations`() {
    val switchSchema = DpSchema.normalize(listOf(definition("8", "switch_2", "Boolean")))
    val switchSpecs = requireNotNull(resolve("kg")).capabilitySpecs(switchSchema)
    val lightSpecs = requireNotNull(resolve("dj")).capabilitySpecs(DpSchema.empty())

    assertEquals("switch.2", switchSpecs.filterIsInstance<ToggleCapabilitySpec>().single().id.value)
    assertTrue(lightSpecs.filterIsInstance<RangeCapabilitySpec>().all { it.writable })
  }

  private fun resolve(category: String): DeviceFamilyDefinition? =
    BuiltinDeviceFamilies.registry.familyFor(category)

  private fun definition(
    id: String,
    code: String,
    type: String,
  ) = DpDefinitionInput(id = id, code = code, declaredType = type)
}
