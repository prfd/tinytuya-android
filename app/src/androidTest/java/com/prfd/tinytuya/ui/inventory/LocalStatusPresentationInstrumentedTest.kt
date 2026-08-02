package com.prfd.tinytuya.ui.inventory

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStatusPresentationInstrumentedTest {
    @Test
    fun inspectorShowsSafePrimitivesAndHidesArbitraryPayloads() {
        val privateText = "private-device-token"
        val privateJson = "{\"credential\":\"private-json-token\"}"
        val device = sampleDevice(
            mappingJson = """
                {
                  "1":{"code":"enabled","type":"Boolean"},
                  "2":{"code":"sample_count","type":"Integer"},
                  "3":{"code":"mode","type":"Enum","values":"{\"range\":[\"auto\",\"manual\"]}"},
                  "4":{"code":"api_token","type":"String"},
                  "5":{"code":"raw_blob","type":"Raw"}
                }
            """.trimIndent(),
        )
        val dataPoints = listOf(
            LocalDataPoint("5", LocalDataPointKind.JSON, privateJson),
            LocalDataPoint("3", LocalDataPointKind.STRING, "auto"),
            LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
            LocalDataPoint("4", LocalDataPointKind.STRING, privateText),
            LocalDataPoint("2", LocalDataPointKind.INTEGER, "42"),
        )

        val inspection = inspectLocalDataPoints(device, dataPoints)
        val summary = presentLocalDataPoints(device, dataPoints)

        assertEquals(5, inspection.totalCount)
        assertEquals(listOf("1", "2", "3", "4", "5"), inspection.dataPoints.map { it.id })
        assertEquals(
            listOf(
                "true",
                "42",
                "auto",
                "Text value hidden",
                "Structured value hidden",
            ),
            inspection.dataPoints.map { it.safeValue },
        )
        assertEquals("Enum", inspection.dataPoints.single { it.id == "3" }.kindLabel)
        assertEquals("mode", inspection.dataPoints.single { it.id == "3" }.code)
        assertEquals("auto", summary.single { it.id == "3" }.value)
        assertEquals("Text value hidden", summary.single { it.id == "4" }.value)
        assertEquals("Structured value hidden", summary.single { it.id == "5" }.value)
        assertFalse(inspection.toString().contains(privateText))
        assertFalse(inspection.toString().contains("private-json-token"))
        assertFalse(summary.toString().contains(privateText))
        assertFalse(summary.toString().contains("private-json-token"))
    }

    @Test
    fun inspectorOrdersNumericIdsAndCapsTheExpandedList() {
        val dataPoints = (20 downTo 1).map { id ->
            LocalDataPoint(id.toString(), LocalDataPointKind.INTEGER, id.toString())
        }

        val inspection = inspectLocalDataPoints(sampleDevice(), dataPoints)

        assertEquals(20, inspection.totalCount)
        assertEquals(16, inspection.dataPoints.size)
        assertEquals((1..16).map(Int::toString), inspection.dataPoints.map { it.id })
    }

    private fun sampleDevice(mappingJson: String = "{}") = CloudImportedDevice(
        id = "presentation-fixture",
        name = "Presentation fixture",
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
