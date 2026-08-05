package com.prfd.tinytuya.device.core.profile

enum class DeviceAccessRestriction {
  NONE,
  GATEWAY_CHILD,
  GATEWAY,
  CAMERA,
  LOCK,
}

/** Final central deny policy evaluated independently of profile matching. */
object ProtectedDevicePolicy {
  fun restrictionFor(identity: DeviceIdentity): DeviceAccessRestriction =
    when {
      identity.isSubDevice -> DeviceAccessRestriction.GATEWAY_CHILD
      identity.category in GATEWAY_CATEGORIES -> DeviceAccessRestriction.GATEWAY
      identity.category in CAMERA_CATEGORIES -> DeviceAccessRestriction.CAMERA
      identity.category in LOCK_CATEGORIES -> DeviceAccessRestriction.LOCK
      else -> DeviceAccessRestriction.NONE
    }

  private val GATEWAY_CATEGORIES = setOf("wg2", "wfcon")
  private val CAMERA_CATEGORIES = setOf("sp")
  private val LOCK_CATEGORIES =
    setOf(
      "ms",
      "bxx",
      "gyms",
      "jtmspro",
      "hotelms",
      "ms_category",
      "jtmsbh",
      "mk",
      "videolock",
      "photolock",
    )
}

data class DeviceClassification(
  val restriction: DeviceAccessRestriction,
  val family: DeviceFamilyDefinition?,
)

class DeviceClassifier(private val registry: DeviceFamilyRegistry) {
  fun classify(identity: DeviceIdentity): DeviceClassification =
    DeviceClassification(
      restriction = ProtectedDevicePolicy.restrictionFor(identity),
      family = registry.familyFor(identity.category),
    )
}
