package com.prfd.tinytuya.device.core.capability

@JvmInline
value class CapabilityId(val value: String) {
    init {
        require(value.length in 1..MAX_ID_LENGTH && ID.matches(value)) {
            "Capability IDs must be bounded lowercase identifiers."
        }
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_ID_LENGTH = 80
        val ID = Regex("[a-z0-9_.-]+")
    }
}

enum class CapabilityTone {
    NEUTRAL,
    NORMAL,
    ACTIVE,
    ALERT,
}

enum class MeasurementDisplay {
    SCALED_VALUE,
    PERCENTAGE,
}

enum class MeasurementUnitPolicy {
    SAFE_MAPPED,
    TEMPERATURE,
    PERCENTAGE,
    DISTANCE,
    SIGNAL,
}

data class CapabilityChoice(
    val wireValue: String,
    val label: String,
) {
    init {
        requireSafeWireValue(wireValue)
        requireSafeLabel(label)
    }
}

data class CapabilityState(
    val wireValue: String,
    val label: String,
    val tone: CapabilityTone,
) {
    init {
        requireSafeWireValue(wireValue)
        requireSafeLabel(label)
    }
}

sealed interface CapabilitySpec {
    val id: CapabilityId
    val label: String
    val codeCandidates: List<String>
    val writable: Boolean
}

data class ToggleCapabilitySpec(
    override val id: CapabilityId,
    override val label: String,
    override val codeCandidates: List<String>,
    override val writable: Boolean,
) : CapabilitySpec {
    init {
        validateSpec(label, codeCandidates)
    }
}

data class RangeCapabilitySpec(
    override val id: CapabilityId,
    override val label: String,
    override val codeCandidates: List<String>,
    override val writable: Boolean,
    val display: MeasurementDisplay = MeasurementDisplay.SCALED_VALUE,
) : CapabilitySpec {
    init {
        validateSpec(label, codeCandidates)
    }
}

data class ChoiceCapabilitySpec(
    override val id: CapabilityId,
    override val label: String,
    override val codeCandidates: List<String>,
    override val writable: Boolean,
    val choices: List<CapabilityChoice>,
) : CapabilitySpec {
    init {
        validateSpec(label, codeCandidates)
        require(choices.isNotEmpty() && choices.size <= MAX_OPTIONS && choices.distinctBy { it.wireValue }.size == choices.size) {
            "Choice capabilities require bounded unique options."
        }
    }
}

data class ActionGroupCapabilitySpec(
    override val id: CapabilityId,
    override val label: String,
    override val codeCandidates: List<String>,
    override val writable: Boolean,
    val actions: List<CapabilityChoice>,
) : CapabilitySpec {
    init {
        validateSpec(label, codeCandidates)
        require(actions.isNotEmpty() && actions.size <= MAX_OPTIONS && actions.distinctBy { it.wireValue }.size == actions.size) {
            "Action groups require bounded unique actions."
        }
    }
}

data class ColorCapabilitySpec(
    override val id: CapabilityId,
    override val label: String,
    override val codeCandidates: List<String>,
    override val writable: Boolean,
) : CapabilitySpec {
    init {
        validateSpec(label, codeCandidates)
    }
}

data class MeasurementCapabilitySpec(
    override val id: CapabilityId,
    override val label: String,
    override val codeCandidates: List<String>,
    val display: MeasurementDisplay = MeasurementDisplay.SCALED_VALUE,
    val unitPolicy: MeasurementUnitPolicy = MeasurementUnitPolicy.SAFE_MAPPED,
) : CapabilitySpec {
    override val writable: Boolean = false

    init {
        validateSpec(label, codeCandidates)
    }
}

data class BinaryStateCapabilitySpec(
    override val id: CapabilityId,
    override val label: String,
    override val codeCandidates: List<String>,
    val states: List<CapabilityState>,
) : CapabilitySpec {
    override val writable: Boolean = false

    init {
        validateSpec(label, codeCandidates)
        require(states.isNotEmpty() && states.size <= MAX_OPTIONS && states.distinctBy { it.wireValue }.size == states.size) {
            "Binary-state capabilities require bounded unique states."
        }
    }
}

data class SafeTextCapabilitySpec(
    override val id: CapabilityId,
    override val label: String,
    override val codeCandidates: List<String>,
    val allowedValues: List<String>,
) : CapabilitySpec {
    override val writable: Boolean = false

    init {
        validateSpec(label, codeCandidates)
        require(
            allowedValues.isNotEmpty() &&
                allowedValues.size <= MAX_OPTIONS &&
                allowedValues.distinct().size == allowedValues.size
        ) {
            "Safe-text capabilities require a bounded allow-list."
        }
        allowedValues.forEach(::requireSafeWireValue)
    }
}

private fun validateSpec(label: String, codes: List<String>) {
    requireSafeLabel(label)
    require(codes.isNotEmpty() && codes.size <= MAX_CODE_CANDIDATES) {
        "Capability specs require bounded code candidates."
    }
    require(codes.distinct().size == codes.size && codes.all { code ->
        code.length in 1..MAX_CODE_LENGTH && CODE.matches(code)
    }) {
        "Capability code candidates must be unique normalized codes."
    }
}

private fun requireSafeLabel(label: String) {
    require(label.length in 1..MAX_LABEL_LENGTH && label.none(Char::isISOControl)) {
        "Capability labels must be bounded single-line text."
    }
}

private fun requireSafeWireValue(value: String) {
    require(value.length in 1..MAX_WIRE_VALUE_LENGTH && value.none(Char::isISOControl)) {
        "Capability wire values must be bounded single-line text."
    }
}

private const val MAX_LABEL_LENGTH = 80
private const val MAX_CODE_LENGTH = 64
private const val MAX_CODE_CANDIDATES = 8
private const val MAX_OPTIONS = 32
private const val MAX_WIRE_VALUE_LENGTH = 128
private val CODE = Regex("[a-z0-9_]+")
