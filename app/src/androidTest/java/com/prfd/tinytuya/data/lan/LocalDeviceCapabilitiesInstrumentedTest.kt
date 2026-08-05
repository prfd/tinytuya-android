package com.prfd.tinytuya.data.lan

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prfd.tinytuya.data.local.LocalStatusRecord
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.device.core.capability.CapabilityAccess
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.ResolvedActionGroup
import com.prfd.tinytuya.device.core.capability.ResolvedChoice
import com.prfd.tinytuya.device.core.capability.ResolvedColor
import com.prfd.tinytuya.device.core.capability.ResolvedMeasurement
import com.prfd.tinytuya.device.core.capability.ResolvedRange
import com.prfd.tinytuya.device.core.capability.ResolvedToggle
import com.prfd.tinytuya.device.core.capability.TuyaHsvColor
import com.prfd.tinytuya.device.core.profile.DeviceAccessRestriction
import com.prfd.tinytuya.device.profiles.BuiltinDeviceFamilyIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDeviceCapabilitiesInstrumentedTest {
  @Test
  fun multiGangControlsAreSortedAndBoundToVerifiedBooleanMappings() {
    val device =
      sampleDevice(
        category = "kg",
        mappingJson =
          """
          {
            "3":{"code":"switch_3","type":"Boolean"},
            "1":{"code":"switch_1","type":"Boolean"},
            "100":{"code":"switch_100","type":"Boolean"},
            "2":{"code":"switch_2","type":"Boolean"}
          }
          """
            .trimIndent(),
      )
    val profile =
      LocalDeviceCapabilityRegistry.profile(
        device = device,
        status =
          respondedStatus(
            LocalDataPoint("3", LocalDataPointKind.BOOLEAN, "true"),
            LocalDataPoint("100", LocalDataPointKind.BOOLEAN, "true"),
            LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "false"),
            LocalDataPoint("2", LocalDataPointKind.BOOLEAN, "true"),
          ),
        lastDiscoveryAtEpochMillis = DISCOVERED_AT,
      )

    assertEquals(BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET, profile.familyId)
    assertEquals(CapabilityAccess.READ_WRITE, profile.capabilityAccess)
    assertEquals(3, profile.mappedSwitchCount)
    val toggles = profile.capabilities.ofType<ResolvedToggle>()
    assertEquals(listOf("1", "2", "3"), toggles.map { it.dataPointId })
    assertEquals(listOf("Switch 1", "Switch 2", "Switch 3"), toggles.map { it.label })
    assertEquals(listOf(false, true, true), toggles.map { it.currentValue })
  }

  @Test
  fun lightProfileExposesControlsFromTheVerifiedBulbMappingAndFreshStatus() {
    val profile =
      LocalDeviceCapabilityRegistry.profile(
        device =
          sampleDevice(
            category = "dj",
            mappingJson = LIGHT_MAPPING,
          ),
        status =
          respondedStatus(
            LocalDataPoint("20", LocalDataPointKind.BOOLEAN, "true"),
            LocalDataPoint("21", LocalDataPointKind.STRING, "white"),
            LocalDataPoint("22", LocalDataPointKind.INTEGER, "730"),
            LocalDataPoint("23", LocalDataPointKind.INTEGER, "420"),
            LocalDataPoint("24", LocalDataPointKind.STRING, "012c02bc01f4"),
          ),
        lastDiscoveryAtEpochMillis = DISCOVERED_AT,
      )

    assertEquals(BuiltinDeviceFamilyIds.LIGHT, profile.familyId)
    assertEquals(1, profile.mappedSwitchCount)
    assertEquals(listOf("20"), profile.capabilities.ofType<ResolvedToggle>().map { it.dataPointId })
    assertEquals("Power", profile.capabilities.ofType<ResolvedToggle>().single().label)
    val mode = profile.capabilities.ofType<ResolvedChoice>().single()
    assertEquals("21", mode.dataPointId)
    assertEquals("white", mode.currentWireValue)
    val ranges = profile.capabilities.ofType<ResolvedRange>().associateBy { it.id }
    assertEquals("22", ranges.getValue(CapabilityId("light.brightness")).dataPointId)
    assertEquals(730, ranges.getValue(CapabilityId("light.brightness")).currentValue)
    assertEquals("23", ranges.getValue(CapabilityId("light.temperature")).dataPointId)
    assertEquals(420, ranges.getValue(CapabilityId("light.temperature")).currentValue)
    assertEquals(
      TuyaHsvColor(hue = 300, saturation = 700, brightness = 500),
      profile.capabilities.ofType<ResolvedColor>().single().currentColor,
    )
  }

  @Test
  fun malformedOrStaleLightDataCannotBecomeWritable() {
    val device = sampleDevice(category = "dj", mappingJson = LIGHT_MAPPING)
    val stale =
      LocalDeviceCapabilityRegistry.profile(
        device = device,
        status =
          respondedStatus(
            LocalDataPoint("21", LocalDataPointKind.STRING, "white"),
            LocalDataPoint("22", LocalDataPointKind.INTEGER, "500"),
            LocalDataPoint("23", LocalDataPointKind.INTEGER, "500"),
            LocalDataPoint("24", LocalDataPointKind.STRING, "000003e803e8"),
            polledAtEpochMillis = DISCOVERED_AT - 1,
          ),
        lastDiscoveryAtEpochMillis = DISCOVERED_AT,
      )
    val malformed =
      LocalDeviceCapabilityRegistry.profile(
        device = device,
        status =
          respondedStatus(
            LocalDataPoint("21", LocalDataPointKind.STRING, "unsupported"),
            LocalDataPoint("22", LocalDataPointKind.INTEGER, "1001"),
            LocalDataPoint("23", LocalDataPointKind.STRING, "500"),
            LocalDataPoint("24", LocalDataPointKind.STRING, "016903e903e8"),
          ),
        lastDiscoveryAtEpochMillis = DISCOVERED_AT,
      )

    assertTrue(stale.capabilities.capabilities.isEmpty())
    assertTrue(malformed.capabilities.capabilities.isEmpty())
  }

  @Test
  fun decimalEncodedIntegerConstraintsRemainReadOnly() {
    val profile =
      LocalDeviceCapabilityRegistry.profile(
        device =
          sampleDevice(
            category = "dj",
            mappingJson =
              """
              {
                "22":{
                  "code":"bright_value_v2",
                  "type":"Integer",
                  "values":{"min":10.0,"max":1000,"step":1,"scale":0}
                }
              }
              """
                .trimIndent(),
          ),
        status = respondedStatus(LocalDataPoint("22", LocalDataPointKind.INTEGER, "500")),
        lastDiscoveryAtEpochMillis = DISCOVERED_AT,
      )

    assertTrue(profile.capabilities.ofType<ResolvedRange>().isEmpty())
  }

  @Test
  fun staleStatusAndSubdevicesNeverExposeLocalControls() {
    val device =
      sampleDevice(
        category = "kg",
        mappingJson = SWITCH_MAPPING,
      )
    val staleStatus =
      respondedStatus(
        LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
        polledAtEpochMillis = DISCOVERED_AT - 1,
      )

    assertTrue(
      LocalDeviceCapabilityRegistry.profile(
          device = device,
          status = staleStatus,
          lastDiscoveryAtEpochMillis = DISCOVERED_AT,
        )
        .capabilities
        .ofType<ResolvedToggle>()
        .isEmpty()
    )
    assertTrue(
      LocalDeviceCapabilityRegistry.profile(
          device = device.copy(isSubDevice = true),
          status = respondedStatus(LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")),
          lastDiscoveryAtEpochMillis = DISCOVERED_AT,
        )
        .capabilities
        .ofType<ResolvedToggle>()
        .isEmpty()
    )
  }

  @Test
  fun mismatchedAndCustomBooleanMappingsRemainReadOnly() {
    val profile =
      LocalDeviceCapabilityRegistry.profile(
        device =
          sampleDevice(
            category = "kg",
            mappingJson =
              """
              {
                "1":{"code":"switch_1","type":"Integer"},
                "2":{"code":"custom_toggle","type":"Boolean"}
              }
              """
                .trimIndent(),
          ),
        status =
          respondedStatus(
            LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
            LocalDataPoint("2", LocalDataPointKind.BOOLEAN, "true"),
          ),
        lastDiscoveryAtEpochMillis = DISCOVERED_AT,
      )

    assertTrue(profile.capabilities.ofType<ResolvedToggle>().isEmpty())
  }

  @Test
  fun protectedDeviceFamiliesNeverPollOrExposeSwitchControls() {
    val protectedCategories =
      listOf(
        "wg2" to DeviceAccessRestriction.GATEWAY,
        "sp" to DeviceAccessRestriction.CAMERA,
        "ms" to DeviceAccessRestriction.LOCK,
        "videolock" to DeviceAccessRestriction.LOCK,
      )

    protectedCategories.forEach { (category, expectedAccess) ->
      val device = sampleDevice(category = category, mappingJson = SWITCH_MAPPING)
      val profile =
        LocalDeviceCapabilityRegistry.profile(
          device = device,
          status = respondedStatus(LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")),
          lastDiscoveryAtEpochMillis = DISCOVERED_AT,
        )

      assertEquals(expectedAccess, profile.restriction)
      assertEquals(CapabilityAccess.DENIED, profile.capabilityAccess)
      assertTrue(profile.capabilities.ofType<ResolvedToggle>().isEmpty())
      assertFalse(LocalDeviceCapabilityRegistry.canPollStatus(device))
    }
  }

  @Test
  fun gatewayChildTakesPriorityOverItsUnderlyingProfile() {
    val device =
      sampleDevice(category = "dj", mappingJson = SWITCH_MAPPING)
        .copy(isSubDevice = true, gatewayId = "gateway-id")
    val profile =
      LocalDeviceCapabilityRegistry.profile(
        device = device,
        status = respondedStatus(LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")),
        lastDiscoveryAtEpochMillis = DISCOVERED_AT,
      )

    assertEquals(BuiltinDeviceFamilyIds.LIGHT, profile.familyId)
    assertEquals(DeviceAccessRestriction.GATEWAY_CHILD, profile.restriction)
    assertEquals(CapabilityAccess.DENIED, profile.capabilityAccess)
    assertTrue(profile.capabilities.capabilities.isEmpty())
    assertFalse(LocalDeviceCapabilityRegistry.canPollStatus(device))
  }

  @Test
  fun coverUsesFreshMappedActionsAndPositionWithoutTypeSpecificTransport() {
    val device =
      sampleDevice(
        category = "cl",
        mappingJson =
          """
          {
            "7":{"code":"control_2","type":"Enum","values":{"range":["up","stop","down"]}},
            "8":{"code":"percent_control_2","type":"Integer","values":{"min":0,"max":100,"step":1,"scale":0}},
            "9":{"code":"percent_state_2","type":"Integer","values":{"min":0,"max":100,"step":1,"scale":0}}
          }
          """
            .trimIndent(),
      )
    val profile =
      LocalDeviceCapabilityRegistry.profile(
        device = device,
        status =
          respondedStatus(
            LocalDataPoint("7", LocalDataPointKind.STRING, "stop"),
            LocalDataPoint("8", LocalDataPointKind.INTEGER, "45"),
            LocalDataPoint("9", LocalDataPointKind.INTEGER, "48"),
          ),
        lastDiscoveryAtEpochMillis = DISCOVERED_AT,
      )

    assertEquals(BuiltinDeviceFamilyIds.COVER, profile.familyId)
    assertEquals(CapabilityAccess.READ_WRITE, profile.capabilityAccess)
    val actions = profile.capabilities.ofType<ResolvedActionGroup>().single()
    assertEquals("7", actions.dataPointId)
    assertEquals(listOf("up", "stop", "down"), actions.actions.map { it.wireValue })
    assertTrue(actions.writable)
    assertEquals("8", profile.capabilities.ofType<ResolvedRange>().single().dataPointId)
    assertEquals("48%", profile.capabilities.ofType<ResolvedMeasurement>().single().displayValue)
    assertTrue(profile.capabilities.ofType<ResolvedToggle>().isEmpty())
  }

  @Test
  fun unknownCategoryRemainsUnsupportedDespiteKnownMappingCodes() {
    val mapping =
      """
      {
        "1":{"code":"switch_1","type":"Boolean"},
        "7":{"code":"control","type":"Enum","values":{"range":["open","stop","close"]}}
      }
      """
        .trimIndent()
    val unknownProfile =
      LocalDeviceCapabilityRegistry.profile(
        device = sampleDevice(category = "custom", mappingJson = mapping),
        status = null,
        lastDiscoveryAtEpochMillis = null,
      )
    val switchProfile =
      LocalDeviceCapabilityRegistry.profile(
        device = sampleDevice(category = "cz", mappingJson = mapping),
        status = respondedStatus(LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")),
        lastDiscoveryAtEpochMillis = DISCOVERED_AT,
      )

    assertEquals(null, unknownProfile.familyId)
    assertEquals(CapabilityAccess.DENIED, unknownProfile.capabilityAccess)
    assertTrue(unknownProfile.capabilities.capabilities.isEmpty())
    assertFalse(
      LocalDeviceCapabilityRegistry.canPollStatus(
        sampleDevice(category = "custom", mappingJson = mapping)
      )
    )
    assertEquals(BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET, switchProfile.familyId)
    assertEquals(
      listOf("1"),
      switchProfile.capabilities.ofType<ResolvedToggle>().map { it.dataPointId },
    )
  }

  @Test
  fun mixedKnownMappingsCannotClassifyAnUnknownCategory() {
    val profile =
      LocalDeviceCapabilityRegistry.profile(
        device =
          sampleDevice(
            category = "custom",
            mappingJson =
              """
              {
                "1":{"code":"switch_1","type":"Boolean"},
                "2":{"code":"bright_value_v2","type":"Integer"},
                "3":{"code":"percent_state","type":"Integer"}
              }
              """
                .trimIndent(),
          ),
        status =
          respondedStatus(
            LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
            LocalDataPoint("2", LocalDataPointKind.INTEGER, "500"),
            LocalDataPoint("3", LocalDataPointKind.INTEGER, "50"),
          ),
        lastDiscoveryAtEpochMillis = DISCOVERED_AT,
      )

    assertEquals(null, profile.familyId)
    assertEquals(CapabilityAccess.DENIED, profile.capabilityAccess)
    assertTrue(profile.capabilities.capabilities.isEmpty())
  }

  @Test
  fun retiredAndUnknownDirectDevicesAreDeniedAndNeverPollable() {
    listOf("custom", "wsdcg", "mcs", "pir", "hps", "sj", "ywbj", "rqbj").forEach { category ->
      val device =
        sampleDevice(
          category = category,
          mappingJson = SWITCH_MAPPING,
        )
      val profile =
        LocalDeviceCapabilityRegistry.profile(
          device = device,
          status = respondedStatus(LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")),
          lastDiscoveryAtEpochMillis = DISCOVERED_AT,
        )

      assertEquals(null, profile.familyId)
      assertEquals(CapabilityAccess.DENIED, profile.capabilityAccess)
      assertTrue(profile.capabilities.capabilities.isEmpty())
      assertFalse(LocalDeviceCapabilityRegistry.canPollStatus(device))
    }
  }

  private fun sampleDevice(
    category: String,
    mappingJson: String,
  ) =
    CloudImportedDevice(
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
  ) =
    LocalStatusRecord(
      id = "profile-fixture",
      state = LocalPollDeviceState.RESPONDED,
      errorCode = "",
      durationMillis = 12L,
      dataPoints = dataPoints.toList(),
      polledAtEpochMillis = polledAtEpochMillis,
    )

  private val LocalDeviceProfile.capabilities
    get() = resolvedDevice.capabilities

  private companion object {
    const val DISCOVERED_AT = 9L
    const val POLLED_AT = 10L
    const val SWITCH_MAPPING = "{\"1\":{\"code\":\"switch_1\",\"type\":\"Boolean\"}}"
    val LIGHT_MAPPING =
      """
      {
        "20":{"code":"switch_led","type":"Boolean"},
        "21":{"code":"work_mode","type":"Enum","values":{"range":["white","colour","scene","music"]}},
        "22":{"code":"bright_value_v2","type":"Integer","values":{"min":10,"max":1000,"step":1,"scale":0}},
        "23":{"code":"temp_value_v2","type":"Integer","values":"{\"min\":0,\"max\":1000,\"step\":1,\"scale\":0}"},
        "24":{"code":"colour_data_v2","type":"Json"}
      }
      """
        .trimIndent()
  }
}
