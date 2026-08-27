package com.prfd.tinytuya.device.core.capability

import java.math.BigDecimal

enum class CapabilityAccess {
  DENIED,
  READ_ONLY,
  READ_WRITE,
}

/**
 * A concrete capability resolved from a family capability spec, the imported cloud DP schema, and a
 * matching local DP observation.
 *
 * Implementations are produced by [CapabilityResolver] and expose a display-safe model plus the
 * exact Tuya mapping metadata needed to identify the backing data point.
 *
 * @property id Stable capability identifier used by UI, intents, and authorization.
 * @property label User-facing capability name supplied by the device family.
 * @property dataPointId Numeric Tuya DP id that backs this capability.
 * @property code Tuya DP mapping code for the backing data point.
 * @property writable Whether a control command may target this capability, derived from both the
 *   capability spec and the applied access policy.
 */
sealed interface ResolvedCapability {
  val id: CapabilityId
  val label: String
  val dataPointId: String
  val code: String
  val writable: Boolean
}

data class ResolvedToggle
internal constructor(
  override val id: CapabilityId,
  override val label: String,
  override val dataPointId: String,
  override val code: String,
  override val writable: Boolean,
  val currentValue: Boolean,
) : ResolvedCapability

data class ResolvedRange
internal constructor(
  override val id: CapabilityId,
  override val label: String,
  override val dataPointId: String,
  override val code: String,
  override val writable: Boolean,
  val minimum: Int,
  val maximum: Int,
  val step: Int,
  val currentValue: Int,
  val displayValue: String,
) : ResolvedCapability

data class ResolvedChoice
internal constructor(
  override val id: CapabilityId,
  override val label: String,
  override val dataPointId: String,
  override val code: String,
  override val writable: Boolean,
  val currentWireValue: String?,
  val currentLabel: String?,
  val choices: List<CapabilityChoice>,
) : ResolvedCapability

data class ResolvedActionGroup
internal constructor(
  override val id: CapabilityId,
  override val label: String,
  override val dataPointId: String,
  override val code: String,
  override val writable: Boolean,
  val currentWireValue: String?,
  val actions: List<CapabilityChoice>,
) : ResolvedCapability

data class ResolvedColor
internal constructor(
  override val id: CapabilityId,
  override val label: String,
  override val dataPointId: String,
  override val code: String,
  override val writable: Boolean,
  val currentColor: TuyaHsvColor,
) : ResolvedCapability

data class ResolvedMeasurement
internal constructor(
  override val id: CapabilityId,
  override val label: String,
  override val dataPointId: String,
  override val code: String,
  val rawValue: BigDecimal,
  val displayValue: String,
) : ResolvedCapability {
  override val writable: Boolean = false
}

data class ResolvedBinaryState
internal constructor(
  override val id: CapabilityId,
  override val label: String,
  override val dataPointId: String,
  override val code: String,
  val value: String,
  val tone: CapabilityTone,
) : ResolvedCapability {
  override val writable: Boolean = false
}

data class ResolvedSafeText
internal constructor(
  override val id: CapabilityId,
  override val label: String,
  override val dataPointId: String,
  override val code: String,
  val value: String,
) : ResolvedCapability {
  override val writable: Boolean = false
}

class ResolvedDeviceCapabilities internal constructor(capabilities: List<ResolvedCapability>) {
  val capabilities: List<ResolvedCapability> = capabilities.toList()

  inline fun <reified T : ResolvedCapability> ofType(): List<T> = capabilities.filterIsInstance<T>()

  companion object {
    val EMPTY = ResolvedDeviceCapabilities(emptyList())
  }
}
