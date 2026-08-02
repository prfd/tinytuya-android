package com.prfd.tinytuya.ui.inventory

import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.lan.LocalSensorKind
import com.prfd.tinytuya.data.python.CloudImportedDevice
import java.math.BigDecimal
import org.json.JSONObject

internal enum class LocalSensorTone {
    NEUTRAL,
    NORMAL,
    ACTIVE,
    ALERT,
}

internal data class PresentedSensorReading(
    val dataPointId: String,
    val label: String,
    val value: String,
)

internal data class LocalSensorPresentation(
    val primary: PresentedSensorReading,
    val secondary: List<PresentedSensorReading>,
    val tone: LocalSensorTone,
) {
    val consumedDataPointIds: Set<String> =
        (listOf(primary) + secondary).mapTo(linkedSetOf()) { reading -> reading.dataPointId }
}

/**
 * Builds a deliberately small sensor summary from cached cloud mappings and live local DPS.
 * Text is accepted only for declared enums with a known standard meaning. Arbitrary device
 * strings and structured values never cross this presentation boundary.
 */
internal fun presentLocalSensor(
    device: CloudImportedDevice,
    sensorKind: LocalSensorKind,
    dataPoints: List<LocalDataPoint>,
): LocalSensorPresentation? {
    val mapping = runCatching { JSONObject(device.mappingJson) }.getOrNull() ?: return null
    val candidates = dataPoints.mapNotNull { dataPoint ->
        val definition = mapping.optJSONObject(dataPoint.id) ?: return@mapNotNull null
        val code = definition.sensorMappingCode() ?: return@mapNotNull null
        SensorCandidate(dataPoint, definition, code)
    }
    val readings = when (sensorKind) {
        LocalSensorKind.CLIMATE -> listOfNotNull(
            candidates.numericReading(
                codes = listOf("temp_current", "va_temperature"),
                label = "Temperature",
            ),
            candidates.numericReading(
                codes = listOf("humidity_value", "va_humidity"),
                label = "Humidity",
            ),
            candidates.batteryReading(),
        )
        LocalSensorKind.CONTACT -> listOfNotNull(
            candidates.booleanStateReading(
                code = "doorcontact_state",
                label = "Contact",
                falseState = "Closed" to LocalSensorTone.NORMAL,
                trueState = "Open" to LocalSensorTone.ACTIVE,
            ),
            candidates.batteryReading(),
            candidates.numericReading(listOf("signal_strength"), "Signal"),
        )
        LocalSensorKind.MOTION -> listOfNotNull(
            candidates.enumStateReading(
                codes = listOf("pir"),
                label = "Motion",
                states = mapOf(
                    "none" to ("No motion" to LocalSensorTone.NORMAL),
                    "pir" to ("Motion detected" to LocalSensorTone.ACTIVE),
                ),
            ),
            candidates.batteryReading(),
        )
        LocalSensorKind.PRESENCE -> listOfNotNull(
            candidates.enumStateReading(
                codes = listOf("presence_state"),
                label = "Presence",
                states = mapOf(
                    "none" to ("Room clear" to LocalSensorTone.NORMAL),
                    "presence" to ("Presence detected" to LocalSensorTone.ACTIVE),
                    "peaceful" to ("Still presence" to LocalSensorTone.ACTIVE),
                    "small_move" to ("Small movement" to LocalSensorTone.ACTIVE),
                    "large_move" to ("Large movement" to LocalSensorTone.ACTIVE),
                ),
            ),
            candidates.numericReading(listOf("target_dis_closest"), "Closest target"),
            candidates.batteryReading(),
        )
        LocalSensorKind.WATER_LEAK -> listOfNotNull(
            candidates.enumStateReading(
                codes = listOf("watersensor_state"),
                label = "Water",
                states = mapOf(
                    "normal" to ("No leak reported" to LocalSensorTone.NORMAL),
                    "alarm" to ("Leak detected" to LocalSensorTone.ALERT),
                ),
            ),
            candidates.batteryReading(),
        )
        LocalSensorKind.SMOKE -> listOfNotNull(
            candidates.enumStateReading(
                codes = listOf("smoke_sensor_status", "smoke_sensor_state"),
                label = "Smoke",
                states = mapOf(
                    "normal" to ("No smoke alarm" to LocalSensorTone.NORMAL),
                    "alarm" to ("Smoke alarm" to LocalSensorTone.ALERT),
                    "2" to ("No smoke alarm" to LocalSensorTone.NORMAL),
                    "1" to ("Smoke alarm" to LocalSensorTone.ALERT),
                ),
            ),
            candidates.numericReading(listOf("smoke_sensor_value"), "Smoke level"),
            candidates.batteryReading(),
        )
        LocalSensorKind.GAS -> listOfNotNull(
            candidates.enumStateReading(
                codes = listOf("gas_sensor_status", "gas_sensor_state"),
                label = "Gas",
                states = mapOf(
                    "normal" to ("No gas alarm" to LocalSensorTone.NORMAL),
                    "alarm" to ("Gas alarm" to LocalSensorTone.ALERT),
                    "2" to ("No gas alarm" to LocalSensorTone.NORMAL),
                    "1" to ("Gas alarm" to LocalSensorTone.ALERT),
                ),
            ),
            candidates.numericReading(listOf("gas_sensor_value"), "Gas level"),
            candidates.batteryReading(),
        )
    }
    val primary = readings.firstOrNull() ?: return null
    return LocalSensorPresentation(
        primary = primary.reading,
        secondary = readings.drop(1).take(MAX_SECONDARY_SENSOR_READINGS).map { it.reading },
        tone = primary.tone,
    )
}

