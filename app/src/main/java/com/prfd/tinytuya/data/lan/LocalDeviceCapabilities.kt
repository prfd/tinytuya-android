package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.data.local.LocalStatusRecord
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.device.core.profile.DeviceAccessRestriction
import com.prfd.tinytuya.device.core.profile.DeviceClassification
import com.prfd.tinytuya.device.core.profile.DeviceClassifier
import com.prfd.tinytuya.device.core.profile.DeviceFamilyResolution
import com.prfd.tinytuya.device.core.profile.DeviceIdentity
import com.prfd.tinytuya.device.core.profile.ProtectedDevicePolicy
import com.prfd.tinytuya.device.core.schema.DpDeclaredType
import com.prfd.tinytuya.device.core.schema.DpSchema
import com.prfd.tinytuya.device.profiles.BuiltinDeviceFamilies
import com.prfd.tinytuya.device.profiles.BuiltinDeviceFamilyIds
import java.math.BigDecimal

data class LocalBooleanControl(
    val dataPointId: String,
    val code: String,
    val label: String,
    val currentValue: Boolean,
)

enum class LocalLightMode(val wireValue: String) {
    WHITE("white"),
    COLOR("colour"),
}

data class LocalLightHsv(
    val hue: Int,
    val saturation: Int,
    val brightness: Int,
)

data class LocalLightModeControl(
    val dataPointId: String,
    val currentMode: LocalLightMode?,
)

data class LocalLightIntegerControl(
    val dataPointId: String,
    val code: String,
    val minimum: Int,
    val maximum: Int,
    val step: Int,
    val currentValue: Int,
)

data class LocalLightColorControl(
    val dataPointId: String,
    val currentColor: LocalLightHsv,
)

data class LocalLightControls(
    val mode: LocalLightModeControl?,
    val whiteBrightness: LocalLightIntegerControl?,
    val colorTemperature: LocalLightIntegerControl?,
    val color: LocalLightColorControl?,
)

/**
 * Describes which capability model and UI presentation a device receives.
 *
 * A profile answers "what kind of device does this look like?" It does not grant permission to
 * poll or control that device. Local network authorization is decided independently by
 * [LocalDeviceAccessKind]. Recognizing a category here is also not a promise that every product
 * in that Tuya category is fully supported or validated on real hardware.
 */
enum class LocalDeviceProfileKind {
    SWITCH_OR_OUTLET,
    LIGHT,
    COVER,
    SENSOR,
    GENERIC,
}

/**
 * Small, common read-only sensor presentations understood by the inventory UI.
 *
 * These identities allow safe summaries from mapped DPS values. They remain read-only and are
 * not considered fully supported device families until representative hardware is validated.
 */
enum class LocalSensorKind {
    CLIMATE,
    CONTACT,
    MOTION,
    PRESENCE,
    WATER_LEAK,
    SMOKE,
    GAS,
}

/**
 * Describes what the app may attempt with a device over the local network.
 *
 * This is deliberately separate from [LocalDeviceProfileKind]: the profile chooses presentation,
 * while this value is the fail-closed transport and authorization decision. The protected values
 * also retain the reason local access is blocked so the UI can explain it clearly.
 */
enum class LocalDeviceAccessKind {
    /** Local polling and capability-verified writes may be attempted. */
    DIRECT_CONTROL,

    /** Local polling is allowed, but no DPS write may be dispatched. */
    STATUS_ONLY,

    /** The device is reached through a Tuya gateway rather than direct TCP 6668 access. */
    GATEWAY_CHILD,

    /** Gateway management and child routing are outside the current local protocol scope. */
    GATEWAY,

    /** Camera streams and commands are intentionally excluded for privacy and protocol scope. */
    CAMERA,

    /** Lock and access-control commands are intentionally excluded for safety. */
    LOCK,
}

