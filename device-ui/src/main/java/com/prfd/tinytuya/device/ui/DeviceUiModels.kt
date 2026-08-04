package com.prfd.tinytuya.device.ui

import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.CapabilityTone
import com.prfd.tinytuya.device.core.capability.ResolvedActionGroup
import com.prfd.tinytuya.device.core.capability.ResolvedBinaryState
import com.prfd.tinytuya.device.core.capability.ResolvedChoice
import com.prfd.tinytuya.device.core.capability.ResolvedColor
import com.prfd.tinytuya.device.core.capability.ResolvedDevice
import com.prfd.tinytuya.device.core.capability.ResolvedMeasurement
import com.prfd.tinytuya.device.core.capability.ResolvedRange
import com.prfd.tinytuya.device.core.capability.ResolvedSafeText
import com.prfd.tinytuya.device.core.capability.ResolvedToggle

sealed interface CapabilityUiModel {
    val id: CapabilityId
    val label: String
    val writable: Boolean
}

data class ToggleUiModel(
    override val id: CapabilityId,
    override val label: String,
    override val writable: Boolean,
    val currentValue: Boolean,
) : CapabilityUiModel {
    init {
        requireSafeDisplayLabel(label)
    }
}

data class RangeUiModel(
    override val id: CapabilityId,
    override val label: String,
    override val writable: Boolean,
    val minimum: Int,
    val maximum: Int,
    val step: Int,
    val currentValue: Int,
    val displayValue: String,
) : CapabilityUiModel {
    init {
        requireSafeDisplayLabel(label)
        require(
            minimum in -MAX_RANGE_ABSOLUTE_VALUE..MAX_RANGE_ABSOLUTE_VALUE &&
                maximum in -MAX_RANGE_ABSOLUTE_VALUE..MAX_RANGE_ABSOLUTE_VALUE &&
                minimum < maximum &&
                step > 0 &&
                currentValue in minimum..maximum
        ) {
            "Range UI models require ordered bounds and a current value inside them."
        }
        require((currentValue.toLong() - minimum.toLong()) % step.toLong() == 0L) {
            "Range UI model values must align to their declared step."
        }
        requireSafeDisplayValue(displayValue)
    }
}

data class ChoiceUiOption(val wireValue: String, val label: String) {
    init {
        requireSafeWireValue(wireValue)
        requireSafeDisplayLabel(label)
    }
}

class ChoiceUiModel(
    override val id: CapabilityId,
    override val label: String,
    override val writable: Boolean,
    val currentWireValue: String?,
    val currentLabel: String?,
    choices: List<ChoiceUiOption>,
) : CapabilityUiModel {
    val choices: List<ChoiceUiOption> = choices.toList()

    init {
        requireSafeDisplayLabel(label)
        require(this.choices.isNotEmpty() && this.choices.size <= MAX_OPTIONS) {
            "Choice UI models require bounded options."
        }
        require(this.choices.map(ChoiceUiOption::wireValue).distinct().size == this.choices.size) {
            "Choice UI model options must be unique."
        }
        currentWireValue?.let(::requireSafeWireValue)
        currentLabel?.let(::requireSafeDisplayValue)
    }
}

class ActionGroupUiModel(
    override val id: CapabilityId,
    override val label: String,
    override val writable: Boolean,
    val currentWireValue: String?,
    actions: List<ChoiceUiOption>,
) : CapabilityUiModel {
    val actions: List<ChoiceUiOption> = actions.toList()

    init {
        requireSafeDisplayLabel(label)
        require(this.actions.isNotEmpty() && this.actions.size <= MAX_OPTIONS) {
            "Action UI models require bounded options."
        }
        require(this.actions.map(ChoiceUiOption::wireValue).distinct().size == this.actions.size) {
            "Action UI model options must be unique."
        }
        currentWireValue?.let(::requireSafeWireValue)
    }
}

data class ColorUiModel(
    override val id: CapabilityId,
    override val label: String,
    override val writable: Boolean,
    val hue: Int,
    val saturation: Int,
    val brightness: Int,
) : CapabilityUiModel {
    init {
        requireSafeDisplayLabel(label)
        require(hue in 0..360 && saturation in 0..1_000 && brightness in 0..1_000) {
            "Color UI models require bounded HSV components."
        }
    }
}

