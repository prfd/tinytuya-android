package com.prfd.tinytuya.ui.inventory

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.lan.LocalSensorKind
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSensorPresentationInstrumentedTest {
    @Test
    fun climateSummaryAppliesDeclaredScaleUnitsAndReadingOrder() {
        val device = sampleDevice(
            mappingJson = """
                {
                  "1":{"code":"temp_current","type":"Integer","values":{"unit":"℃","min":-100,"max":600,"scale":1}},
                  "2":{"code":"humidity_value","type":"Integer","values":"{\"unit\":\"%\",\"min\":0,\"max\":1000,\"scale\":1}"},
                  "4":{"code":"battery_percentage","type":"Integer","values":{"unit":"%","min":0,"max":100,"scale":0}}
                }
            """.trimIndent(),
        )

        val presentation = presentLocalSensor(
            device = device,
            sensorKind = LocalSensorKind.CLIMATE,
            dataPoints = listOf(
                LocalDataPoint("4", LocalDataPointKind.INTEGER, "87"),
                LocalDataPoint("2", LocalDataPointKind.INTEGER, "482"),
                LocalDataPoint("1", LocalDataPointKind.INTEGER, "217"),
            ),
        )!!

        assertEquals("Temperature", presentation.primary.label)
        assertEquals("21.7 °C", presentation.primary.value)
        assertEquals(LocalSensorTone.NEUTRAL, presentation.tone)
        assertEquals(listOf("Humidity", "Battery"), presentation.secondary.map { it.label })
        assertEquals(listOf("48.2 %", "87 %"), presentation.secondary.map { it.value })
        assertEquals(setOf("1", "2", "4"), presentation.consumedDataPointIds)
    }

    @Test
    fun contactAndPresenceSummariesUseOnlyKnownDeclaredStates() {
        val contact = presentLocalSensor(
            device = sampleDevice(
                mappingJson = "{\"1\":{\"code\":\"doorcontact_state\",\"type\":\"Boolean\"}}",
            ),
            sensorKind = LocalSensorKind.CONTACT,
            dataPoints = listOf(LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")),
        )!!
        val presence = presentLocalSensor(
            device = sampleDevice(
                mappingJson = """
                    {"1":{"code":"presence_state","type":"Enum","values":{"range":["none","presence","small_move"]}}}
                """.trimIndent(),
            ),
            sensorKind = LocalSensorKind.PRESENCE,
            dataPoints = listOf(LocalDataPoint("1", LocalDataPointKind.STRING, "small_move")),
        )!!

        assertEquals("Open", contact.primary.value)
        assertEquals(LocalSensorTone.ACTIVE, contact.tone)
        assertEquals("Small movement", presence.primary.value)
        assertEquals(LocalSensorTone.ACTIVE, presence.tone)
    }

    @Test
    fun safetyAlarmUsesAnAlertToneWithoutClaimingUnknownEnumPayloads() {
        val privateValue = "private-device-state"
        val device = sampleDevice(
            mappingJson = """
                {"1":{"code":"watersensor_state","type":"Enum","values":{"range":["alarm","normal","$privateValue"]}}}
            """.trimIndent(),
        )
        val alarm = presentLocalSensor(
            device = device,
            sensorKind = LocalSensorKind.WATER_LEAK,
            dataPoints = listOf(LocalDataPoint("1", LocalDataPointKind.STRING, "alarm")),
        )!!
        val unknown = presentLocalSensor(
            device = device,
            sensorKind = LocalSensorKind.WATER_LEAK,
            dataPoints = listOf(LocalDataPoint("1", LocalDataPointKind.STRING, privateValue)),
        )

        assertEquals("Leak detected", alarm.primary.value)
        assertEquals(LocalSensorTone.ALERT, alarm.tone)
        assertNull(unknown)
        assertFalse(alarm.toString().contains(privateValue))
    }

    @Test
    fun outOfRangeOrMismatchedNumericValuesAreNotPromoted() {
        val device = sampleDevice(
            mappingJson = """
                {
                  "1":{"code":"temp_current","type":"Integer","values":{"unit":"℃","min":-100,"max":600,"scale":1}},
                  "2":{"code":"humidity_value","type":"Integer","values":{"unit":"%","min":0,"max":1000,"scale":1}}
                }
            """.trimIndent(),
        )
        val presentation = presentLocalSensor(
            device = device,
            sensorKind = LocalSensorKind.CLIMATE,
            dataPoints = listOf(
                LocalDataPoint("1", LocalDataPointKind.INTEGER, "9999"),
                LocalDataPoint("2", LocalDataPointKind.STRING, "450"),
            ),
        )

        assertNull(presentation)
    }

    private fun sampleDevice(mappingJson: String) = CloudImportedDevice(
        id = "sensor-presentation-fixture",
        name = "Sensor fixture",
        localKey = SensitiveString.of("fixture-key"),
        category = "wsdcg",
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
