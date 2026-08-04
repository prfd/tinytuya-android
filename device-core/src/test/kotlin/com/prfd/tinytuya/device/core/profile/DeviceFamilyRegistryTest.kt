package com.prfd.tinytuya.device.core.profile

import com.prfd.tinytuya.device.core.schema.DpSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFamilyRegistryTest {
    private val identity = DeviceIdentity.normalize(
        category = "custom",
        productId = "product",
        productName = "Fixture",
        model = "Model",
        isSubDevice = false,
    )

    @Test
    fun `strongest unique match wins independently of registry order`() {
        val weak = definition("weak", DeviceMatchStrength.HEURISTIC_SCHEMA)
        val strong = definition("strong", DeviceMatchStrength.CATEGORY)

        listOf(listOf(weak, strong), listOf(strong, weak)).forEach { definitions ->
            val resolution = DeviceFamilyRegistry(definitions).resolve(identity, DpSchema.empty())
                as DeviceFamilyResolution.Matched

            assertEquals(DeviceFamilyId("strong"), resolution.definition.id)
            assertEquals(DeviceMatchStrength.CATEGORY, resolution.match.strength)
        }
    }

    @Test
    fun `equal strongest matches are ambiguous with stable sorted ids`() {
        val registry = DeviceFamilyRegistry(
            listOf(
                definition("zeta", DeviceMatchStrength.DISTINCTIVE_SCHEMA),
                definition("alpha", DeviceMatchStrength.DISTINCTIVE_SCHEMA),
                definition("weak", DeviceMatchStrength.HEURISTIC_SCHEMA),
            )
        )

        val resolution = registry.resolve(identity, DpSchema.empty())
            as DeviceFamilyResolution.Ambiguous

        assertEquals(listOf(DeviceFamilyId("alpha"), DeviceFamilyId("zeta")), resolution.familyIds)
        assertEquals(DeviceMatchStrength.DISTINCTIVE_SCHEMA, resolution.strength)
    }

    @Test
    fun `no matches and matcher failures both fail closed`() {
        val throwing = object : DeviceFamilyDefinition by definition(
            "throwing",
            DeviceMatchStrength.NONE,
        ) {
            override fun match(identity: DeviceIdentity, schema: DpSchema): DeviceMatch =
                error("fixture failure")
        }

        assertTrue(
            DeviceFamilyRegistry(listOf(throwing)).resolve(identity, DpSchema.empty()) ==
                DeviceFamilyResolution.Unmatched
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate family ids are rejected`() {
        DeviceFamilyRegistry(
            listOf(
                definition("duplicate", DeviceMatchStrength.CATEGORY),
                definition("duplicate", DeviceMatchStrength.DISTINCTIVE_SCHEMA),
            )
        )
    }

    private fun definition(
        id: String,
        strength: DeviceMatchStrength,
    ): DeviceFamilyDefinition = object : DeviceFamilyDefinition {
        override val id = DeviceFamilyId(id)
        override val support = DeviceSupport(DeviceSupportLevel.EXPERIMENTAL, "Test fixture only.")
        override val presentation = DevicePresentation(
            layoutId = DeviceLayoutId("generic"),
            typeLabel = "Fixture",
            symbol = "•",
        )

        override fun match(identity: DeviceIdentity, schema: DpSchema) = DeviceMatch(strength)
    }
}
