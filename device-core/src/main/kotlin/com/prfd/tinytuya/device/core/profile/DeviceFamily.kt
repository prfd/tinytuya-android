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

enum class DeviceSupportLevel {
    REAL_HARDWARE,
    SYNTHETIC_ONLY,
    EXPERIMENTAL,
}

data class DeviceSupport(
    val level: DeviceSupportLevel,
    val summary: String,
) {
    init {
        require(summary.length in 1..MAX_SUMMARY_LENGTH && summary.none(Char::isISOControl)) {
            "Device support summaries must be bounded single-line text."
        }
    }

    private companion object {
        const val MAX_SUMMARY_LENGTH = 240
    }
}

data class DevicePresentation(
    val layoutId: DeviceLayoutId,
    val typeLabel: String,
    val symbol: String,
) {
    init {
        require(typeLabel.length in 1..MAX_LABEL_LENGTH && typeLabel.none(Char::isISOControl)) {
            "Device type labels must be bounded single-line text."
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

/** Non-secret imported metadata made available to device-family matchers. */
class DeviceIdentity private constructor(
    val category: String,
    val productId: String,
    val productName: String,
    val model: String,
    val isSubDevice: Boolean,
) {
    companion object {
        fun normalize(
            category: String,
            productId: String,
            productName: String,
            model: String,
            isSubDevice: Boolean,
        ): DeviceIdentity = DeviceIdentity(
            category = category
                .trim()
                .lowercase()
                .takeIf { value ->
                    value.length in 1..MAX_CATEGORY_LENGTH && CATEGORY.matches(value)
                }
                .orEmpty(),
            productId = normalizeText(productId, MAX_PRODUCT_ID_LENGTH),
            productName = normalizeText(productName, MAX_DISPLAY_METADATA_LENGTH),
            model = normalizeText(model, MAX_DISPLAY_METADATA_LENGTH),
            isSubDevice = isSubDevice,
        )

        private fun normalizeText(value: String, maximumLength: Int): String = value
            .trim()
            .takeIf { text -> text.length <= maximumLength && text.none(Char::isISOControl) }
            .orEmpty()

        private const val MAX_CATEGORY_LENGTH = 64
        private const val MAX_PRODUCT_ID_LENGTH = 128
        private const val MAX_DISPLAY_METADATA_LENGTH = 160
        private val CATEGORY = Regex("[a-z0-9_]+")
    }
}

enum class DeviceMatchStrength(val rank: Int) {
    NONE(0),
    HEURISTIC_SCHEMA(100),
    DISTINCTIVE_SCHEMA(200),
    CATEGORY(300),
    CATEGORY_AND_SCHEMA(350),
    EXACT_PRODUCT(400),
}

data class DeviceMatch(val strength: DeviceMatchStrength) {
    companion object {
        val NONE = DeviceMatch(DeviceMatchStrength.NONE)
    }
}

interface DeviceFamilyDefinition {
    val id: DeviceFamilyId
    val support: DeviceSupport
    val presentation: DevicePresentation

    fun match(identity: DeviceIdentity, schema: DpSchema): DeviceMatch

    /** Declares intent from normalized metadata only; fresh observed DPS is never exposed here. */
    fun capabilitySpecs(identity: DeviceIdentity, schema: DpSchema): List<CapabilitySpec> =
        emptyList()
}

sealed interface DeviceFamilyResolution {
    data object Unmatched : DeviceFamilyResolution

    data class Matched(
        val definition: DeviceFamilyDefinition,
        val match: DeviceMatch,
    ) : DeviceFamilyResolution

    data class Ambiguous(
        val familyIds: List<DeviceFamilyId>,
        val strength: DeviceMatchStrength,
    ) : DeviceFamilyResolution
}

/** Explicit registry whose outcome is independent of definition order. */
class DeviceFamilyRegistry(definitions: List<DeviceFamilyDefinition>) {
    val definitions: List<DeviceFamilyDefinition> = definitions.toList()

    init {
        require(this.definitions.map(DeviceFamilyDefinition::id).distinct().size == definitions.size) {
            "Device family IDs must be unique."
        }
    }

    fun resolve(identity: DeviceIdentity, schema: DpSchema): DeviceFamilyResolution {
        val matches = definitions.mapNotNull { definition ->
            val match = runCatching { definition.match(identity, schema) }
                .getOrDefault(DeviceMatch.NONE)
            match.takeIf { candidate -> candidate.strength != DeviceMatchStrength.NONE }
                ?.let { candidate -> definition to candidate }
        }
        val strongestRank = matches.maxOfOrNull { (_, match) -> match.strength.rank }
            ?: return DeviceFamilyResolution.Unmatched
        val strongest = matches.filter { (_, match) -> match.strength.rank == strongestRank }
        if (strongest.size != 1) {
            return DeviceFamilyResolution.Ambiguous(
                familyIds = strongest.map { (definition) -> definition.id }.sortedBy { it.value },
                strength = strongest.first().second.strength,
            )
        }
        val (definition, match) = strongest.single()
        return DeviceFamilyResolution.Matched(definition = definition, match = match)
    }
}
