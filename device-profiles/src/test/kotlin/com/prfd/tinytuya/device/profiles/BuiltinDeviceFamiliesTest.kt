package com.prfd.tinytuya.device.profiles

import com.prfd.tinytuya.device.core.profile.DeviceFamilyId
import com.prfd.tinytuya.device.core.profile.DeviceFamilyResolution
import com.prfd.tinytuya.device.core.profile.DeviceIdentity
import com.prfd.tinytuya.device.core.profile.DeviceMatchStrength
import com.prfd.tinytuya.device.core.profile.DeviceSupportLevel
import com.prfd.tinytuya.device.core.schema.DpDefinitionInput
import com.prfd.tinytuya.device.core.schema.DpSchema
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.RangeCapabilitySpec
import com.prfd.tinytuya.device.core.capability.ToggleCapabilitySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinDeviceFamiliesTest {
    @Test
    fun `category matrix selects the expected family`() {
        mapOf(
            "kg" to BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET,
            "dj" to BuiltinDeviceFamilyIds.LIGHT,
            "cl" to BuiltinDeviceFamilyIds.COVER,
            "wsdcg" to BuiltinDeviceFamilyIds.CLIMATE_SENSOR,
            "mcs" to BuiltinDeviceFamilyIds.CONTACT_SENSOR,
            "pir" to BuiltinDeviceFamilyIds.MOTION_SENSOR,
            "hps" to BuiltinDeviceFamilyIds.PRESENCE_SENSOR,
            "sj" to BuiltinDeviceFamilyIds.WATER_LEAK_SENSOR,
            "ywbj" to BuiltinDeviceFamilyIds.SMOKE_SENSOR,
            "rqbj" to BuiltinDeviceFamilyIds.GAS_SENSOR,
        ).forEach { (category, expectedId) ->
            val match = resolve(category = category, schema = DpSchema.empty())
                as DeviceFamilyResolution.Matched

            assertEquals(expectedId, match.definition.id)
            assertEquals(DeviceMatchStrength.CATEGORY, match.match.strength)
        }
    }

    @Test
    fun `distinctive mapping codes classify unknown products`() {
        mapOf(
            definition("1", "switch_1", "Boolean") to BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET,
            definition("1", "bright_value_v2", "Integer") to BuiltinDeviceFamilyIds.LIGHT,
            definition("1", "percent_state", "Integer") to BuiltinDeviceFamilyIds.COVER,
            definition("1", "temp_current", "Integer") to BuiltinDeviceFamilyIds.CLIMATE_SENSOR,
            definition("1", "doorcontact_state", "Boolean") to BuiltinDeviceFamilyIds.CONTACT_SENSOR,
            definition("1", "watersensor_state", "Enum") to BuiltinDeviceFamilyIds.WATER_LEAK_SENSOR,
        ).forEach { (input, expectedId) ->
            val match = resolve(schema = DpSchema.normalize(listOf(input)))
                as DeviceFamilyResolution.Matched

            assertEquals(expectedId, match.definition.id)
        }
    }

    @Test
    fun `recognized category is stronger than conflicting schema heuristics`() {
        val match = resolve(
            category = "wsdcg",
            schema = DpSchema.normalize(
                listOf(
                    definition("1", "switch", "Boolean"),
                    definition("2", "bright_value_v2", "Integer"),
                )
            ),
        ) as DeviceFamilyResolution.Matched

        assertEquals(BuiltinDeviceFamilyIds.CLIMATE_SENSOR, match.definition.id)
        assertEquals(DeviceMatchStrength.CATEGORY, match.match.strength)
    }

    @Test
    fun `distinctive sensor schema beats generic switch heuristic`() {
        val match = resolve(
            schema = DpSchema.normalize(
                listOf(
                    definition("1", "switch_1", "Boolean"),
                    definition("7", "watersensor_state", "Enum"),
                )
            )
        ) as DeviceFamilyResolution.Matched

        assertEquals(BuiltinDeviceFamilyIds.WATER_LEAK_SENSOR, match.definition.id)
        assertEquals(DeviceMatchStrength.DISTINCTIVE_SCHEMA, match.match.strength)
    }

    @Test
    fun `conflicting equally distinctive schemas are ambiguous`() {
        val resolution = resolve(
            schema = DpSchema.normalize(
                listOf(
                    definition("1", "bright_value_v2", "Integer"),
                    definition("2", "percent_state", "Integer"),
                )
            )
        ) as DeviceFamilyResolution.Ambiguous

        assertEquals(
            listOf(BuiltinDeviceFamilyIds.COVER, BuiltinDeviceFamilyIds.LIGHT)
                .sortedBy(DeviceFamilyId::value),
            resolution.familyIds,
        )
    }

    @Test
    fun `unsupported mapping is unmatched and support evidence is explicit`() {
        assertTrue(
            resolve(schema = DpSchema.normalize(listOf(definition("1", "countdown_1", "Integer")))) ==
                DeviceFamilyResolution.Unmatched
        )
        assertEquals(
            DeviceSupportLevel.REAL_HARDWARE,
            BuiltinDeviceFamilies.definitions.single {
                definition -> definition.id == BuiltinDeviceFamilyIds.LIGHT
            }.support.level,
        )
        assertEquals(
            DeviceSupportLevel.SYNTHETIC_ONLY,
            BuiltinDeviceFamilies.definitions.single {
                definition -> definition.id == BuiltinDeviceFamilyIds.COVER
            }.support.level,
        )
    }

    @Test
    fun `profile capabilities use semantic ids independent of DPS numbers`() {
        fun idsFor(powerDp: String, brightnessDp: String): List<CapabilityId> {
            val schema = DpSchema.normalize(
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
            val family = resolve(category = "dj", schema = schema)
                .let { it as DeviceFamilyResolution.Matched }
                .definition
            return family.capabilitySpecs(identity("dj"), schema).map { it.id }
        }

        assertEquals(idsFor("1", "2"), idsFor("20", "101"))
        assertTrue(CapabilityId("power") in idsFor("1", "2"))
        assertTrue(CapabilityId("light.brightness") in idsFor("1", "2"))
    }

    @Test
    fun `switch and light profiles declare writable intent without observations`() {
        val switchSchema = DpSchema.normalize(listOf(definition("8", "switch_2", "Boolean")))
        val switchDefinition = (resolve(category = "kg", schema = switchSchema)
            as DeviceFamilyResolution.Matched).definition
        val switchSpecs = switchDefinition.capabilitySpecs(identity("kg"), switchSchema)
        val lightDefinition = (resolve(category = "dj", schema = DpSchema.empty())
            as DeviceFamilyResolution.Matched).definition
        val lightSpecs = lightDefinition.capabilitySpecs(identity("dj"), DpSchema.empty())

        assertEquals("switch.2", switchSpecs.filterIsInstance<ToggleCapabilitySpec>().single().id.value)
        assertTrue(lightSpecs.filterIsInstance<RangeCapabilitySpec>().all { it.writable })
    }

    private fun resolve(
        category: String = "custom",
        schema: DpSchema,
    ): DeviceFamilyResolution = BuiltinDeviceFamilies.registry.resolve(
        identity = identity(category),
        schema = schema,
    )

    private fun identity(category: String): DeviceIdentity = DeviceIdentity.normalize(
            category = category,
            productId = "fixture-product",
            productName = "Fixture",
            model = "Model",
            isSubDevice = false,
    )

    private fun definition(
        id: String,
        code: String,
        type: String,
    ) = DpDefinitionInput(id = id, code = code, declaredType = type)
}
