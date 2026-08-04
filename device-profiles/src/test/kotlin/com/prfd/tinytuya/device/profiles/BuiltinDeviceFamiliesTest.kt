package com.prfd.tinytuya.device.profiles

import com.prfd.tinytuya.device.core.profile.DeviceFamilyId
import com.prfd.tinytuya.device.core.profile.DeviceFamilyResolution
import com.prfd.tinytuya.device.core.profile.DeviceIdentity
import com.prfd.tinytuya.device.core.profile.DeviceMatchStrength
import com.prfd.tinytuya.device.core.profile.DeviceSupportLevel
import com.prfd.tinytuya.device.core.schema.DpDefinitionInput
import com.prfd.tinytuya.device.core.schema.DpSchema
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

    private fun resolve(
        category: String = "custom",
        schema: DpSchema,
    ): DeviceFamilyResolution = BuiltinDeviceFamilies.registry.resolve(
        identity = DeviceIdentity.normalize(
            category = category,
            productId = "fixture-product",
            productName = "Fixture",
            model = "Model",
            isSubDevice = false,
        ),
        schema = schema,
    )

    private fun definition(
        id: String,
        code: String,
        type: String,
    ) = DpDefinitionInput(id = id, code = code, declaredType = type)
}
