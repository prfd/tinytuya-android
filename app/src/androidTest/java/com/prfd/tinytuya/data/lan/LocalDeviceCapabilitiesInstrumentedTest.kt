package com.prfd.tinytuya.data.lan

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.local.LocalStatusRecord
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDeviceCapabilitiesInstrumentedTest {
    @Test
    fun multiGangControlsAreSortedAndBoundToVerifiedBooleanMappings() {
        val device = sampleDevice(
            category = "kg",
            mappingJson = """
                {
                  "3":{"code":"switch_3","type":"Boolean"},
                  "1":{"code":"switch_1","type":"Boolean"},
                  "100":{"code":"switch_100","type":"Boolean"},
                  "2":{"code":"switch_2","type":"Boolean"}
                }
            """.trimIndent(),
        )
        val profile = LocalDeviceCapabilityRegistry.profile(
            device = device,
            status = respondedStatus(
                LocalDataPoint("3", LocalDataPointKind.BOOLEAN, "true"),
                LocalDataPoint("100", LocalDataPointKind.BOOLEAN, "true"),
                LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "false"),
                LocalDataPoint("2", LocalDataPointKind.BOOLEAN, "true"),
            ),
            lastDiscoveryAtEpochMillis = DISCOVERED_AT,
        )

        assertEquals(LocalDeviceProfileKind.SWITCH_OR_OUTLET, profile.kind)
        assertEquals(3, profile.mappedSwitchCount)
        assertEquals(listOf("1", "2", "3"), profile.booleanControls.map { it.dataPointId })
        assertEquals(listOf("Switch 1", "Switch 2", "Switch 3"), profile.booleanControls.map { it.label })
        assertEquals(listOf(false, true, true), profile.booleanControls.map { it.currentValue })
    }

    @Test
    fun lightProfileExposesControlsFromTheVerifiedBulbMappingAndFreshStatus() {
        val profile = LocalDeviceCapabilityRegistry.profile(
            device = sampleDevice(
                category = "dj",
                mappingJson = LIGHT_MAPPING,
            ),
            status = respondedStatus(
                LocalDataPoint("20", LocalDataPointKind.BOOLEAN, "true"),
                LocalDataPoint("21", LocalDataPointKind.STRING, "white"),
                LocalDataPoint("22", LocalDataPointKind.INTEGER, "730"),
                LocalDataPoint("23", LocalDataPointKind.INTEGER, "420"),
                LocalDataPoint("24", LocalDataPointKind.STRING, "012c02bc01f4"),
            ),
            lastDiscoveryAtEpochMillis = DISCOVERED_AT,
        )

        assertEquals(LocalDeviceProfileKind.LIGHT, profile.kind)
        assertEquals(1, profile.mappedSwitchCount)
        assertEquals(listOf("20"), profile.booleanControls.map { it.dataPointId })
        assertEquals("Power", profile.booleanControls.single().label)
        val controls = requireNotNull(profile.lightControls)
        assertEquals("21", controls.mode?.dataPointId)
        assertEquals(LocalLightMode.WHITE, controls.mode?.currentMode)
        assertEquals(
            LocalLightIntegerControl("22", "bright_value_v2", 10, 1_000, 1, 730),
            controls.whiteBrightness,
        )
        assertEquals(
            LocalLightIntegerControl("23", "temp_value_v2", 0, 1_000, 1, 420),
            controls.colorTemperature,
        )
        assertEquals(
            LocalLightHsv(hue = 300, saturation = 700, brightness = 500),
            controls.color?.currentColor,
        )
    }

    @Test
    fun malformedOrStaleLightDataCannotBecomeWritable() {
        val device = sampleDevice(category = "dj", mappingJson = LIGHT_MAPPING)
        val stale = LocalDeviceCapabilityRegistry.profile(
            device = device,
            status = respondedStatus(
                LocalDataPoint("21", LocalDataPointKind.STRING, "white"),
                LocalDataPoint("22", LocalDataPointKind.INTEGER, "500"),
                LocalDataPoint("23", LocalDataPointKind.INTEGER, "500"),
                LocalDataPoint("24", LocalDataPointKind.STRING, "000003e803e8"),
                polledAtEpochMillis = DISCOVERED_AT - 1,
            ),
            lastDiscoveryAtEpochMillis = DISCOVERED_AT,
        )
        val malformed = LocalDeviceCapabilityRegistry.profile(
            device = device,
            status = respondedStatus(
                LocalDataPoint("21", LocalDataPointKind.STRING, "unsupported"),
                LocalDataPoint("22", LocalDataPointKind.INTEGER, "1001"),
                LocalDataPoint("23", LocalDataPointKind.STRING, "500"),
                LocalDataPoint("24", LocalDataPointKind.STRING, "016903e903e8"),
            ),
            lastDiscoveryAtEpochMillis = DISCOVERED_AT,
        )

        assertEquals(null, stale.lightControls)
        assertEquals(null, malformed.lightControls)
    }

    @Test
    fun staleStatusAndSubdevicesNeverExposeLocalControls() {
        val device = sampleDevice(
            category = "kg",
            mappingJson = SWITCH_MAPPING,
        )
        val staleStatus = respondedStatus(
            LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
            polledAtEpochMillis = DISCOVERED_AT - 1,
        )

        assertTrue(
            LocalDeviceCapabilityRegistry.profile(
                device = device,
                status = staleStatus,
                lastDiscoveryAtEpochMillis = DISCOVERED_AT,
            ).booleanControls.isEmpty()
        )
        assertTrue(
            LocalDeviceCapabilityRegistry.profile(
                device = device.copy(isSubDevice = true),
                status = respondedStatus(
                    LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")
                ),
                lastDiscoveryAtEpochMillis = DISCOVERED_AT,
            ).booleanControls.isEmpty()
        )
    }

    @Test
    fun mismatchedAndCustomBooleanMappingsRemainReadOnly() {
        val profile = LocalDeviceCapabilityRegistry.profile(
            device = sampleDevice(
                category = "kg",
                mappingJson = """
                    {
                      "1":{"code":"switch_1","type":"Integer"},
                      "2":{"code":"custom_toggle","type":"Boolean"}
                    }
                """.trimIndent(),
            ),
            status = respondedStatus(
                LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
                LocalDataPoint("2", LocalDataPointKind.BOOLEAN, "true"),
            ),
            lastDiscoveryAtEpochMillis = DISCOVERED_AT,
        )

        assertTrue(profile.booleanControls.isEmpty())
    }

    @Test
    fun protectedDeviceFamiliesNeverPollOrExposeSwitchControls() {
        val protectedCategories = listOf(
            "wg2" to LocalDeviceAccessKind.GATEWAY,
            "sp" to LocalDeviceAccessKind.CAMERA,
            "ms" to LocalDeviceAccessKind.LOCK,
            "videolock" to LocalDeviceAccessKind.LOCK,
        )

        protectedCategories.forEach { (category, expectedAccess) ->
            val device = sampleDevice(category = category, mappingJson = SWITCH_MAPPING)
            val profile = LocalDeviceCapabilityRegistry.profile(
                device = device,
                status = respondedStatus(
                    LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")
                ),
                lastDiscoveryAtEpochMillis = DISCOVERED_AT,
            )

            assertEquals(expectedAccess, profile.access)
            assertTrue(profile.booleanControls.isEmpty())
            assertFalse(LocalDeviceCapabilityRegistry.canPollStatus(device))
            assertTrue(
                LocalDeviceCapabilityRegistry.booleanControls(
                    device = device,
                    status = respondedStatus(
                        LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")
                    ),
                    lastDiscoveryAtEpochMillis = DISCOVERED_AT,
                ).isEmpty()
            )
        }
    }

    @Test
    fun gatewayChildTakesPriorityOverItsUnderlyingProfile() {
        val device = sampleDevice(category = "dj", mappingJson = SWITCH_MAPPING)
            .copy(isSubDevice = true, gatewayId = "gateway-id")
        val profile = LocalDeviceCapabilityRegistry.profile(
            device = device,
            status = respondedStatus(
                LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")
            ),
            lastDiscoveryAtEpochMillis = DISCOVERED_AT,
        )

        assertEquals(LocalDeviceProfileKind.LIGHT, profile.kind)
        assertEquals(LocalDeviceAccessKind.GATEWAY_CHILD, profile.access)
        assertTrue(profile.booleanControls.isEmpty())
        assertFalse(LocalDeviceCapabilityRegistry.canPollStatus(device))
    }

    @Test
    fun standardSensorCategoriesArePollableReadOnlyProfiles() {
        val categories = listOf(
            "wsdcg" to LocalSensorKind.CLIMATE,
            "mcs" to LocalSensorKind.CONTACT,
            "pir" to LocalSensorKind.MOTION,
            "hps" to LocalSensorKind.PRESENCE,
            "sj" to LocalSensorKind.WATER_LEAK,
            "ywbj" to LocalSensorKind.SMOKE,
            "rqbj" to LocalSensorKind.GAS,
        )

        categories.forEach { (category, expectedSensorKind) ->
            val device = sampleDevice(
                category = category,
                mappingJson = "{\"4\":{\"code\":\"battery_percentage\",\"type\":\"Integer\"}}",
            )
            val profile = LocalDeviceCapabilityRegistry.profile(
                device = device,
                status = respondedStatus(
                    LocalDataPoint("4", LocalDataPointKind.INTEGER, "82")
                ),
                lastDiscoveryAtEpochMillis = DISCOVERED_AT,
            )

            assertEquals(LocalDeviceProfileKind.SENSOR, profile.kind)
            assertEquals(expectedSensorKind, profile.sensorKind)
            assertEquals(LocalDeviceAccessKind.STATUS_ONLY, profile.access)
            assertTrue(profile.booleanControls.isEmpty())
            assertTrue(LocalDeviceCapabilityRegistry.canPollStatus(device))
        }
    }

    @Test
    fun climateCategoryCannotGainControlsFromAmbiguousSwitchAndBrightnessCodes() {
        val device = sampleDevice(
            category = "wsdcg",
            mappingJson = """
                {
                  "1":{"code":"switch","type":"Boolean"},
                  "2":{"code":"bright_value","type":"Integer"},
                  "3":{"code":"temp_current","type":"Integer"}
                }
            """.trimIndent(),
        )
        val status = respondedStatus(
            LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
            LocalDataPoint("2", LocalDataPointKind.INTEGER, "500"),
            LocalDataPoint("3", LocalDataPointKind.INTEGER, "215"),
        )

        val profile = LocalDeviceCapabilityRegistry.profile(
            device = device,
            status = status,
            lastDiscoveryAtEpochMillis = DISCOVERED_AT,
        )

        assertEquals(LocalDeviceProfileKind.SENSOR, profile.kind)
        assertEquals(LocalSensorKind.CLIMATE, profile.sensorKind)
        assertEquals(LocalDeviceAccessKind.STATUS_ONLY, profile.access)
        assertTrue(profile.booleanControls.isEmpty())
        assertTrue(
            LocalDeviceCapabilityRegistry.booleanControls(
                device = device,
                status = status,
                lastDiscoveryAtEpochMillis = DISCOVERED_AT,
            ).isEmpty()
        )
    }

    @Test
    fun strongSensorMappingIdentifiesAnUnknownProductWithoutOverridingASwitchCategory() {
        val mapping = """
            {
              "1":{"code":"switch_1","type":"Boolean"},
              "7":{"code":"watersensor_state","type":"Enum","values":{"range":["alarm","normal"]}}
            }
        """.trimIndent()
        val unknownProfile = LocalDeviceCapabilityRegistry.profile(
            device = sampleDevice(category = "custom", mappingJson = mapping),
            status = null,
            lastDiscoveryAtEpochMillis = null,
        )
        val switchProfile = LocalDeviceCapabilityRegistry.profile(
            device = sampleDevice(category = "cz", mappingJson = mapping),
            status = respondedStatus(
                LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")
            ),
            lastDiscoveryAtEpochMillis = DISCOVERED_AT,
        )

        assertEquals(LocalDeviceProfileKind.SENSOR, unknownProfile.kind)
        assertEquals(LocalSensorKind.WATER_LEAK, unknownProfile.sensorKind)
        assertEquals(LocalDeviceAccessKind.STATUS_ONLY, unknownProfile.access)
        assertEquals(LocalDeviceProfileKind.SWITCH_OR_OUTLET, switchProfile.kind)
        assertEquals(null, switchProfile.sensorKind)
        assertEquals(listOf("1"), switchProfile.booleanControls.map { it.dataPointId })
    }

    @Test
    fun genericDirectDeviceIsPollableButStatusOnly() {
        val device = sampleDevice(
            category = "custom_sensor",
            mappingJson = "{\"1\":{\"code\":\"presence\",\"type\":\"Boolean\"}}",
        )
        val profile = LocalDeviceCapabilityRegistry.profile(
            device = device,
            status = respondedStatus(
                LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")
            ),
            lastDiscoveryAtEpochMillis = DISCOVERED_AT,
        )

        assertEquals(LocalDeviceProfileKind.GENERIC, profile.kind)
        assertEquals(LocalDeviceAccessKind.STATUS_ONLY, profile.access)
        assertTrue(profile.booleanControls.isEmpty())
        assertTrue(LocalDeviceCapabilityRegistry.canPollStatus(device))
    }

    private fun sampleDevice(
        category: String,
        mappingJson: String,
    ) = CloudImportedDevice(
        id = "profile-fixture",
        name = "Profile fixture",
        localKey = SensitiveString.of("fixture-key"),
        category = category,
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

    private fun respondedStatus(
        vararg dataPoints: LocalDataPoint,
        polledAtEpochMillis: Long = POLLED_AT,
    ) = LocalStatusRecord(
        id = "profile-fixture",
        state = LocalPollDeviceState.RESPONDED,
        errorCode = "",
        durationMillis = 12L,
        dataPoints = dataPoints.toList(),
        polledAtEpochMillis = polledAtEpochMillis,
    )

    private companion object {
        const val DISCOVERED_AT = 9L
        const val POLLED_AT = 10L
        const val SWITCH_MAPPING = "{\"1\":{\"code\":\"switch_1\",\"type\":\"Boolean\"}}"
        val LIGHT_MAPPING = """
            {
              "20":{"code":"switch_led","type":"Boolean"},
              "21":{"code":"work_mode","type":"Enum","values":{"range":["white","colour","scene","music"]}},
              "22":{"code":"bright_value_v2","type":"Integer","values":{"min":10,"max":1000,"step":1,"scale":0}},
              "23":{"code":"temp_value_v2","type":"Integer","values":"{\"min\":0,\"max\":1000,\"step\":1,\"scale\":0}"},
              "24":{"code":"colour_data_v2","type":"Json"}
            }
        """.trimIndent()
    }
}
