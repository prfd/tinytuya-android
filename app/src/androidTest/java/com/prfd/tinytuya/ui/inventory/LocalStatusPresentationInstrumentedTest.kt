package com.prfd.tinytuya.ui.inventory

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStatusPresentationInstrumentedTest {
    @Test
    fun mainCardKeepsOnlyUsefulHighlightsAndFormatsLightValues() {
        val device = sampleDevice(
            mappingJson = """
                {
                  "4":{"code":"child_lock","type":"Boolean"},
                  "9":{"code":"countdown_1","type":"Integer"},
                  "19":{"code":"cur_power","type":"Integer","values":{"unit":"W","scale":1}},
                  "21":{"code":"work_mode","type":"Enum","values":{"range":["white","colour"]}},
                  "22":{"code":"bright_value","type":"Integer","values":{"min":10,"max":1000,"scale":0}},
                  "23":{"code":"temp_value","type":"Integer","values":{"min":0,"max":1000,"scale":0}}
                }
            """.trimIndent(),
        )
        val highlights = presentLocalDataPoints(
            device = device,
            dataPoints = listOf(
                LocalDataPoint("4", LocalDataPointKind.BOOLEAN, "true"),
                LocalDataPoint("9", LocalDataPointKind.INTEGER, "0"),
                LocalDataPoint("19", LocalDataPointKind.INTEGER, "123"),
                LocalDataPoint("21", LocalDataPointKind.STRING, "white"),
                LocalDataPoint("22", LocalDataPointKind.INTEGER, "730"),
                LocalDataPoint("23", LocalDataPointKind.INTEGER, "420"),
            ),
        )

        assertEquals(
            listOf("Power draw", "Brightness", "Color temperature", "Mode"),
            highlights.map { it.label },
        )
        assertEquals(listOf("12.3 W", "73%", "42%", "White"), highlights.map { it.value })
        assertFalse(highlights.any { it.id == "4" || it.id == "9" })
    }

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
        val summary = presentLocalDataPoints(device, dataPoints)

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
        assertTrue(summary.isEmpty())
        assertFalse(inspection.toString().contains(privateText))
        assertFalse(inspection.toString().contains("private-json-token"))
        assertFalse(summary.toString().contains(privateText))
        assertFalse(summary.toString().contains("private-json-token"))
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
