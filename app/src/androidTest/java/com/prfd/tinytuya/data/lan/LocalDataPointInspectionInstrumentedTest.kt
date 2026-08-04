package com.prfd.tinytuya.data.lan

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDataPointInspectionInstrumentedTest {
    @Test
    fun inspectorUsesNormalizedMappingAndHidesArbitraryPayloads() {
        val privateText = "private-device-token"
        val privateJson = "{\"credential\":\"private-json-token\"}"
        val device = sampleDevice(
            mappingJson = """
                {
                  "1":{"code":"enabled","type":"Boolean"},
                  "2":{"code":"sample_count","type":"Integer"},
                  "3":{"code":"mode","type":"Enum","values":"{\"range\":[\"auto\",\"manual\"]}"},
                  "4":{"code":"api_token","type":"String"},
                  "5":{"code":"raw_blob","type":"Raw"},
                  "6":{"code":"display_message","type":"String"}
                }
            """.trimIndent(),
        )
        val dataPoints = listOf(
            LocalDataPoint("5", LocalDataPointKind.JSON, privateJson),
            LocalDataPoint("3", LocalDataPointKind.STRING, "auto"),
            LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
            LocalDataPoint("4", LocalDataPointKind.STRING, privateText),
            LocalDataPoint("2", LocalDataPointKind.INTEGER, "42"),
            LocalDataPoint("6", LocalDataPointKind.STRING, "Ready for use"),
        )

        val inspection = inspectLocalDataPoints(device, dataPoints)

        assertEquals(6, inspection.totalCount)
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), inspection.dataPoints.map { it.id })
        assertEquals(
            listOf(
                "true",
                "42",
                "auto",
                "Sensitive or invalid text hidden",
                "Structured value hidden",
                "Ready for use",
            ),
            inspection.dataPoints.map { it.safeValue },
        )
        assertEquals("Enum", inspection.dataPoints.single { it.id == "3" }.kindLabel)
        assertEquals("mode", inspection.dataPoints.single { it.id == "3" }.code)
        assertFalse(inspection.toString().contains(privateText))
        assertFalse(inspection.toString().contains("private-json-token"))
    }

    private fun sampleDevice(mappingJson: String) = CloudImportedDevice(
        id = "inspection-fixture",
        name = "Inspection fixture",
        localKey = SensitiveString.of("fixture-key"),
        category = "custom_sensor",
        productId = "product-id",
        productName = "Fixture",
        model = "Model",
        mac = "",
        uuid = "",
        isSubDevice = false,
        gatewayId = "",
        nodeId = "",
        protocolVersion = "3.5",
        lastIp = "",
        mappingJson = mappingJson,
    )
}
