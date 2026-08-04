package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.data.local.LocalStatusRecord
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.device.core.capability.CapabilityAccess
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.CapabilityResolver
import com.prfd.tinytuya.device.core.capability.DeviceObservation
import com.prfd.tinytuya.device.core.capability.ObservedDataPointInput
import com.prfd.tinytuya.device.core.capability.ObservedDataPointKind
import com.prfd.tinytuya.device.core.capability.ResolvedChoice
import com.prfd.tinytuya.device.core.capability.ResolvedColor
import com.prfd.tinytuya.device.core.capability.ResolvedDevice
import com.prfd.tinytuya.device.core.capability.ResolvedDeviceCapabilities
import com.prfd.tinytuya.device.core.capability.ResolvedRange
import com.prfd.tinytuya.device.core.capability.ResolvedToggle
import com.prfd.tinytuya.device.core.profile.DeviceAccessRestriction
import com.prfd.tinytuya.device.core.profile.DeviceClassification
import com.prfd.tinytuya.device.core.profile.DeviceClassifier
import com.prfd.tinytuya.device.core.profile.DeviceFamilyResolution
import com.prfd.tinytuya.device.core.profile.DeviceFamilyId
import com.prfd.tinytuya.device.core.profile.DeviceIdentity
import com.prfd.tinytuya.device.core.profile.ProtectedDevicePolicy
import com.prfd.tinytuya.device.core.schema.DpDeclaredType
import com.prfd.tinytuya.device.core.schema.DpSchema
import com.prfd.tinytuya.device.profiles.BuiltinDeviceFamilies
import com.prfd.tinytuya.device.profiles.BuiltinDeviceFamilyIds

data class LocalBooleanControl(
    val capabilityId: CapabilityId,
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
    val capabilityId: CapabilityId,
    val dataPointId: String,
    val currentMode: LocalLightMode?,
)

data class LocalLightIntegerControl(
    val capabilityId: CapabilityId,
    val dataPointId: String,
    val code: String,
    val minimum: Int,
    val maximum: Int,
    val step: Int,
    val currentValue: Int,
)

data class LocalLightColorControl(
    val capabilityId: CapabilityId,
    val dataPointId: String,
    val currentColor: LocalLightHsv,
)

data class LocalLightControls(
    val mode: LocalLightModeControl?,
    val whiteBrightness: LocalLightIntegerControl?,
    val colorTemperature: LocalLightIntegerControl?,
    val color: LocalLightColorControl?,
)

enum class LocalDeviceProfileKind {
    SWITCH_OR_OUTLET,
    LIGHT,
    COVER,
    SENSOR,
    GENERIC,
}

enum class LocalSensorKind {
    CLIMATE,
    CONTACT,
    MOTION,
    PRESENCE,
    WATER_LEAK,
    SMOKE,
    GAS,
}

enum class LocalDeviceAccessKind {
    DIRECT_CONTROL,
    STATUS_ONLY,
    GATEWAY_CHILD,
    GATEWAY,
    CAMERA,
    LOCK,
}

/**
 * Compatibility view consumed by the current inventory UI and command coordinator.
 *
 * [capabilities] is canonical. The Boolean/light fields are temporary presentation adapters; Phase
 * 4 command authorization consumes only semantic intents and freshly resolved capabilities.
 */
data class LocalDeviceProfile(
    val kind: LocalDeviceProfileKind,
    val access: LocalDeviceAccessKind,
    val sensorKind: LocalSensorKind?,
    val mappedSwitchCount: Int,
    val booleanControls: List<LocalBooleanControl>,
    val lightControls: LocalLightControls?,
    val capabilities: ResolvedDeviceCapabilities,
    val resolvedDevice: ResolvedDevice,
)

object LocalDeviceCapabilityRegistry {
    private val deviceClassifier = DeviceClassifier(BuiltinDeviceFamilies.registry)

