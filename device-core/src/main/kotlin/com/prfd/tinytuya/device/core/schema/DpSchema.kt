package com.prfd.tinytuya.device.core.schema

import java.math.BigDecimal

/** A bounded, normalized view of the Tuya mapping declared for one DPS ID. */
data class DpDefinition
internal constructor(
  val id: String,
  val code: String?,
  val declaredType: DpDeclaredType,
  val constraints: DpConstraints,
)

/** Mapping types understood by the core. Unknown values remain explicit and fail closed. */
enum class DpDeclaredType {
  BOOLEAN,
  INTEGER,
  VALUE,
  FLOAT,
  ENUM,
  STRING,
  JSON,
  RAW,
  UNKNOWN;

  companion object {
    internal fun normalize(value: String?): DpDeclaredType =
      when (value?.trim()?.lowercase()) {
        "boolean" -> BOOLEAN
        "integer" -> INTEGER
        "value" -> VALUE
        "float" -> FLOAT
        "enum" -> ENUM
        "string" -> STRING
        "json" -> JSON
        "raw" -> RAW
        else -> UNKNOWN
      }
  }
}

/** Safe constraints extracted from a mapping's optionally nested `values` object. */
data class DpConstraints
internal constructor(
  val minimum: BigDecimal?,
  val maximum: BigDecimal?,
  val step: BigDecimal?,
  val scale: Int?,
  val unit: String?,
  val enumValues: List<String>,
)

/**
 * Adapter-neutral input used to construct a [DpSchema].
 *
 * JSON belongs outside the pure Kotlin core. Android and future import adapters supply only bounded
 * primitive strings here; [DpSchema.normalize] performs the final sanitization and size checks.
 */
data class DpDefinitionInput(
  val id: String,
  val code: String?,
  val declaredType: String?,
  val minimum: String? = null,
  val maximum: String? = null,
  val step: String? = null,
  val scale: String? = null,
  val unit: String? = null,
  val enumValues: List<String> = emptyList(),
)

/**
 * Immutable mapping schema capped to the same maximum DPS count accepted from local polling.
 *
 * Oversized schemas are rejected as a whole so input iteration order can never decide which
 * controls become writable. Duplicate IDs invalidate only that ID and cannot use first/last wins.
 */
class DpSchema
private constructor(
  definitions: List<DpDefinition>,
  val rejectedAsOversized: Boolean,
) {
  val definitions: List<DpDefinition> = definitions.toList()
  private val definitionsById = this.definitions.associateBy(DpDefinition::id)

  val isEmpty: Boolean
    get() = definitions.isEmpty()

  operator fun get(id: String): DpDefinition? = definitionsById[id]

  companion object {
    const val MAX_DEFINITION_COUNT = 256
    const val MAX_DATA_POINT_ID_LENGTH = 8
    const val MAX_CODE_LENGTH = 64
    const val MAX_ENUM_VALUE_COUNT = 128
    const val MAX_ENUM_VALUE_LENGTH = 128
    const val MAX_NUMERIC_TEXT_LENGTH = 64
    const val MAX_UNIT_LENGTH = 32

    private val CODE = Regex("[a-z0-9_]+")
    private val DATA_POINT_ID = Regex("[0-9]+")
    private val DECIMAL = Regex("-?[0-9]+(?:\\.[0-9]+)?")

    fun empty(): DpSchema = DpSchema(emptyList(), rejectedAsOversized = false)

    fun normalize(inputs: Iterable<DpDefinitionInput>): DpSchema {
      val boundedInputs = inputs.take(MAX_DEFINITION_COUNT + 1)
      if (boundedInputs.size > MAX_DEFINITION_COUNT) {
        return DpSchema(emptyList(), rejectedAsOversized = true)
      }

      val definitionsById = mutableMapOf<String, DpDefinition>()
      val duplicateIds = mutableSetOf<String>()
      boundedInputs.forEach { input ->
        val id = input.id
        if (!isValidDataPointId(id) || id in duplicateIds) return@forEach
        if (id in definitionsById) {
          definitionsById.remove(id)
          duplicateIds += id
          return@forEach
        }

        definitionsById[id] =
          DpDefinition(
            id = id,
            code = normalizeCode(input.code),
            declaredType = DpDeclaredType.normalize(input.declaredType),
            constraints =
              DpConstraints(
                minimum = normalizeDecimal(input.minimum),
                maximum = normalizeDecimal(input.maximum),
                step = normalizeDecimal(input.step),
                scale = normalizeScale(input.scale),
                unit = normalizeUnit(input.unit),
                enumValues = normalizeEnumValues(input.enumValues),
              ),
          )
      }

      return DpSchema(
        definitions =
          definitionsById.values.sortedWith(
            compareBy<DpDefinition> { definition -> definition.id.toLongOrNull() ?: Long.MAX_VALUE }
              .thenBy(DpDefinition::id)
          ),
        rejectedAsOversized = false,
      )
    }

    private fun isValidDataPointId(id: String): Boolean =
      id.length in 1..MAX_DATA_POINT_ID_LENGTH &&
        DATA_POINT_ID.matches(id) &&
        id.any { character -> character != '0' }

    private fun normalizeCode(value: String?): String? =
      value?.trim()?.lowercase()?.takeIf { code ->
        code.length in 1..MAX_CODE_LENGTH && CODE.matches(code)
      }

    private fun normalizeDecimal(value: String?): BigDecimal? {
      val text = value ?: return null
      if (text.length !in 1..MAX_NUMERIC_TEXT_LENGTH || !DECIMAL.matches(text)) return null
      return text.toBigDecimalOrNull()
    }

    private fun normalizeScale(value: String?): Int? {
      val text = value ?: return null
      if (text.length !in 1..MAX_NUMERIC_TEXT_LENGTH || !DECIMAL.matches(text)) return null
      return text.toIntOrNull()
    }

    private fun normalizeUnit(value: String?): String? =
      value?.trim()?.takeIf { unit ->
        unit.isNotEmpty() && unit.length <= MAX_UNIT_LENGTH && unit.none(Char::isISOControl)
      }

    private fun normalizeEnumValues(values: List<String>): List<String> {
      if (values.size > MAX_ENUM_VALUE_COUNT) return emptyList()
      return values
        .filter { value ->
          value.length in 1..MAX_ENUM_VALUE_LENGTH && value.none(Char::isISOControl)
        }
        .distinct()
    }
  }
}
