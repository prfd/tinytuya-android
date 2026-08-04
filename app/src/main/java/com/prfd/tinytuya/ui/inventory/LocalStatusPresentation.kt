package com.prfd.tinytuya.ui.inventory

import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.lan.LocalDeviceCapabilityRegistry
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.device.core.capability.ResolvedChoice
import com.prfd.tinytuya.device.core.capability.ResolvedDeviceCapabilities
import com.prfd.tinytuya.device.core.capability.ResolvedMeasurement
import org.json.JSONObject

internal data class PresentedDataPoint(
    val id: String,
    val label: String,
    val value: String,
)

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

internal fun presentLocalDataPoints(
    device: CloudImportedDevice,
    dataPoints: List<LocalDataPoint>,
): List<PresentedDataPoint> = presentLocalDataPoints(
    capabilities = LocalDeviceCapabilityRegistry.resolveFreshReadOnly(device, dataPoints),
)

/** Bridges resolved read-only primitives into the compact main-card highlight model. */
internal fun presentLocalDataPoints(
    capabilities: ResolvedDeviceCapabilities,
    excludedDataPointIds: Set<String> = emptySet(),
): List<PresentedDataPoint> = capabilities.capabilities
        .filter { capability -> capability.dataPointId !in excludedDataPointIds }
        .mapNotNull { capability ->
            when (capability) {
                is ResolvedMeasurement -> PresentedDataPoint(
                    id = capability.dataPointId,
                    label = capability.label,
                    value = capability.displayValue,
                ) to capability.code
                is ResolvedChoice -> PresentedDataPoint(
                    id = capability.dataPointId,
                    label = capability.label,
                    value = capability.currentLabel ?: "Unknown mode",
                ) to capability.code
                else -> null
            }
        }
        .filter { (_, code) -> isCardHighlight(code) }
        .sortedWith(
            compareBy<Pair<PresentedDataPoint, String>> { (_, code) -> dataPointPriority(code) }
                .thenBy { (dataPoint) -> dataPoint.id.toIntOrNull() ?: Int.MAX_VALUE }
                .thenBy { (dataPoint) -> dataPoint.id }
        )
        .take(MAX_PRESENTED_DATA_POINTS)
        .map(Pair<PresentedDataPoint, String>::first)

internal fun inspectLocalDataPoints(
    device: CloudImportedDevice,
    dataPoints: List<LocalDataPoint>,
): LocalDataPointInspection {
    val mapping = runCatching { JSONObject(device.mappingJson) }.getOrNull()
    val inspected = dataPoints
        .sortedWith(
            compareBy<LocalDataPoint> { it.id.toIntOrNull() ?: Int.MAX_VALUE }
                .thenBy { it.id }
        )
        .map { dataPoint ->
            val definition = mapping?.optJSONObject(dataPoint.id)
            InspectedDataPoint(
                id = dataPoint.id,
                kindLabel = inspectionKindLabel(dataPoint, definition),
                code = definition.safeMappingCode(),
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
    definition: JSONObject?,
): String = when {
    dataPoint.kind == LocalDataPointKind.STRING &&
        definition?.optString("type")?.equals("Enum", ignoreCase = true) == true -> "Enum"
    dataPoint.kind == LocalDataPointKind.STRING -> "Text"
    dataPoint.kind == LocalDataPointKind.JSON -> "Structured"
    dataPoint.kind == LocalDataPointKind.NULL -> "Null"
    else -> dataPoint.kind.wireValue.replaceFirstChar { character -> character.uppercase() }
}

private fun inspectionValue(
    dataPoint: LocalDataPoint,
    definition: JSONObject?,
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

private fun inspectedTextValue(value: String, definition: JSONObject?): String {
    if (value.isBlank()) return "Empty"
    val code = definition.safeMappingCode() ?: return "Unmapped text hidden"
    val type = definition?.optString("type").orEmpty()
    if (
        type.equals("Raw", ignoreCase = true) ||
        code.containsSensitiveTerm() ||
        value.length > MAX_INSPECTED_TEXT_LENGTH ||
        value.any { character -> character.isISOControl() }
    ) {
        return "Sensitive or invalid text hidden"
    }
    return value
}

private fun String.containsSensitiveTerm(): Boolean =
    split('_').any { segment -> segment in SENSITIVE_CODE_SEGMENTS }

private fun isCardHighlight(code: String): Boolean =
    code in CARD_HIGHLIGHT_CODES

private fun dataPointPriority(code: String): Int = when {
    code == "cur_power" -> 0
    code == "cur_voltage" -> 1
    code == "cur_current" -> 2
    code == "add_ele" -> 3
    code == "bright_value" || code == "bright_value_v2" -> 4
    code == "temp_value" || code == "temp_value_v2" -> 5
    code == "work_mode" -> 6
    code == "percent_state" || code == "percent_state_2" -> 7
    else -> 10
}

private fun JSONObject?.safeMappingCode(): String? {
    if (this == null) return null
    return optString("code")
        .trim()
        .lowercase()
        .takeIf { code ->
            code.length in 1..MAX_MAPPING_CODE_LENGTH && MAPPING_CODE.matches(code)
        }
}

private val MAPPING_CODE = Regex("[a-z0-9_]+")
private val NUMERIC_WIRE_VALUE = Regex("-?[0-9]+(?:\\.[0-9]+)?")
private val CARD_HIGHLIGHT_CODES = setOf(
    "cur_power",
    "cur_voltage",
    "cur_current",
    "add_ele",
    "bright_value",
    "bright_value_v2",
    "temp_value",
    "temp_value_v2",
    "work_mode",
    "percent_state",
    "percent_state_2",
)
private val SENSITIVE_CODE_SEGMENTS = setOf(
    "auth",
    "credential",
    "key",
    "password",
    "secret",
    "token",
)
private const val MAX_PRESENTED_DATA_POINTS = 6
private const val MAX_INSPECTED_VALUE_LENGTH = 40
private const val MAX_INSPECTED_TEXT_LENGTH = 120
private const val MAX_MAPPING_CODE_LENGTH = 64