    fun profile(
        device: CloudImportedDevice,
        status: LocalStatusRecord?,
        lastDiscoveryAtEpochMillis: Long?,
    ): LocalDeviceProfile {
        val schema = parseTuyaDpSchema(device.mappingJson)
        val classification = classify(device, schema)
        val legacyFamily = classification.toLegacyFamily()
        val access = accessKind(classification.restriction, legacyFamily.kind)
        val capabilities = resolveCapabilities(
            device = device,
            schema = schema,
            classification = classification,
            observation = status.toObservation(lastDiscoveryAtEpochMillis),
            access = access.toCapabilityAccess(),
        )
        val layoutId = (classification.family as? DeviceFamilyResolution.Matched)
            ?.definition
            ?.presentation
            ?.layoutId
        val booleanControls = capabilities.ofType<ResolvedToggle>()
            .filter(ResolvedToggle::writable)
            .map { capability ->
                LocalBooleanControl(
                    capabilityId = capability.id,
                    dataPointId = capability.dataPointId,
                    code = capability.code,
                    label = capability.label,
                    currentValue = capability.currentValue,
                )
            }
        val lightControls = if (
            legacyFamily.kind == LocalDeviceProfileKind.LIGHT &&
            access == LocalDeviceAccessKind.DIRECT_CONTROL
        ) {
            capabilities.toLegacyLightControls()
        } else {
            null
        }

        return LocalDeviceProfile(
            kind = legacyFamily.kind,
            access = access,
            sensorKind = legacyFamily.sensorKind,
            mappedSwitchCount = schema.definitions.count { definition ->
                definition.declaredType == DpDeclaredType.BOOLEAN &&
                    definition.code.orEmpty().isSwitchCode()
            }.coerceAtMost(MAX_BOOLEAN_CONTROLS),
            booleanControls = booleanControls,
            lightControls = lightControls,
            capabilities = capabilities,
            resolvedDevice = ResolvedDevice.create(device.id, layoutId, capabilities),
        )
    }

    fun canPollStatus(device: CloudImportedDevice): Boolean =
        ProtectedDevicePolicy.restrictionFor(device.toDeviceIdentity()) ==
            DeviceAccessRestriction.NONE

    fun booleanControls(
        device: CloudImportedDevice,
        status: LocalStatusRecord?,
        lastDiscoveryAtEpochMillis: Long?,
    ): List<LocalBooleanControl> = profile(
        device = device,
        status = status,
        lastDiscoveryAtEpochMillis = lastDiscoveryAtEpochMillis,
    ).booleanControls

    internal fun resolveFreshReadOnly(
        device: CloudImportedDevice,
        dataPoints: List<LocalDataPoint>,
        requestedFamilyId: DeviceFamilyId? = null,
    ): ResolvedDeviceCapabilities {
        val schema = parseTuyaDpSchema(device.mappingJson)
        val classification = classify(device, schema)
        val restriction = classification.restriction
        val definition = requestedFamilyId?.let { familyId ->
            BuiltinDeviceFamilies.definitions.singleOrNull { candidate -> candidate.id == familyId }
        }
        if (definition != null) {
            val specs = runCatching {
                definition.capabilitySpecs(device.toDeviceIdentity(), schema)
            }.getOrDefault(emptyList())
            return CapabilityResolver.resolve(
                specs = specs,
                schema = schema,
                observation = dataPoints.toObservation(isFresh = true),
                access = if (restriction == DeviceAccessRestriction.NONE) {
                    CapabilityAccess.READ_ONLY
                } else {
                    CapabilityAccess.DENIED
                },
            )
        }
        return resolveCapabilities(
            device = device,
            schema = schema,
            classification = classification,
            observation = dataPoints.toObservation(isFresh = true),
            access = if (restriction == DeviceAccessRestriction.NONE) {
                CapabilityAccess.READ_ONLY
            } else {
                CapabilityAccess.DENIED
            },
        )
    }

    private fun resolveCapabilities(
        device: CloudImportedDevice,
        schema: DpSchema,
        classification: DeviceClassification,
        observation: DeviceObservation,
        access: CapabilityAccess,
    ): ResolvedDeviceCapabilities {
        val definition = (classification.family as? DeviceFamilyResolution.Matched)
            ?.definition
            ?: return ResolvedDeviceCapabilities.EMPTY
        val specs = runCatching {
            definition.capabilitySpecs(device.toDeviceIdentity(), schema)
        }.getOrDefault(emptyList())
        return CapabilityResolver.resolve(specs, schema, observation, access)
    }

