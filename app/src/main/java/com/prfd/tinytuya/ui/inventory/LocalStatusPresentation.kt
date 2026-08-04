package com.prfd.tinytuya.ui.inventory

import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.python.CloudImportedDevice
import java.math.BigDecimal
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
): List<PresentedDataPoint> {
    val mapping = runCatching { JSONObject(device.mappingJson) }.getOrNull()
    val candidates = dataPoints.map { dataPoint ->
        val definition = mapping?.optJSONObject(dataPoint.id)
        PresentationCandidate(
            dataPoint = dataPoint,
            definition = definition,
            code = definition.safeMappingCode().orEmpty(),
        )
    }
    val mappedSwitchCount = candidates.count { candidate ->
        val code = candidate.code
        code == "switch" || code == "switch_led" || code.startsWith("switch_")
    }
    return candidates
        .filter { candidate -> isCardHighlight(candidate.code) }
        .sortedWith(
            compareBy<PresentationCandidate> { dataPointPriority(it.code) }
                .thenBy { it.dataPoint.id.toIntOrNull() ?: Int.MAX_VALUE }
                .thenBy { it.dataPoint.id }
        )
        .take(MAX_PRESENTED_DATA_POINTS)
        .map { candidate ->
            val dataPoint = candidate.dataPoint
            val code = candidate.code
            val label = dataPointLabel(
                device = device,
                id = dataPoint.id,
                code = code,
                isOnlyMappedSwitch = mappedSwitchCount == 1,
            )
            PresentedDataPoint(
                id = dataPoint.id,
                label = label,
                value = dataPointValue(
                    dataPoint = dataPoint,
                    code = code,
                    definition = candidate.definition,
                    isPower = label == "Power",
                ),
            )
        }
}

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

private fun dataPointLabel(
    device: CloudImportedDevice,
    id: String,
    code: String,
    isOnlyMappedSwitch: Boolean,
): String {
    if (code == "switch" || code == "switch_led") return "Power"
    if (code.startsWith("switch_")) {
        if (isOnlyMappedSwitch) return "Power"
        val suffix = code.removePrefix("switch_")
        return suffix.toIntOrNull()?.let { "Switch $it" } ?: "Power"
    }
    standardDataPointLabel(code)?.let { return it }
    if (code.isNotBlank()) return code.toReadableLabel()
    if (id == "1" && device.category.trim().lowercase() in SWITCH_CATEGORIES) return "Power"
    return "DP $id"
}

private fun dataPointValue(
    dataPoint: LocalDataPoint,
    code: String,
    definition: JSONObject?,
    isPower: Boolean,
): String {
    if (code in PERCENTAGE_CODES) {
        formatMappedPercentage(dataPoint.value, definition)?.let { return it }
    }
    return when (dataPoint.kind) {
        LocalDataPointKind.BOOLEAN -> when {
            isPower || code == "switch" || code == "switch_led" || code.startsWith("switch_") ->
                if (dataPoint.value == "true") "On" else "Off"
            dataPoint.value == "true" -> "Yes"
            else -> "No"
        }
        LocalDataPointKind.INTEGER, LocalDataPointKind.DECIMAL -> {
            val values = definition.mappingValues()
            val scale = values?.optInt("scale", 0)?.coerceIn(0, 9) ?: 0
            val unit = values?.optString("unit").orEmpty()
            runCatching {
                BigDecimal(dataPoint.value)
                    .movePointLeft(scale)
                    .stripTrailingZeros()
                    .toPlainString()
            }.getOrDefault(dataPoint.value).let { number ->
                if (unit.isBlank()) number else "$number $unit"
            }
        }
        LocalDataPointKind.STRING -> when {
            dataPoint.value.isBlank() -> "Empty"
            code == "work_mode" -> mappedEnumValue(
                definition = definition,
                value = dataPoint.value,
                maximumLength = MAX_PRESENTED_VALUE_LENGTH,
            )?.toReadableLabel() ?: "Unknown mode"
            else -> mappedEnumValue(
                definition = definition,
                value = dataPoint.value,
                maximumLength = MAX_PRESENTED_VALUE_LENGTH,
            ) ?: "Text value hidden"
        }
        LocalDataPointKind.JSON -> "Structured value hidden"
        LocalDataPointKind.NULL -> "No value"
    }
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

private fun mappedEnumValue(
    definition: JSONObject?,
    value: String,
    maximumLength: Int,
): String? {
    if (
        definition?.optString("type")?.equals("Enum", ignoreCase = true) != true ||
        value.length > maximumLength ||
        value.any { character -> character.isISOControl() }
    ) {
        return null
    }
    val range = definition.mappingValues()?.optJSONArray("range") ?: return null
    return value.takeIf { candidate ->
        (0 until range.length()).any { index -> range.optString(index) == candidate }
    }
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

private fun formatMappedPercentage(rawValue: String, definition: JSONObject?): String? {
    val value = rawValue.toBigDecimalOrNull() ?: return null
    val values = definition.mappingValues()
    val minimum = values?.optString("min")?.toBigDecimalOrNull()
    val maximum = values?.optString("max")?.toBigDecimalOrNull()
    val normalized = if (minimum != null && maximum != null && maximum > minimum) {
        value.subtract(minimum)
            .multiply(BigDecimal(100))
            .divide(maximum.subtract(minimum), 0, java.math.RoundingMode.HALF_UP)
    } else {
        value
    }
    return normalized
        .coerceIn(BigDecimal.ZERO, BigDecimal(100))
        .stripTrailingZeros()
        .toPlainString() + "%"
}

private fun standardDataPointLabel(code: String): String? = when {
    code == "cur_power" -> "Power draw"
    code == "cur_voltage" -> "Voltage"
    code == "cur_current" -> "Current"
    code == "add_ele" -> "Energy"
    code == "bright_value" || code == "bright_value_v2" -> "Brightness"
    code == "temp_value" || code == "temp_value_v2" -> "Color temperature"
    code == "work_mode" -> "Mode"
    code == "percent_state" || code == "percent_state_2" -> "Position"
    else -> null
}

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

private fun JSONObject?.mappingValues(): JSONObject? {
    if (this == null) return null
    return when (val values = opt("values")) {
        is JSONObject -> values
        is String -> runCatching { JSONObject(values) }.getOrNull()
        else -> null
    }
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

private fun String.toReadableLabel(): String =
    split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { character -> character.uppercase() }
        }
        .ifBlank { "Unknown value" }

private val SWITCH_CATEGORIES = setOf("kg", "cz", "pc")
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
private val PERCENTAGE_CODES = setOf(
    "bright_value",
    "bright_value_v2",
    "temp_value",
    "temp_value_v2",
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
private const val MAX_PRESENTED_VALUE_LENGTH = 80
private const val MAX_INSPECTED_VALUE_LENGTH = 40
private const val MAX_INSPECTED_TEXT_LENGTH = 120
private const val MAX_MAPPING_CODE_LENGTH = 64

private data class PresentationCandidate(
    val dataPoint: LocalDataPoint,
    val definition: JSONObject?,
    val code: String,
)
