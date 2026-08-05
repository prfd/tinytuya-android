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
}

/**
 * Built-in device families selected exclusively by the normalized category imported from Tuya
 * Cloud. Switches and lights have representative hardware evidence; covers currently have synthetic
 * coverage only. Detailed public claims live in `SUPPORTED_DEVICES.md`.
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
