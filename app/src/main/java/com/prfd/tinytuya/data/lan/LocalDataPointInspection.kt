package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.device.core.schema.DpDeclaredType
import com.prfd.tinytuya.device.core.schema.DpDefinition

internal data class InspectedDataPoint(
    val id: String,
    val kindLabel: String,
    val code: String?,
    val safeValue: String,
)

internal data class LocalDataPointInspection(
    val totalCount: Int,
    val dataPoints: List<InspectedDataPoint>,
)

/** Builds the capped safe inspector model outside Compose from the normalized mapping boundary. */
internal fun inspectLocalDataPoints(
    device: CloudImportedDevice,
    dataPoints: List<LocalDataPoint>,
): LocalDataPointInspection {
    val schema = parseTuyaDpSchema(device.mappingJson)
    val inspected = dataPoints
        .sortedWith(
            compareBy<LocalDataPoint> { it.id.toIntOrNull() ?: Int.MAX_VALUE }
                .thenBy { it.id }
        )
        .map { dataPoint ->
            val definition = schema[dataPoint.id]
            InspectedDataPoint(
                id = dataPoint.id,
                kindLabel = inspectionKindLabel(dataPoint, definition),
                code = definition?.code,
                safeValue = inspectionValue(dataPoint, definition),
            )
        }
    return LocalDataPointInspection(
        totalCount = dataPoints.size,
        dataPoints = inspected,
    )
}

private fun inspectionKindLabel(
    dataPoint: LocalDataPoint,
    definition: DpDefinition?,
): String = when {
    dataPoint.kind == LocalDataPointKind.STRING &&
        definition?.declaredType == DpDeclaredType.ENUM -> "Enum"
    dataPoint.kind == LocalDataPointKind.STRING -> "Text"
    dataPoint.kind == LocalDataPointKind.JSON -> "Structured"
    dataPoint.kind == LocalDataPointKind.NULL -> "Null"
    else -> dataPoint.kind.wireValue.replaceFirstChar { character -> character.uppercase() }
}

private fun inspectionValue(
    dataPoint: LocalDataPoint,
    definition: DpDefinition?,
): String = when (dataPoint.kind) {
    LocalDataPointKind.BOOLEAN -> dataPoint.value
        .takeIf { value -> value == "true" || value == "false" }
        ?: "Invalid Boolean hidden"
    LocalDataPointKind.INTEGER, LocalDataPointKind.DECIMAL -> dataPoint.value
        .takeIf { value ->
            value.length <= MAX_INSPECTED_VALUE_LENGTH && NUMERIC_WIRE_VALUE.matches(value)
        }
        ?: "Invalid numeric value hidden"
    LocalDataPointKind.STRING -> inspectedTextValue(dataPoint.value, definition)
    LocalDataPointKind.JSON -> "Structured value hidden"
    LocalDataPointKind.NULL -> "No value"
}

private fun inspectedTextValue(value: String, definition: DpDefinition?): String {
    if (value.isBlank()) return "Empty"
    val code = definition?.code ?: return "Unmapped text hidden"
    if (
        definition.declaredType == DpDeclaredType.RAW ||
        code.containsSensitiveTerm() ||
        value.length > MAX_INSPECTED_TEXT_LENGTH ||
        value.any(Char::isISOControl)
    ) {
        return "Sensitive or invalid text hidden"
    }
    return value
}

private fun String.containsSensitiveTerm(): Boolean =
    split('_').any { segment -> segment in SENSITIVE_CODE_SEGMENTS }

private val NUMERIC_WIRE_VALUE = Regex("-?[0-9]+(?:\\.[0-9]+)?")
private val SENSITIVE_CODE_SEGMENTS = setOf(
    "auth",
    "credential",
    "key",
    "password",
    "secret",
    "token",
)
private const val MAX_INSPECTED_VALUE_LENGTH = 40
private const val MAX_INSPECTED_TEXT_LENGTH = 120