data class LocalDeviceProfile(
    val kind: LocalDeviceProfileKind,
    val access: LocalDeviceAccessKind,
    val sensorKind: LocalSensorKind?,
    val mappedSwitchCount: Int,
    val booleanControls: List<LocalBooleanControl>,
    val lightControls: LocalLightControls?,
)

/**
 * Conservative registry for controls which are safe to expose locally.
 *
 * A writable Boolean switch must be declared by the cached Tuya mapping and
 * independently observed as a Boolean in a current local status response. A
 * numeric DP ID or device category alone is never enough to authorize a write.
 *
 * Category sets in this registry are conservative recognition and blocking rules, not a support
 * checklist. Public support should stay limited to device classes covered by representative tests.
 */
object LocalDeviceCapabilityRegistry {
    private val deviceClassifier = DeviceClassifier(BuiltinDeviceFamilies.registry)

    fun profile(
        device: CloudImportedDevice,
        status: LocalStatusRecord?,
        lastDiscoveryAtEpochMillis: Long?,
    ): LocalDeviceProfile {
        val schema = parseTuyaDpSchema(device.mappingJson)
        val definitions = schema.definitions
        val mappedSwitchCount = definitions.count { definition ->
            definition.declaredType == DpDeclaredType.BOOLEAN &&
                isSwitchCode(definition.code.orEmpty())
        }.coerceAtMost(MAX_BOOLEAN_CONTROLS)
        val classification = classify(device, schema)
        val legacyFamily = classification.toLegacyFamily()
        val access = accessKind(classification.restriction, legacyFamily.kind)
        return LocalDeviceProfile(
            kind = legacyFamily.kind,
            access = access,
            sensorKind = legacyFamily.sensorKind,
            mappedSwitchCount = mappedSwitchCount,
            booleanControls = if (access == LocalDeviceAccessKind.DIRECT_CONTROL) {
                booleanControls(
                    device = device,
                    status = status,
                    lastDiscoveryAtEpochMillis = lastDiscoveryAtEpochMillis,
                    schema = schema,
                )
            } else {
                emptyList()
            },
            lightControls = if (
                access == LocalDeviceAccessKind.DIRECT_CONTROL &&
                legacyFamily.kind == LocalDeviceProfileKind.LIGHT
            ) {
                lightControls(
                    device = device,
                    status = status,
                    lastDiscoveryAtEpochMillis = lastDiscoveryAtEpochMillis,
                    schema = schema,
                )
            } else {
                null
            },
        )
    }

    fun canPollStatus(device: CloudImportedDevice): Boolean =
        ProtectedDevicePolicy.restrictionFor(device.toDeviceIdentity()) ==
            DeviceAccessRestriction.NONE

    fun booleanControls(
        device: CloudImportedDevice,
        status: LocalStatusRecord?,
        lastDiscoveryAtEpochMillis: Long?,
    ): List<LocalBooleanControl> {
        val schema = parseTuyaDpSchema(device.mappingJson)
        val classification = classify(device, schema)
        val legacyFamily = classification.toLegacyFamily()
        if (
            accessKind(classification.restriction, legacyFamily.kind) !=
            LocalDeviceAccessKind.DIRECT_CONTROL
        ) {
            return emptyList()
        }
        return booleanControls(
            device = device,
            status = status,
            lastDiscoveryAtEpochMillis = lastDiscoveryAtEpochMillis,
            schema = schema,
        )
    }

