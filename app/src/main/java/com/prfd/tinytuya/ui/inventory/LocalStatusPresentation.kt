package com.prfd.tinytuya.ui.inventory

import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.python.CloudImportedDevice
import java.math.BigDecimal
import org.json.JSONObject

internal data class PresentedDataPoint(
    val label: String,
    val value: String,
)

internal fun presentLocalDataPoints(
    device: CloudImportedDevice,
    dataPoints: List<LocalDataPoint>,
): List<PresentedDataPoint> {
    val mapping = runCatching { JSONObject(device.mappingJson) }.getOrNull()
    val mappedCodes = dataPoints.associate { dataPoint ->
        dataPoint.id to mapping?.optJSONObject(dataPoint.id)?.optString("code").orEmpty()
    }
    val mappedSwitchCount = mappedCodes.values.count { code ->
        code == "switch" || code == "switch_led" || code.startsWith("switch_")
    }
    return dataPoints.take(MAX_PRESENTED_DATA_POINTS).map { dataPoint ->
        val definition = mapping?.optJSONObject(dataPoint.id)
        val code = mappedCodes.getValue(dataPoint.id)
        val label = dataPointLabel(
            device = device,
            id = dataPoint.id,
            code = code,
            isOnlyMappedSwitch = mappedSwitchCount == 1,
        )
        PresentedDataPoint(
            label = label,
            value = dataPointValue(
                dataPoint = dataPoint,
                code = code,
                definition = definition,
                isPower = label == "Power",
            ),
        )
    }
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
    if (code.isNotBlank()) return code.toReadableLabel()
    if (id == "1" && device.category in SWITCH_CATEGORIES) return "Power"
    return "DP $id"
}

private fun dataPointValue(
    dataPoint: LocalDataPoint,
    code: String,
    definition: JSONObject?,
    isPower: Boolean,
): String = when (dataPoint.kind) {
    LocalDataPointKind.BOOLEAN -> when {
        isPower || code == "switch" || code == "switch_led" || code.startsWith("switch_") ->
            if (dataPoint.value == "true") "On" else "Off"
        dataPoint.value == "true" -> "Yes"
        else -> "No"
    }
    LocalDataPointKind.INTEGER, LocalDataPointKind.DECIMAL -> {
        val values = definition?.optJSONObject("values")
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
    LocalDataPointKind.STRING -> dataPoint.value
        .take(MAX_PRESENTED_VALUE_LENGTH)
        .ifBlank { "Empty" }
    LocalDataPointKind.JSON -> "Structured value"
    LocalDataPointKind.NULL -> "No value"
}

private fun String.toReadableLabel(): String =
    split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { character -> character.uppercase() }
        }
        .ifBlank { "Unknown value" }

private val SWITCH_CATEGORIES = setOf("kg", "cz", "pc")
private const val MAX_PRESENTED_DATA_POINTS = 6
private const val MAX_PRESENTED_VALUE_LENGTH = 80
