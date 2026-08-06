package com.prfd.tinytuya.device.core.profile

import com.prfd.tinytuya.device.core.capability.CapabilitySpec
import com.prfd.tinytuya.device.core.schema.DpSchema

@JvmInline
value class DeviceFamilyId(val value: String) {
  init {
    require(value.length in 1..MAX_ID_LENGTH && ID.matches(value)) {
      "Device family IDs must be bounded lowercase identifiers."
    }
  }

  override fun toString(): String = value

  private companion object {
    const val MAX_ID_LENGTH = 64
    val ID = Regex("[a-z0-9_]+")
  }
}

@JvmInline
value class DeviceLayoutId(val value: String) {
  init {
    require(value.length in 1..MAX_ID_LENGTH && ID.matches(value)) {
      "Device layout IDs must be bounded lowercase identifiers."
    }
  }

  override fun toString(): String = value

  private companion object {
    const val MAX_ID_LENGTH = 80
    val ID = Regex("[a-z0-9_.-]+")
  }
}

data class DevicePresentation(
  val layoutId: DeviceLayoutId,
  val typeLabel: String,
  val symbol: String,
  val familyLabel: String = typeLabel,
) {
  init {
    require(typeLabel.length in 1..MAX_LABEL_LENGTH && typeLabel.none(Char::isISOControl)) {
      "Device type labels must be bounded single-line text."
    }
    require(familyLabel.length in 1..MAX_LABEL_LENGTH && familyLabel.none(Char::isISOControl)) {
      "Device family labels must be bounded single-line text."
    }
    require(symbol.length in 1..MAX_SYMBOL_LENGTH && symbol.none(Char::isISOControl)) {
      "Device symbols must be bounded single-line text."
    }
  }

  private companion object {
    const val MAX_LABEL_LENGTH = 80
    const val MAX_SYMBOL_LENGTH = 8
  }
}

/** Bounded imported metadata used by category selection and protected-device policy. */
class DeviceIdentity
private constructor(
  val category: String,
  val isSubDevice: Boolean,
) {
  companion object {
    fun normalize(
      category: String,
      isSubDevice: Boolean,
    ): DeviceIdentity =
      DeviceIdentity(
        category = category.normalizedDeviceCategory(),
        isSubDevice = isSubDevice,
      )
  }
}

interface DeviceFamilyDefinition {
  val id: DeviceFamilyId
  val categories: Set<String>
  val presentation: DevicePresentation

  /** Declares intent from normalized metadata only; fresh observed DPS is never exposed here. */
  fun capabilitySpecs(schema: DpSchema): List<CapabilitySpec> = emptyList()
}

/** Explicit category registry whose outcome is independent of definition order. */
class DeviceFamilyRegistry(definitions: List<DeviceFamilyDefinition>) {
  val definitions: List<DeviceFamilyDefinition> = definitions.toList()
  private val definitionsByCategory: Map<String, DeviceFamilyDefinition>

  init {
    require(this.definitions.map(DeviceFamilyDefinition::id).distinct().size == definitions.size) {
      "Device family IDs must be unique."
    }
    require(this.definitions.all { definition -> definition.categories.isNotEmpty() }) {
      "Device families must declare at least one category."
    }
    val registrations =
      this.definitions.flatMap { definition ->
        definition.categories.map { category -> category to definition }
      }
    require(
      registrations.all { (category) ->
        category.isNotEmpty() && category.normalizedDeviceCategory() == category
      }
    ) {
      "Device family categories must be bounded lowercase identifiers."
    }
    require(registrations.map { (category) -> category }.distinct().size == registrations.size) {
      "Device family categories must be unique."
    }
    definitionsByCategory = registrations.toMap()
  }

  fun familyFor(category: String): DeviceFamilyDefinition? =
    definitionsByCategory[category.normalizedDeviceCategory()]
}

private fun String.normalizedDeviceCategory(): String =
  trim()
    .lowercase()
    .takeIf { value -> value.length in 1..MAX_CATEGORY_LENGTH && DEVICE_CATEGORY.matches(value) }
    .orEmpty()

private const val MAX_CATEGORY_LENGTH = 64
private val DEVICE_CATEGORY = Regex("[a-z0-9_]+")