    private fun booleanControls(
        device: CloudImportedDevice,
        status: LocalStatusRecord?,
        lastDiscoveryAtEpochMillis: Long?,
        schema: DpSchema,
    ): List<LocalBooleanControl> {
        if (
            device.isSubDevice ||
            status == null ||
            status.state != LocalPollDeviceState.RESPONDED ||
            lastDiscoveryAtEpochMillis == null ||
            status.polledAtEpochMillis < lastDiscoveryAtEpochMillis ||
            schema.isEmpty
        ) {
            return emptyList()
        }

        val candidates = status.dataPoints.mapNotNull { dataPoint ->
            if (
                dataPoint.kind != LocalDataPointKind.BOOLEAN ||
                dataPoint.value !in BOOLEAN_WIRE_VALUES
            ) {
                return@mapNotNull null
            }
            val definition = schema[dataPoint.id] ?: return@mapNotNull null
            val code = definition.code.orEmpty()
            if (definition.declaredType != DpDeclaredType.BOOLEAN || !isSwitchCode(code)) {
                return@mapNotNull null
            }
            Candidate(
                dataPointId = dataPoint.id,
                code = code,
                currentValue = dataPoint.value == "true",
            )
        }.sortedWith(compareBy<Candidate> { it.dataPointId.toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.dataPointId })
            .take(MAX_BOOLEAN_CONTROLS)

        return candidates.map { candidate ->
            LocalBooleanControl(
                dataPointId = candidate.dataPointId,
                code = candidate.code,
                label = switchLabel(candidate.code, candidates.size),
                currentValue = candidate.currentValue,
            )
        }
    }

    private fun lightControls(
        device: CloudImportedDevice,
        status: LocalStatusRecord?,
        lastDiscoveryAtEpochMillis: Long?,
        schema: DpSchema,
    ): LocalLightControls? {
        if (
            device.isSubDevice ||
            status == null ||
            status.state != LocalPollDeviceState.RESPONDED ||
            lastDiscoveryAtEpochMillis == null ||
            status.polledAtEpochMillis < lastDiscoveryAtEpochMillis ||
            schema.isEmpty
        ) {
            return null
        }
        val mode = status.dataPoints.firstNotNullOfOrNull { dataPoint ->
            val definition = schema[dataPoint.id] ?: return@firstNotNullOfOrNull null
            if (
                definition.code != "work_mode" ||
                definition.declaredType != DpDeclaredType.ENUM ||
                dataPoint.kind != LocalDataPointKind.STRING ||
                dataPoint.value.length > MAX_LIGHT_ENUM_LENGTH ||
                dataPoint.value.any(Char::isISOControl)
            ) {
                return@firstNotNullOfOrNull null
            }
            val declaredValues = definition.constraints.enumValues
            if (
                LocalLightMode.WHITE.wireValue !in declaredValues ||
                LocalLightMode.COLOR.wireValue !in declaredValues ||
                dataPoint.value !in declaredValues
            ) {
                return@firstNotNullOfOrNull null
            }
            LocalLightModeControl(
                dataPointId = dataPoint.id,
                currentMode = LocalLightMode.entries.firstOrNull { mode ->
                    mode.wireValue == dataPoint.value
                },
            )
        }
        val whiteBrightness = lightIntegerControl(
            status = status,
            schema = schema,
            codes = listOf("bright_value_v2", "bright_value"),
        )
        val colorTemperature = lightIntegerControl(
            status = status,
            schema = schema,
            codes = listOf("temp_value_v2", "temp_value"),
        )
        val color = status.dataPoints.firstNotNullOfOrNull { dataPoint ->
            val definition = schema[dataPoint.id] ?: return@firstNotNullOfOrNull null
            if (
                definition.code != "colour_data_v2" ||
                definition.declaredType != DpDeclaredType.JSON ||
                dataPoint.kind != LocalDataPointKind.STRING
            ) {
                return@firstNotNullOfOrNull null
            }
            decodeLightColor(dataPoint.value)?.let { currentColor ->
                LocalLightColorControl(
                    dataPointId = dataPoint.id,
                    currentColor = currentColor,
                )
            }
        }
        return LocalLightControls(
            mode = mode,
            whiteBrightness = whiteBrightness,
            colorTemperature = colorTemperature,
            color = color,
        ).takeIf { controls ->
            controls.mode != null ||
                controls.whiteBrightness != null ||
                controls.colorTemperature != null ||
                controls.color != null
        }
    }