data class MeasurementUiModel(
    override val id: CapabilityId,
    override val label: String,
    val displayValue: String,
) : CapabilityUiModel {
    override val writable: Boolean = false

    init {
        requireSafeDisplayLabel(label)
        requireSafeDisplayValue(displayValue)
    }
}

data class BinaryStateUiModel(
    override val id: CapabilityId,
    override val label: String,
    val value: String,
    val tone: CapabilityTone,
) : CapabilityUiModel {
    override val writable: Boolean = false

    init {
        requireSafeDisplayLabel(label)
        requireSafeDisplayValue(value)
    }
}

data class SafeTextUiModel(
    override val id: CapabilityId,
    override val label: String,
    val value: String,
) : CapabilityUiModel {
    override val writable: Boolean = false

    init {
        requireSafeDisplayLabel(label)
        requireSafeDisplayValue(value)
    }
}

class DeviceUiModel internal constructor(
    val deviceId: String,
    val layoutId: String?,
    capabilities: List<CapabilityUiModel>,
) {
    val capabilities: List<CapabilityUiModel> = capabilities.toList()

    init {
        require(this.capabilities.size <= MAX_CAPABILITIES) {
            "Device UI models require a bounded capability list."
        }
        require(this.capabilities.map(CapabilityUiModel::id).distinct().size == this.capabilities.size) {
            "Device UI capability IDs must be unique."
        }
    }

    override fun toString(): String =
        "DeviceUiModel(deviceId=[REDACTED], layoutId=$layoutId, " +
            "capabilityCount=${capabilities.size})"
}

object DeviceUiMapper {
    fun map(device: ResolvedDevice): DeviceUiModel = DeviceUiModel(
        deviceId = device.deviceId,
        layoutId = device.layoutId?.value,
        capabilities = device.capabilities.capabilities.mapNotNull { capability ->
            when (capability) {
                is ResolvedToggle -> ToggleUiModel(
                    capability.id,
                    capability.label,
                    capability.writable,
                    capability.currentValue,
                )
                is ResolvedRange -> RangeUiModel(
                    capability.id,
                    capability.label,
                    capability.writable,
                    capability.minimum,
                    capability.maximum,
                    capability.step,
                    capability.currentValue,
                    capability.displayValue,
                )
                is ResolvedChoice -> ChoiceUiModel(
                    capability.id,
                    capability.label,
                    capability.writable,
                    capability.currentWireValue,
                    capability.currentLabel,
                    capability.choices.map { choice ->
                        ChoiceUiOption(choice.wireValue, choice.label)
                    },
                )
                is ResolvedActionGroup -> ActionGroupUiModel(
                    capability.id,
                    capability.label,
                    capability.writable,
                    capability.currentWireValue,
                    capability.actions.map { action ->
                        ChoiceUiOption(action.wireValue, action.label)
                    },
                )
                is ResolvedColor -> ColorUiModel(
                    capability.id,
                    capability.label,
                    capability.writable,
                    capability.currentColor.hue,
                    capability.currentColor.saturation,
                    capability.currentColor.brightness,
                )
                is ResolvedMeasurement -> MeasurementUiModel(
                    capability.id,
                    capability.label,
                    capability.displayValue,
                )
                is ResolvedBinaryState -> BinaryStateUiModel(
                    capability.id,
                    capability.label,
                    capability.value,
                    capability.tone,
                )
                is ResolvedSafeText -> SafeTextUiModel(
                    capability.id,
                    capability.label,
                    capability.value,
                )
            }
        },
    )
}

private fun requireSafeDisplayLabel(value: String) {
    require(value.length in 1..MAX_LABEL_LENGTH && value.none(Char::isISOControl)) {
        "UI labels must be bounded single-line text."
    }
}

private fun requireSafeDisplayValue(value: String) {
    require(value.length <= MAX_DISPLAY_VALUE_LENGTH && value.none(Char::isISOControl)) {
        "UI values must be bounded single-line text."
    }
}

private fun requireSafeWireValue(value: String) {
    require(value.length in 1..MAX_WIRE_VALUE_LENGTH && value.none(Char::isISOControl)) {
        "UI wire values must be bounded single-line text."
    }
}

private const val MAX_LABEL_LENGTH = 80
private const val MAX_DISPLAY_VALUE_LENGTH = 160
private const val MAX_WIRE_VALUE_LENGTH = 128
private const val MAX_OPTIONS = 32
private const val MAX_CAPABILITIES = 256
private const val MAX_RANGE_ABSOLUTE_VALUE = 1_000_000_000
