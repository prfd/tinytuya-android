package com.prfd.tinytuya.device.core.capability

import java.math.BigDecimal

enum class CapabilityAccess {
    DENIED,
    READ_ONLY,
    READ_WRITE,
}

sealed interface ResolvedCapability {
    val id: CapabilityId
    val label: String
    val dataPointId: String
    val code: String
    val writable: Boolean
}

data class ResolvedToggle internal constructor(
    override val id: CapabilityId,
    override val label: String,
    override val dataPointId: String,
    override val code: String,
    override val writable: Boolean,
    val currentValue: Boolean,
) : ResolvedCapability

data class ResolvedRange internal constructor(
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

data class ResolvedChoice internal constructor(
    override val id: CapabilityId,
    override val label: String,
    override val dataPointId: String,
    override val code: String,
    override val writable: Boolean,
    val currentWireValue: String?,
    val currentLabel: String?,
    val choices: List<CapabilityChoice>,
) : ResolvedCapability

data class ResolvedActionGroup internal constructor(
    override val id: CapabilityId,
    override val label: String,
    override val dataPointId: String,
    override val code: String,
    override val writable: Boolean,
    val currentWireValue: String?,
    val actions: List<CapabilityChoice>,
) : ResolvedCapability

data class ResolvedColor internal constructor(
    override val id: CapabilityId,
    override val label: String,
    override val dataPointId: String,
    override val code: String,
    override val writable: Boolean,
    val currentColor: TuyaHsvColor,
) : ResolvedCapability

data class ResolvedMeasurement internal constructor(
    override val id: CapabilityId,
    override val label: String,
    override val dataPointId: String,
    override val code: String,
    val rawValue: BigDecimal,
    val displayValue: String,
) : ResolvedCapability {
    override val writable: Boolean = false
}

data class ResolvedBinaryState internal constructor(
    override val id: CapabilityId,
    override val label: String,
    override val dataPointId: String,
    override val code: String,
    val value: String,
    val tone: CapabilityTone,
) : ResolvedCapability {
    override val writable: Boolean = false
}

data class ResolvedSafeText internal constructor(
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

    inline fun <reified T : ResolvedCapability> ofType(): List<T> =
        capabilities.filterIsInstance<T>()

    companion object {
        val EMPTY = ResolvedDeviceCapabilities(emptyList())
    }
}