private fun List<SensorCandidate>.batteryReading(): SensorReadingCandidate? =
    numericReading(listOf("battery_percentage"), "Battery")

private fun List<SensorCandidate>.booleanStateReading(
    code: String,
    label: String,
    falseState: Pair<String, LocalSensorTone>,
    trueState: Pair<String, LocalSensorTone>,
): SensorReadingCandidate? {
    val candidate = firstByCode(listOf(code)) ?: return null
    if (
        candidate.dataPoint.kind != LocalDataPointKind.BOOLEAN ||
        !candidate.definition.optString("type").equals("Boolean", ignoreCase = true)
    ) {
        return null
    }
    val state = when (candidate.dataPoint.value) {
        "false" -> falseState
        "true" -> trueState
        else -> return null
    }
    return SensorReadingCandidate(
        reading = PresentedSensorReading(candidate.dataPoint.id, label, state.first),
        tone = state.second,
    )
}

private fun List<SensorCandidate>.enumStateReading(
    codes: List<String>,
    label: String,
    states: Map<String, Pair<String, LocalSensorTone>>,
): SensorReadingCandidate? {
    val candidate = firstByCode(codes) ?: return null
    if (
        candidate.dataPoint.kind != LocalDataPointKind.STRING ||
        !candidate.definition.optString("type").equals("Enum", ignoreCase = true)
    ) {
        return null
    }
    val rawValue = candidate.dataPoint.value
    if (!candidate.definition.declaresEnumValue(rawValue)) return null
    val state = states[rawValue] ?: return null
    return SensorReadingCandidate(
        reading = PresentedSensorReading(candidate.dataPoint.id, label, state.first),
        tone = state.second,
    )
}

private fun List<SensorCandidate>.numericReading(
    codes: List<String>,
    label: String,
): SensorReadingCandidate? {
    val candidate = firstByCode(codes) ?: return null
    if (
        candidate.dataPoint.kind !in NUMERIC_DATA_POINT_KINDS ||
        candidate.definition.optString("type").lowercase() !in NUMERIC_MAPPING_TYPES
    ) {
        return null
    }
    val rawValue = candidate.dataPoint.value
    if (rawValue.length > MAX_SENSOR_WIRE_VALUE_LENGTH || !NUMERIC_WIRE_VALUE.matches(rawValue)) {
        return null
    }
    val number = rawValue.toBigDecimalOrNull() ?: return null
    val values = candidate.definition.sensorMappingValues()
    if (!number.isWithinDeclaredRange(values)) return null
    val scale = values?.optInt("scale", 0)?.coerceIn(0, MAX_SENSOR_SCALE) ?: 0
    val formattedNumber = number
        .movePointLeft(scale)
        .stripTrailingZeros()
        .toPlainString()
        .takeIf { value -> value.length <= MAX_SENSOR_DISPLAY_VALUE_LENGTH }
        ?: return null
    val unit = sensorUnit(candidate.code, values?.optString("unit").orEmpty())
    return SensorReadingCandidate(
        reading = PresentedSensorReading(
            dataPointId = candidate.dataPoint.id,
            label = label,
            value = if (unit.isBlank()) formattedNumber else "$formattedNumber $unit",
        ),
        tone = LocalSensorTone.NEUTRAL,
    )
}

