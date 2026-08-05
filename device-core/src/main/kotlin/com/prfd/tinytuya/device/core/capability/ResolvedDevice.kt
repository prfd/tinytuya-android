package com.prfd.tinytuya.device.core.capability

import com.prfd.tinytuya.device.core.profile.DeviceLayoutId

/** Safe resolved input for UI modules; transport bindings remain inside [capabilities]. */
class ResolvedDevice
private constructor(
  val deviceId: String,
  val layoutId: DeviceLayoutId?,
  val capabilities: ResolvedDeviceCapabilities,
) {
  override fun toString(): String =
    "ResolvedDevice(deviceId=[REDACTED], layoutId=$layoutId, " +
      "capabilityCount=${capabilities.capabilities.size})"

  companion object {
    fun create(
      deviceId: String,
      layoutId: DeviceLayoutId?,
      capabilities: ResolvedDeviceCapabilities,
    ): ResolvedDevice {
      require(deviceId.length in 1..MAX_DEVICE_ID_LENGTH && deviceId.none(Char::isISOControl)) {
        "Resolved device IDs must be bounded single-line text."
      }
      return ResolvedDevice(deviceId, layoutId, capabilities)
    }

    private const val MAX_DEVICE_ID_LENGTH = 128
  }
}
