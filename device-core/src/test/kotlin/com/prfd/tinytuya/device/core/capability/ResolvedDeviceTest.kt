package com.prfd.tinytuya.device.core.capability

import com.prfd.tinytuya.device.core.profile.DeviceLayoutId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedDeviceTest {
    @Test
    fun `resolved device keeps its route out of string output`() {
        val device = ResolvedDevice.create(
            deviceId = "private-device-route",
            layoutId = DeviceLayoutId("generic.controls"),
            capabilities = ResolvedDeviceCapabilities.EMPTY,
        )

        assertFalse(device.toString().contains("private-device-route"))
        assertTrue(device.toString().contains("generic.controls"))
    }

    @Test
    fun `resolved device rejects unbounded or multiline routes`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResolvedDevice.create("", null, ResolvedDeviceCapabilities.EMPTY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResolvedDevice.create("device\nroute", null, ResolvedDeviceCapabilities.EMPTY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResolvedDevice.create("d".repeat(129), null, ResolvedDeviceCapabilities.EMPTY)
        }
    }
}
