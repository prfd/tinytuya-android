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
            code = definition?.optString("code").orEmpty().trim().lowercase(),
        )
    }
    val mappedSwitchCount = candidates.count { candidate ->
        val code = candidate.code
        code == "switch" || code == "switch_led" || code.startsWith("switch_")
    }
    return candidates
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
    if (id == "1" && device.category in SWITCH_CATEGORIES) return "Power"
    return "DP $id"
}

private fun dataPointValue(
    dataPoint: LocalDataPoint,
    code: String,
    definition: JSONObject?,
    isPower: Boolean,
): String {
    if (code.startsWith("countdown_")) {
        formatCountdown(dataPoint.value)?.let { return it }
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
        LocalDataPointKind.STRING -> dataPoint.value
            .take(MAX_PRESENTED_VALUE_LENGTH)
            .ifBlank { "Empty" }
        LocalDataPointKind.JSON -> "Structured value"
        LocalDataPointKind.NULL -> "No value"
    }
}

private fun standardDataPointLabel(code: String): String? = when {
    code == "cur_power" -> "Power draw"
    code == "cur_voltage" -> "Voltage"
    code == "cur_current" -> "Current"
    code == "add_ele" -> "Energy"
    code == "relay_status" -> "Power-on state"
    code == "child_lock" -> "Child lock"
    code == "switch_backlight" -> "Indicator"
    code.startsWith("countdown_") -> code.removePrefix("countdown_")
        .toIntOrNull()
        ?.let { channel -> if (channel == 1) "Countdown" else "Switch $channel countdown" }
        ?: "Countdown"
    else -> null
}

private fun dataPointPriority(code: String): Int = when {
    code == "cur_power" -> 0
    code == "cur_voltage" -> 1
    code == "cur_current" -> 2
    code == "add_ele" -> 3
    code.startsWith("countdown_") -> 4
    else -> 10
}

private fun formatCountdown(rawValue: String): String? {
    val totalSeconds = rawValue.toLongOrNull()?.takeIf { it >= 0L } ?: return null
    if (totalSeconds == 0L) return "Off"
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return buildList {
        if (hours > 0L) add("${hours}h")
        if (minutes > 0L) add("${minutes}m")
        if (seconds > 0L && hours == 0L) add("${seconds}s")
    }.joinToString(" ")
}

private fun JSONObject?.mappingValues(): JSONObject? {
    if (this == null) return null
    return when (val values = opt("values")) {
        is JSONObject -> values
        is String -> runCatching { JSONObject(values) }.getOrNull()
        else -> null
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
private const val MAX_PRESENTED_DATA_POINTS = 6
private const val MAX_PRESENTED_VALUE_LENGTH = 80

private data class PresentationCandidate(
    val dataPoint: LocalDataPoint,
    val definition: JSONObject?,
    val code: String,
)