private fun List<SensorCandidate>.firstByCode(codes: List<String>): SensorCandidate? =
    codes.firstNotNullOfOrNull { code -> firstOrNull { candidate -> candidate.code == code } }

private fun JSONObject.declaresEnumValue(value: String): Boolean {
    if (value.length > MAX_SENSOR_ENUM_LENGTH || value.any { character -> character.isISOControl() }) {
        return false
    }
    val range = sensorMappingValues()?.optJSONArray("range") ?: return false
    return (0 until range.length()).any { index -> range.optString(index) == value }
}

private fun JSONObject.sensorMappingValues(): JSONObject? = when (val values = opt("values")) {
    is JSONObject -> values
    is String -> runCatching { JSONObject(values) }.getOrNull()
    else -> null
}

private fun JSONObject.sensorMappingCode(): String? = optString("code")
    .trim()
    .lowercase()
    .takeIf { code -> code.length in 1..MAX_SENSOR_CODE_LENGTH && SENSOR_CODE.matches(code) }

private fun BigDecimal.isWithinDeclaredRange(values: JSONObject?): Boolean {
    if (values == null) return true
    val minimum = values.decimalProperty("min")
    val maximum = values.decimalProperty("max")
    return (minimum == null || this >= minimum) && (maximum == null || this <= maximum)
}

private fun JSONObject.decimalProperty(name: String): BigDecimal? {
    val value = opt(name) ?: return null
    if (value == JSONObject.NULL) return null
    return value.toString()
        .takeIf { text -> text.length <= MAX_SENSOR_WIRE_VALUE_LENGTH }
        ?.toBigDecimalOrNull()
}

private fun sensorUnit(code: String, rawUnit: String): String = when (code) {
    "temp_current", "va_temperature" -> when (rawUnit.trim().lowercase()) {
        "℃", "°c", "c" -> "°C"
        "℉", "°f", "f" -> "°F"
        else -> ""
    }
    "humidity_value", "va_humidity", "battery_percentage" -> "%"
    "target_dis_closest" -> when (rawUnit.trim().lowercase()) {
        "mm" -> "mm"
        "m" -> "m"
        else -> "cm"
    }
    "signal_strength" -> "dBm"
    else -> when (rawUnit.trim().lowercase()) {
        "%" -> "%"
        "ppm" -> "ppm"
        "ppb" -> "ppb"
        else -> ""
    }
}

private data class SensorCandidate(
    val dataPoint: LocalDataPoint,
    val definition: JSONObject,
    val code: String,
)

private data class SensorReadingCandidate(
    val reading: PresentedSensorReading,
    val tone: LocalSensorTone,
)

private val NUMERIC_DATA_POINT_KINDS = setOf(
    LocalDataPointKind.INTEGER,
    LocalDataPointKind.DECIMAL,
)
private val NUMERIC_MAPPING_TYPES = setOf("integer", "value", "float")
private val NUMERIC_WIRE_VALUE = Regex("-?[0-9]+(?:\\.[0-9]+)?")
private val SENSOR_CODE = Regex("[a-z0-9_]+")
private const val MAX_SECONDARY_SENSOR_READINGS = 2
private const val MAX_SENSOR_CODE_LENGTH = 64
private const val MAX_SENSOR_ENUM_LENGTH = 40
private const val MAX_SENSOR_WIRE_VALUE_LENGTH = 40
private const val MAX_SENSOR_DISPLAY_VALUE_LENGTH = 32
private const val MAX_SENSOR_SCALE = 9
