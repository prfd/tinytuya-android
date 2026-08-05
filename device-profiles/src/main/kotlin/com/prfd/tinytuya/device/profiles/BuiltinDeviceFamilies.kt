package com.prfd.tinytuya.device.profiles

import com.prfd.tinytuya.device.core.capability.CapabilitySpec
import com.prfd.tinytuya.device.core.profile.DeviceFamilyDefinition
import com.prfd.tinytuya.device.core.profile.DeviceFamilyId
import com.prfd.tinytuya.device.core.profile.DeviceFamilyRegistry
import com.prfd.tinytuya.device.core.profile.DevicePresentation
import com.prfd.tinytuya.device.core.profile.StandardDeviceLayoutIds
import com.prfd.tinytuya.device.core.schema.DpSchema

object BuiltinDeviceFamilyIds {
  val SWITCH_OR_OUTLET = DeviceFamilyId("switch_or_outlet")
  val LIGHT = DeviceFamilyId("light")
  val COVER = DeviceFamilyId("cover")
  val CLIMATE_SENSOR = DeviceFamilyId("sensor_climate")
  val CONTACT_SENSOR = DeviceFamilyId("sensor_contact")
  val MOTION_SENSOR = DeviceFamilyId("sensor_motion")
  val PRESENCE_SENSOR = DeviceFamilyId("sensor_presence")
  val WATER_LEAK_SENSOR = DeviceFamilyId("sensor_water_leak")
  val SMOKE_SENSOR = DeviceFamilyId("sensor_smoke")
  val GAS_SENSOR = DeviceFamilyId("sensor_gas")
}

/**
 * Built-in device families selected exclusively by the normalized category imported from Tuya
 * Cloud. Switches and lights have representative hardware evidence; covers and sensor families
 * currently have synthetic coverage only. Detailed public claims live in `SUPPORTED_DEVICES.md`.
 */
object BuiltinDeviceFamilies {
  val definitions: List<DeviceFamilyDefinition> =
    listOf(
      DeclarativeDeviceFamily(
        id = BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET,
        presentation =
          DevicePresentation(
            StandardDeviceLayoutIds.GENERIC_CONTROLS,
            typeLabel = "Switch or outlet",
            symbol = "⏻",
          ),
        categories = setOf("kg", "cz", "pc"),
      ),
      DeclarativeDeviceFamily(
        id = BuiltinDeviceFamilyIds.LIGHT,
        presentation =
          DevicePresentation(
            StandardDeviceLayoutIds.LIGHT,
            typeLabel = "Smart light",
            symbol = "✦",
          ),
        categories = setOf("dj", "xdd", "fwd", "dc", "dd", "gyd", "fsd", "tyndj"),
      ),
      DeclarativeDeviceFamily(
        id = BuiltinDeviceFamilyIds.COVER,
        presentation =
          DevicePresentation(
            StandardDeviceLayoutIds.COVER,
            typeLabel = "Curtain or cover",
            symbol = "↕",
          ),
        categories = setOf("cl", "clkg"),
      ),
      sensorFamily(
        id = BuiltinDeviceFamilyIds.CLIMATE_SENSOR,
        label = "Temperature and humidity sensor",
        symbol = "°",
        categories = setOf("wsdcg"),
      ),
      sensorFamily(
        id = BuiltinDeviceFamilyIds.CONTACT_SENSOR,
        label = "Contact sensor",
        symbol = "▯",
        categories = setOf("mcs"),
      ),
      sensorFamily(
        id = BuiltinDeviceFamilyIds.MOTION_SENSOR,
        label = "Motion sensor",
        symbol = "⌁",
        categories = setOf("pir"),
      ),
      sensorFamily(
        id = BuiltinDeviceFamilyIds.PRESENCE_SENSOR,
        label = "Presence sensor",
        symbol = "◎",
        categories = setOf("hps"),
      ),
      sensorFamily(
        id = BuiltinDeviceFamilyIds.WATER_LEAK_SENSOR,
        label = "Water leak sensor",
        symbol = "≈",
        categories = setOf("sj"),
      ),
      sensorFamily(
        id = BuiltinDeviceFamilyIds.SMOKE_SENSOR,
        label = "Smoke alarm",
        symbol = "≋",
        categories = setOf("ywbj"),
      ),
      sensorFamily(
        id = BuiltinDeviceFamilyIds.GAS_SENSOR,
        label = "Gas alarm",
        symbol = "◇",
        categories = setOf("rqbj"),
      ),
    )

  val registry = DeviceFamilyRegistry(definitions)
}

private class DeclarativeDeviceFamily(
  override val id: DeviceFamilyId,
  override val presentation: DevicePresentation,
  override val categories: Set<String>,
) : DeviceFamilyDefinition {
  override fun capabilitySpecs(schema: DpSchema): List<CapabilitySpec> =
    BuiltinCapabilitySpecs.forFamily(id, schema)
}

private fun sensorFamily(
  id: DeviceFamilyId,
  label: String,
  symbol: String,
  categories: Set<String>,
): DeviceFamilyDefinition =
  DeclarativeDeviceFamily(
    id = id,
    presentation =
      DevicePresentation(
        StandardDeviceLayoutIds.SENSOR_SUMMARY,
        typeLabel = label,
        symbol = symbol,
      ),
    categories = categories,
  )
