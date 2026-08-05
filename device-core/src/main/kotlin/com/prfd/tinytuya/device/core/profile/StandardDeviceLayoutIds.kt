package com.prfd.tinytuya.device.core.profile

/**
 * Reusable presentation contracts shared by pure profiles and the Compose renderer registry.
 *
 * A layout ID selects arrangement only. It grants no capability, access, or write authority.
 */
object StandardDeviceLayoutIds {
  val GENERIC_CONTROLS = DeviceLayoutId("generic.controls")
  val LIGHT = DeviceLayoutId("light")
  val COVER = DeviceLayoutId("cover")
}
