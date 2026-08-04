package com.prfd.tinytuya.device.core.capability

import com.prfd.tinytuya.device.core.schema.DpDeclaredType
import com.prfd.tinytuya.device.core.schema.DpDefinition
import com.prfd.tinytuya.device.core.schema.DpSchema
import java.math.BigDecimal

/** The only component allowed to construct resolved capabilities. */
object CapabilityResolver {
    const val MAX_CAPABILITY_COUNT = 64

    fun resolve(
        specs: List<CapabilitySpec>,
        schema: DpSchema,
        observation: DeviceObservation,
        access: CapabilityAccess,
    ): ResolvedDeviceCapabilities {
        if (
            access == CapabilityAccess.DENIED ||
            schema.isEmpty ||
            schema.rejectedAsOversized ||
            !observation.isFresh ||
            observation.rejectedAsOversized ||
            specs.size > MAX_CAPABILITY_COUNT ||
            specs.map(CapabilitySpec::id).distinct().size != specs.size
        ) {
            return ResolvedDeviceCapabilities.EMPTY
        }

        return ResolvedDeviceCapabilities(
            specs.mapNotNull { spec ->
                runCatching { resolveOne(spec, schema, observation, access) }.getOrNull()
            }
        )
    }

    private fun resolveOne(
        spec: CapabilitySpec,
        schema: DpSchema,
        observation: DeviceObservation,
        access: CapabilityAccess,
    ): ResolvedCapability? {
        val definition = selectDefinition(spec.codeCandidates, schema) ?: return null
        val point = observation[definition.id] ?: return null
        val code = definition.code ?: return null
        val writable = spec.writable && access == CapabilityAccess.READ_WRITE
        return when (spec) {
            is ToggleCapabilitySpec -> {
                if (
                    definition.declaredType != DpDeclaredType.BOOLEAN ||
                    point.kind != ObservedDataPointKind.BOOLEAN
                ) return null
                ResolvedToggle(
                    spec.id,
                    spec.label,
                    definition.id,
                    code,
                    writable,
                    BooleanCapabilityCodec.decode(point.value) ?: return null,
                )
            }
            is RangeCapabilitySpec -> resolveRange(spec, definition, point, code, writable)
            is ChoiceCapabilitySpec -> resolveChoice(spec, definition, point, code, writable)
            is ActionGroupCapabilitySpec -> resolveActionGroup(
                spec,
                definition,
                point,
                code,
                writable,
            )
            is ColorCapabilitySpec -> {
                if (
                    definition.declaredType != DpDeclaredType.JSON ||
                    point.kind != ObservedDataPointKind.STRING
                ) return null
                ResolvedColor(
                    spec.id,
                    spec.label,
                    definition.id,
                    code,
                    writable,
                    TuyaHsvV2CapabilityCodec.decode(point.value) ?: return null,
                )
            }
            is MeasurementCapabilitySpec -> resolveMeasurement(spec, definition, point, code)
            is BinaryStateCapabilitySpec -> resolveBinaryState(spec, definition, point, code)
            is SafeTextCapabilitySpec -> resolveSafeText(spec, definition, point, code)
        }
    }

    private fun resolveRange(
        spec: RangeCapabilitySpec,
        definition: DpDefinition,
        point: ObservedDataPoint,
        code: String,
        writable: Boolean,
    ): ResolvedRange? {
        if (
            definition.declaredType != DpDeclaredType.INTEGER ||
            point.kind != ObservedDataPointKind.INTEGER ||
            definition.constraints.scale != 0
        ) return null
        val minimum = definition.constraints.minimum.exactIntOrNull() ?: return null
        val maximum = definition.constraints.maximum.exactIntOrNull() ?: return null
        val step = definition.constraints.step.exactIntOrNull() ?: return null
        val current = IntegerCapabilityCodec.decode(point.value, minimum, maximum, step) ?: return null
        val displayValue = when (spec.display) {
            MeasurementDisplay.SCALED_VALUE -> current.toString()
            MeasurementDisplay.PERCENTAGE -> PercentageCapabilityCodec.normalize(
                value = current.toBigDecimal(),
                minimum = minimum.toBigDecimal(),
                maximum = maximum.toBigDecimal(),
            )?.let { "$it%" } ?: return null
        }
        return ResolvedRange(
            spec.id,
            spec.label,
            definition.id,
            code,
            writable,
            minimum,
            maximum,
            step,
            current,
            displayValue,
        )
    }