    private fun lightIntegerControl(
        status: LocalStatusRecord,
        schema: DpSchema,
        codes: List<String>,
    ): LocalLightIntegerControl? = codes.firstNotNullOfOrNull codeLoop@ { requestedCode ->
        status.dataPoints.firstNotNullOfOrNull dataPointLoop@ { dataPoint ->
            val definition = schema[dataPoint.id] ?: return@dataPointLoop null
            val code = definition.code.orEmpty()
            if (
                code != requestedCode ||
                definition.declaredType != DpDeclaredType.INTEGER ||
                dataPoint.kind != LocalDataPointKind.INTEGER
            ) {
                return@dataPointLoop null
            }
            val constraints = definition.constraints
            val minimum = constraints.minimum.exactIntOrNull() ?: return@dataPointLoop null
            val maximum = constraints.maximum.exactIntOrNull() ?: return@dataPointLoop null
            val step = constraints.step.exactIntOrNull() ?: return@dataPointLoop null
            val scale = constraints.scale ?: return@dataPointLoop null
            val currentValue = dataPoint.value.toIntOrNull() ?: return@dataPointLoop null
            if (
                scale != 0 ||
                minimum !in 0..MAX_LIGHT_INTEGER_VALUE ||
                maximum !in 1..MAX_LIGHT_INTEGER_VALUE ||
                minimum >= maximum ||
                step !in 1..(maximum - minimum) ||
                currentValue !in minimum..maximum ||
                (currentValue - minimum) % step != 0
            ) {
                return@dataPointLoop null
            }
            LocalLightIntegerControl(
                dataPointId = dataPoint.id,
                code = code,
                minimum = minimum,
                maximum = maximum,
                step = step,
                currentValue = currentValue,
            )
        }
    }

    private fun decodeLightColor(value: String): LocalLightHsv? {
        if (value.length != LIGHT_COLOR_HEX_LENGTH || !LIGHT_COLOR_HEX.matches(value)) return null
        val color = runCatching {
            LocalLightHsv(
                hue = value.substring(0, 4).toInt(16),
                saturation = value.substring(4, 8).toInt(16),
                brightness = value.substring(8, 12).toInt(16),
            )
        }.getOrNull() ?: return null
        return color.takeIf { candidate ->
            candidate.hue in 0..MAX_LIGHT_HUE &&
                candidate.saturation in 0..MAX_LIGHT_COLOR_COMPONENT &&
                candidate.brightness in 0..MAX_LIGHT_COLOR_COMPONENT
        }
    }

    private fun accessKind(
        restriction: DeviceAccessRestriction,
        kind: LocalDeviceProfileKind,
    ): LocalDeviceAccessKind = restriction.toLegacyAccessKind() ?: when (kind) {
        LocalDeviceProfileKind.SWITCH_OR_OUTLET,
        LocalDeviceProfileKind.LIGHT -> LocalDeviceAccessKind.DIRECT_CONTROL
        LocalDeviceProfileKind.COVER,
        LocalDeviceProfileKind.SENSOR,
        LocalDeviceProfileKind.GENERIC -> LocalDeviceAccessKind.STATUS_ONLY
    }

    private fun classify(device: CloudImportedDevice, schema: DpSchema): DeviceClassification =
        deviceClassifier.classify(device.toDeviceIdentity(), schema)

