package com.prfd.tinytuya.device.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceFamilyRegistryTest {
    @Test
    fun `registered category selects its family`() {
        val switch = definition("switch", setOf("kg", "cz"))
        val light = definition("light", setOf("dj"))
        val registry = DeviceFamilyRegistry(listOf(switch, light))

        assertEquals(switch, registry.familyFor("kg"))
        assertEquals(switch, registry.familyFor(" CZ "))
        assertEquals(light, registry.familyFor("dj"))
    }

    @Test
    fun `unknown blank and malformed categories are unsupported`() {
        val registry = DeviceFamilyRegistry(listOf(definition("switch", setOf("kg"))))

        assertNull(registry.familyFor("custom"))
        assertNull(registry.familyFor(""))
        assertNull(registry.familyFor("kg!"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate family ids are rejected`() {
        DeviceFamilyRegistry(
            listOf(
                definition("duplicate", setOf("kg")),
                definition("duplicate", setOf("dj")),
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate category ownership is rejected`() {
        DeviceFamilyRegistry(
            listOf(
                definition("first", setOf("kg")),
                definition("second", setOf("kg")),
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `families without categories are rejected`() {
        DeviceFamilyRegistry(listOf(definition("empty", emptySet())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `registered categories must already be normalized`() {
        DeviceFamilyRegistry(listOf(definition("invalid", setOf(" KG "))))
    }

    private fun definition(
        id: String,
        categories: Set<String>,
    ): DeviceFamilyDefinition = object : DeviceFamilyDefinition {
        override val id = DeviceFamilyId(id)
        override val categories = categories
        override val presentation = DevicePresentation(
            layoutId = DeviceLayoutId("generic"),
            typeLabel = "Fixture",
            symbol = "•",
        )
    }
}