    private fun resolveChoice(
        spec: ChoiceCapabilitySpec,
        definition: DpDefinition,
        point: ObservedDataPoint,
        code: String,
        writable: Boolean,
    ): ResolvedChoice? {
        if (
            definition.declaredType != DpDeclaredType.ENUM ||
            point.kind != ObservedDataPointKind.STRING
        ) return null
        val declared = definition.constraints.enumValues
        if (spec.choices.any { choice -> choice.wireValue !in declared }) return null
        val current = EnumCapabilityCodec.decode(point.value, declared) ?: return null
        val currentChoice = spec.choices.firstOrNull { choice -> choice.wireValue == current }
        return ResolvedChoice(
            spec.id,
            spec.label,
            definition.id,
            code,
            writable,
            currentChoice?.wireValue,
            currentChoice?.label,
            spec.choices,
        )
    }

    private fun resolveActionGroup(
        spec: ActionGroupCapabilitySpec,
        definition: DpDefinition,
        point: ObservedDataPoint,
        code: String,
        writable: Boolean,
    ): ResolvedActionGroup? {
        if (
            definition.declaredType != DpDeclaredType.ENUM ||
            point.kind != ObservedDataPointKind.STRING
        ) return null
        val declared = definition.constraints.enumValues
        if (spec.actions.any { action -> action.wireValue !in declared }) return null
        val current = EnumCapabilityCodec.decode(point.value, declared) ?: return null
        return ResolvedActionGroup(
            spec.id,
            spec.label,
            definition.id,
            code,
            writable,
            spec.actions.firstOrNull { action -> action.wireValue == current }?.wireValue,
            spec.actions,
        )
    }

    private fun resolveMeasurement(
        spec: MeasurementCapabilitySpec,
        definition: DpDefinition,
        point: ObservedDataPoint,
        code: String,
    ): ResolvedMeasurement? {
        if (
            definition.declaredType !in NUMERIC_DECLARED_TYPES ||
            point.kind !in NUMERIC_OBSERVED_TYPES
        ) return null
        val value = point.value.toBigDecimalOrNull() ?: return null
        val constraints = definition.constraints
        if (
            constraints.minimum?.let { value < it } == true ||
            constraints.maximum?.let { value > it } == true
        ) return null
        val scale = constraints.scale ?: 0
        if (scale !in 0..MAX_SCALE) return null
        val displayValue = when (spec.display) {
            MeasurementDisplay.PERCENTAGE -> PercentageCapabilityCodec.normalize(
                value,
                constraints.minimum,
                constraints.maximum,
            )?.let { "$it%" } ?: return null
            MeasurementDisplay.SCALED_VALUE -> {
                val number = value.movePointLeft(scale).stripTrailingZeros().toPlainString()
                if (number.length > MAX_DISPLAY_VALUE_LENGTH) return null
                val unit = safeUnit(code, constraints.unit.orEmpty(), spec.unitPolicy)
                if (unit.isBlank()) number else "$number $unit"
            }
        }
        return ResolvedMeasurement(spec.id, spec.label, definition.id, code, value, displayValue)
    }