    private fun DeviceClassification.toLegacyFamily(): LegacyFamily =
        when ((family as? DeviceFamilyResolution.Matched)?.definition?.id) {
            BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET -> LegacyFamily(
                LocalDeviceProfileKind.SWITCH_OR_OUTLET
            )
            BuiltinDeviceFamilyIds.LIGHT -> LegacyFamily(LocalDeviceProfileKind.LIGHT)
            BuiltinDeviceFamilyIds.COVER -> LegacyFamily(LocalDeviceProfileKind.COVER)
            BuiltinDeviceFamilyIds.CLIMATE_SENSOR -> LegacyFamily(
                LocalDeviceProfileKind.SENSOR,
                LocalSensorKind.CLIMATE,
            )
            BuiltinDeviceFamilyIds.CONTACT_SENSOR -> LegacyFamily(
                LocalDeviceProfileKind.SENSOR,
                LocalSensorKind.CONTACT,
            )
            BuiltinDeviceFamilyIds.MOTION_SENSOR -> LegacyFamily(
                LocalDeviceProfileKind.SENSOR,
                LocalSensorKind.MOTION,
            )
            BuiltinDeviceFamilyIds.PRESENCE_SENSOR -> LegacyFamily(
                LocalDeviceProfileKind.SENSOR,
                LocalSensorKind.PRESENCE,
            )
            BuiltinDeviceFamilyIds.WATER_LEAK_SENSOR -> LegacyFamily(
                LocalDeviceProfileKind.SENSOR,
                LocalSensorKind.WATER_LEAK,
            )
            BuiltinDeviceFamilyIds.SMOKE_SENSOR -> LegacyFamily(
                LocalDeviceProfileKind.SENSOR,
                LocalSensorKind.SMOKE,
            )
            BuiltinDeviceFamilyIds.GAS_SENSOR -> LegacyFamily(
                LocalDeviceProfileKind.SENSOR,
                LocalSensorKind.GAS,
            )
            else -> LegacyFamily(LocalDeviceProfileKind.GENERIC)
        }

    private fun isSwitchCode(code: String): Boolean =
        code == "switch" ||
            code == "switch_led" ||
            SWITCH_NUMBER_CODE.matches(code)

    private fun switchLabel(code: String, controlCount: Int): String = when {
        code == "switch" || code == "switch_led" -> "Power"
        controlCount == 1 -> "Power"
        else -> code.removePrefix("switch_").toIntOrNull()?.let { "Switch $it" } ?: "Power"
    }

    private data class Candidate(
        val dataPointId: String,
        val code: String,
        val currentValue: Boolean,
    )

    private data class LegacyFamily(
        val kind: LocalDeviceProfileKind,
        val sensorKind: LocalSensorKind? = null,
    )

    private val BOOLEAN_WIRE_VALUES = setOf("true", "false")
    private val SWITCH_NUMBER_CODE = Regex("switch_[1-9][0-9]?")
    private const val MAX_BOOLEAN_CONTROLS = 16
    private const val MAX_LIGHT_ENUM_LENGTH = 40
    private const val MAX_LIGHT_HUE = 360
    private const val MAX_LIGHT_COLOR_COMPONENT = 1_000
    private const val MAX_LIGHT_INTEGER_VALUE = 10_000
    private const val LIGHT_COLOR_HEX_LENGTH = 12
    private val LIGHT_COLOR_HEX = Regex("[0-9a-fA-F]{12}")
}

private fun CloudImportedDevice.toDeviceIdentity(): DeviceIdentity = DeviceIdentity.normalize(
    category = category,
    productId = productId,
    productName = productName,
    model = model,
    isSubDevice = isSubDevice,
)

private fun DeviceAccessRestriction.toLegacyAccessKind(): LocalDeviceAccessKind? = when (this) {
    DeviceAccessRestriction.NONE -> null
    DeviceAccessRestriction.GATEWAY_CHILD -> LocalDeviceAccessKind.GATEWAY_CHILD
    DeviceAccessRestriction.GATEWAY -> LocalDeviceAccessKind.GATEWAY
    DeviceAccessRestriction.CAMERA -> LocalDeviceAccessKind.CAMERA
    DeviceAccessRestriction.LOCK -> LocalDeviceAccessKind.LOCK
}

private fun BigDecimal?.exactIntOrNull(): Int? =
    this
        ?.takeIf { value -> value.scale() == 0 }
        ?.let { value -> runCatching(value::intValueExact).getOrNull() }
