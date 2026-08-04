package com.prfd.tinytuya.device.core.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectedDevicePolicyTest {
    @Test
    fun `normalizes bounded non-secret identity metadata`() {
        val identity = DeviceIdentity.normalize(
            category = " DJ ",
            productId = " product-id ",
            productName = " Bulb ",
            model = " Model ",
            isSubDevice = false,
        )

        assertEquals("dj", identity.category)
        assertEquals("product-id", identity.productId)
        assertEquals("Bulb", identity.productName)
        assertEquals("Model", identity.model)
    }

    @Test
    fun `subdevice restriction takes priority over protected category`() {
        val identity = identity(category = "sp", isSubDevice = true)

        assertEquals(
            DeviceAccessRestriction.GATEWAY_CHILD,
            ProtectedDevicePolicy.restrictionFor(identity),
        )
    }

    @Test
    fun `protected categories are centrally classified`() {
        mapOf(
            "wg2" to DeviceAccessRestriction.GATEWAY,
            "wfcon" to DeviceAccessRestriction.GATEWAY,
            "sp" to DeviceAccessRestriction.CAMERA,
            "ms" to DeviceAccessRestriction.LOCK,
            "videolock" to DeviceAccessRestriction.LOCK,
            "dj" to DeviceAccessRestriction.NONE,
        ).forEach { (category, restriction) ->
            assertEquals(restriction, ProtectedDevicePolicy.restrictionFor(identity(category)))
        }
    }

    @Test
    fun `invalid or oversized categories cannot impersonate protected categories`() {
        assertEquals(DeviceAccessRestriction.NONE, ProtectedDevicePolicy.restrictionFor(identity(" sp! ")))
        assertEquals(
            DeviceAccessRestriction.NONE,
            ProtectedDevicePolicy.restrictionFor(identity("s".repeat(65))),
        )
    }

    private fun identity(
        category: String,
        isSubDevice: Boolean = false,
    ): DeviceIdentity = DeviceIdentity.normalize(
        category = category,
        productId = "",
        productName = "",
        model = "",
        isSubDevice = isSubDevice,
    )
}