    private fun resolveBinaryState(
        spec: BinaryStateCapabilitySpec,
        definition: DpDefinition,
        point: ObservedDataPoint,
        code: String,
    ): ResolvedBinaryState? {
        val rawValue = when (definition.declaredType) {
            DpDeclaredType.BOOLEAN -> {
                if (point.kind != ObservedDataPointKind.BOOLEAN) return null
                BooleanCapabilityCodec.decode(point.value)?.toString() ?: return null
            }
            DpDeclaredType.ENUM -> {
                if (point.kind != ObservedDataPointKind.STRING) return null
                EnumCapabilityCodec.decode(
                    point.value,
                    definition.constraints.enumValues,
                ) ?: return null
            }
            else -> return null
        }
        val state = spec.states.firstOrNull { state -> state.wireValue == rawValue } ?: return null
        return ResolvedBinaryState(
            spec.id,
            spec.label,
            definition.id,
            code,
            state.label,
            state.tone,
        )
    }

    private fun resolveSafeText(
        spec: SafeTextCapabilitySpec,
        definition: DpDefinition,
        point: ObservedDataPoint,
        code: String,
    ): ResolvedSafeText? {
        if (point.kind != ObservedDataPointKind.STRING) return null
        val value = point.value
        if (
            value.length > MAX_SAFE_TEXT_LENGTH ||
            value.any(Char::isISOControl) ||
            definition.declaredType !in setOf(DpDeclaredType.STRING, DpDeclaredType.ENUM)
        ) return null
        if (value !in spec.allowedValues) return null
        if (
            definition.declaredType == DpDeclaredType.ENUM &&
            EnumCapabilityCodec.decode(value, definition.constraints.enumValues) == null
        ) return null
        return ResolvedSafeText(spec.id, spec.label, definition.id, code, value)
    }

    private fun selectDefinition(codes: List<String>, schema: DpSchema): DpDefinition? {
        codes.forEach { code ->
            val matches = schema.definitions.filter { definition -> definition.code == code }
            if (matches.size > 1) return null
            if (matches.size == 1) return matches.single()
        }
        return null
    }

    private fun safeUnit(
        code: String,
        rawUnit: String,
        policy: MeasurementUnitPolicy,
    ): String = when (policy) {
        MeasurementUnitPolicy.TEMPERATURE -> when (rawUnit.trim().lowercase()) {
            "℃", "°c", "c" -> "°C"
            "℉", "°f", "f" -> "°F"
            else -> ""
        }
        MeasurementUnitPolicy.PERCENTAGE -> "%"
        MeasurementUnitPolicy.DISTANCE -> when (rawUnit.trim().lowercase()) {
            "mm" -> "mm"
            "m" -> "m"
            else -> "cm"
        }
        MeasurementUnitPolicy.SIGNAL -> "dBm"
        MeasurementUnitPolicy.SAFE_MAPPED -> SAFE_UNITS[rawUnit.trim().lowercase()].orEmpty()
    }

    private fun BigDecimal?.exactIntOrNull(): Int? = this
        ?.takeIf { value -> value.scale() == 0 }
        ?.let { value -> runCatching(value::intValueExact).getOrNull() }

    private val NUMERIC_DECLARED_TYPES = setOf(
        DpDeclaredType.INTEGER,
        DpDeclaredType.VALUE,
        DpDeclaredType.FLOAT,
    )
    private val NUMERIC_OBSERVED_TYPES = setOf(
        ObservedDataPointKind.INTEGER,
        ObservedDataPointKind.DECIMAL,
    )
    private val SAFE_UNITS = mapOf(
        "%" to "%",
        "w" to "W",
        "kw" to "kW",
        "wh" to "Wh",
        "kwh" to "kWh",
        "v" to "V",
        "mv" to "mV",
        "a" to "A",
        "ma" to "mA",
        "ppm" to "ppm",
        "ppb" to "ppb",
    )
    private const val MAX_SCALE = 9
    private const val MAX_DISPLAY_VALUE_LENGTH = 32
    private const val MAX_SAFE_TEXT_LENGTH = 120
}