    private fun ResolvedDeviceCapabilities.toLegacyLightControls(): LocalLightControls? {
        val mode = ofType<ResolvedChoice>()
            .firstOrNull { capability -> capability.id.value == "light.mode" && capability.writable }
            ?.let { capability ->
                LocalLightModeControl(
                    capabilityId = capability.id,
                    dataPointId = capability.dataPointId,
                    currentMode = LocalLightMode.entries.firstOrNull { mode ->
                        mode.wireValue == capability.currentWireValue
                    },
                )
            }
        fun range(id: String): LocalLightIntegerControl? = ofType<ResolvedRange>()
            .firstOrNull { capability -> capability.id.value == id && capability.writable }
            ?.let { capability ->
                LocalLightIntegerControl(
                    capabilityId = capability.id,
                    dataPointId = capability.dataPointId,
                    code = capability.code,
                    minimum = capability.minimum,
                    maximum = capability.maximum,
                    step = capability.step,
                    currentValue = capability.currentValue,
                )
            }
        val color = ofType<ResolvedColor>()
            .firstOrNull { capability -> capability.id.value == "light.color" && capability.writable }
            ?.let { capability ->
                LocalLightColorControl(
                    capabilityId = capability.id,
                    dataPointId = capability.dataPointId,
                    currentColor = LocalLightHsv(
                        hue = capability.currentColor.hue,
                        saturation = capability.currentColor.saturation,
                        brightness = capability.currentColor.brightness,
                    ),
                )
            }
        return LocalLightControls(
            mode = mode,
            whiteBrightness = range("light.brightness"),
            colorTemperature = range("light.temperature"),
            color = color,
        ).takeIf { controls ->
            controls.mode != null ||
                controls.whiteBrightness != null ||
                controls.colorTemperature != null ||
                controls.color != null
        }
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

    private fun accessKind(
        restriction: DeviceAccessRestriction,
        kind: LocalDeviceProfileKind,
    ): LocalDeviceAccessKind = restriction.toLegacyAccessKind() ?: when (kind) {
        LocalDeviceProfileKind.SWITCH_OR_OUTLET,
        LocalDeviceProfileKind.LIGHT,
        LocalDeviceProfileKind.COVER -> LocalDeviceAccessKind.DIRECT_CONTROL
        LocalDeviceProfileKind.SENSOR,
        LocalDeviceProfileKind.GENERIC -> LocalDeviceAccessKind.STATUS_ONLY
    }

    private data class LegacyFamily(
        val kind: LocalDeviceProfileKind,
        val sensorKind: LocalSensorKind? = null,
    )

    private const val MAX_BOOLEAN_CONTROLS = 16
}

private fun LocalStatusRecord?.toObservation(
    lastDiscoveryAtEpochMillis: Long?,
): DeviceObservation {
    val fresh = this != null &&
        state == LocalPollDeviceState.RESPONDED &&
        lastDiscoveryAtEpochMillis != null &&
        polledAtEpochMillis >= lastDiscoveryAtEpochMillis
    return this?.dataPoints.orEmpty().toObservation(isFresh = fresh)
}

private fun List<LocalDataPoint>.toObservation(isFresh: Boolean): DeviceObservation =
    DeviceObservation.normalize(
        map { dataPoint ->
            ObservedDataPointInput(
                id = dataPoint.id,
                kind = dataPoint.kind.toObservedKind(),
                value = if (dataPoint.kind == LocalDataPointKind.NULL) "" else dataPoint.value,
            )
        },
        isFresh = isFresh,
    )

private fun LocalDataPointKind.toObservedKind(): ObservedDataPointKind = when (this) {
    LocalDataPointKind.BOOLEAN -> ObservedDataPointKind.BOOLEAN
    LocalDataPointKind.INTEGER -> ObservedDataPointKind.INTEGER
    LocalDataPointKind.DECIMAL -> ObservedDataPointKind.DECIMAL
    LocalDataPointKind.STRING -> ObservedDataPointKind.STRING
    LocalDataPointKind.JSON -> ObservedDataPointKind.STRUCTURED
    LocalDataPointKind.NULL -> ObservedDataPointKind.NULL
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

private fun LocalDeviceAccessKind.toCapabilityAccess(): CapabilityAccess = when (this) {
    LocalDeviceAccessKind.DIRECT_CONTROL -> CapabilityAccess.READ_WRITE
    LocalDeviceAccessKind.STATUS_ONLY -> CapabilityAccess.READ_ONLY
    LocalDeviceAccessKind.GATEWAY_CHILD,
    LocalDeviceAccessKind.GATEWAY,
    LocalDeviceAccessKind.CAMERA,
    LocalDeviceAccessKind.LOCK -> CapabilityAccess.DENIED
}

private fun String.isSwitchCode(): Boolean =
    this == "switch" || this == "switch_led" || SWITCH_NUMBER_CODE.matches(this)

private val SWITCH_NUMBER_CODE = Regex("switch_[1-9][0-9]?")
