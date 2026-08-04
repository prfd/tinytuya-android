package com.prfd.tinytuya.device.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceObservationTest {
    @Test
    fun `normalizes bounded primitives in stable DPS order`() {
        val observation = DeviceObservation.normalize(
            listOf(
                ObservedDataPointInput("20", ObservedDataPointKind.DECIMAL, "2.5"),
                ObservedDataPointInput("2", ObservedDataPointKind.BOOLEAN, "false"),
                ObservedDataPointInput("1", ObservedDataPointKind.NULL, ""),
            ),
            isFresh = true,
        )

        assertTrue(observation.isFresh)
        assertEquals(listOf("1", "2", "20"), observation.dataPoints.map { it.id })
    }

    @Test
    fun `invalid values and duplicate ids fail closed`() {
        val observation = DeviceObservation.normalize(
            listOf(
                ObservedDataPointInput("1", ObservedDataPointKind.BOOLEAN, "True"),
                ObservedDataPointInput("2", ObservedDataPointKind.INTEGER, "1e3"),
                ObservedDataPointInput("3", ObservedDataPointKind.STRING, "line\nbreak"),
                ObservedDataPointInput("4", ObservedDataPointKind.STRING, "first"),
                ObservedDataPointInput("4", ObservedDataPointKind.STRING, "second"),
            ),
            isFresh = true,
        )

        assertTrue(observation.dataPoints.isEmpty())
        assertNull(observation["4"])
    }
}
