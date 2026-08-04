package com.prfd.tinytuya.device.core.capability

import java.math.BigDecimal
import java.math.RoundingMode

object BooleanCapabilityCodec {
    fun decode(value: String): Boolean? = when (value) {
        "true" -> true
        "false" -> false
        else -> null
    }

    fun encode(value: Boolean): String = value.toString()
}

object IntegerCapabilityCodec {
    fun decode(
        value: String,
        minimum: Int,
        maximum: Int,
        step: Int,
    ): Int? {
        if (
            value.length !in 1..MAX_INTEGER_TEXT_LENGTH ||
            !INTEGER.matches(value) ||
            minimum !in -MAX_ABSOLUTE_VALUE..MAX_ABSOLUTE_VALUE ||
            maximum !in -MAX_ABSOLUTE_VALUE..MAX_ABSOLUTE_VALUE ||
            minimum >= maximum ||
            step !in 1..(maximum - minimum)
        ) {
            return null
        }
        val decoded = value.toIntOrNull() ?: return null
        return decoded.takeIf { candidate ->
            candidate in minimum..maximum && (candidate - minimum) % step == 0
        }
    }

    fun encode(value: Int, minimum: Int, maximum: Int, step: Int): String? =
        decode(value.toString(), minimum, maximum, step)?.toString()

    const val MAX_ABSOLUTE_VALUE = 1_000_000_000
    private const val MAX_INTEGER_TEXT_LENGTH = 32
    private val INTEGER = Regex("-?[0-9]+")
}

object EnumCapabilityCodec {
    fun decode(value: String, declaredValues: Collection<String>): String? {
        if (
            value.length !in 1..MAX_ENUM_VALUE_LENGTH ||
            value.any(Char::isISOControl) ||
            declaredValues.size > MAX_ENUM_VALUES
        ) {
            return null
        }
        return value.takeIf(declaredValues::contains)
    }

    fun encode(value: String, declaredValues: Collection<String>): String? =
        decode(value, declaredValues)

    private const val MAX_ENUM_VALUES = 128
    private const val MAX_ENUM_VALUE_LENGTH = 128
}

object PercentageCapabilityCodec {
    fun normalize(
        value: BigDecimal,
        minimum: BigDecimal?,
        maximum: BigDecimal?,
    ): Int? {
        val normalized = if (minimum != null && maximum != null && maximum > minimum) {
            if (value < minimum || value > maximum) return null
            value.subtract(minimum)
                .multiply(ONE_HUNDRED)
                .divide(maximum.subtract(minimum), 0, RoundingMode.HALF_UP)
        } else {
            value
        }
        return normalized
            .coerceIn(BigDecimal.ZERO, ONE_HUNDRED)
            .setScale(0, RoundingMode.HALF_UP)
            .toInt()
    }

    private val ONE_HUNDRED = BigDecimal(100)
}

data class TuyaHsvColor(
    val hue: Int,
    val saturation: Int,
    val brightness: Int,
)

/** Tuya's colour_data_v2 codec: four hexadecimal digits per HSV component. */
object TuyaHsvV2CapabilityCodec {
    fun decode(value: String): TuyaHsvColor? {
        if (value.length != ENCODED_LENGTH || !HEX.matches(value)) return null
        val color = runCatching {
            TuyaHsvColor(
                hue = value.substring(0, 4).toInt(16),
                saturation = value.substring(4, 8).toInt(16),
                brightness = value.substring(8, 12).toInt(16),
            )
        }.getOrNull() ?: return null
        return color.takeIf { candidate ->
            candidate.hue in 0..MAX_HUE &&
                candidate.saturation in 0..MAX_COMPONENT &&
                candidate.brightness in 0..MAX_COMPONENT
        }
    }

    fun encode(color: TuyaHsvColor): String? = color
        .takeIf { candidate ->
            candidate.hue in 0..MAX_HUE &&
                candidate.saturation in 0..MAX_COMPONENT &&
                candidate.brightness in 0..MAX_COMPONENT
        }
        ?.let { candidate ->
            "%04x%04x%04x".format(
                candidate.hue,
                candidate.saturation,
                candidate.brightness,
            )
        }

    const val MAX_HUE = 360
    const val MAX_COMPONENT = 1_000
    private const val ENCODED_LENGTH = 12
    private val HEX = Regex("[0-9a-fA-F]{12}")
}
