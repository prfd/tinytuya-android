package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.data.local.LocalStatusRecord
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.device.core.capability.CapabilityAccess
import com.prfd.tinytuya.device.core.capability.CapabilityResolver
import com.prfd.tinytuya.device.core.capability.CapabilitySpec
import com.prfd.tinytuya.device.core.capability.DeviceObservation
import com.prfd.tinytuya.device.core.capability.ObservedDataPointInput
import com.prfd.tinytuya.device.core.capability.ObservedDataPointKind
import com.prfd.tinytuya.device.core.capability.ResolvedDevice
import com.prfd.tinytuya.device.core.profile.DeviceAccessRestriction
import com.prfd.tinytuya.device.core.profile.DeviceClassification
import com.prfd.tinytuya.device.core.profile.DeviceClassifier
import com.prfd.tinytuya.device.core.profile.DeviceFamilyId
import com.prfd.tinytuya.device.core.profile.DeviceIdentity
import com.prfd.tinytuya.device.core.profile.DevicePresentation
import com.prfd.tinytuya.device.core.profile.ProtectedDevicePolicy
import com.prfd.tinytuya.device.core.profile.StandardDeviceLayoutIds
import com.prfd.tinytuya.device.core.schema.DpDeclaredType
import com.prfd.tinytuya.device.core.schema.DpSchema
import com.prfd.tinytuya.device.profiles.BuiltinDeviceFamilies

/** App adapter over the final profile, policy, and resolved-capability model. */
data class LocalDeviceProfile(
    val familyId: DeviceFamilyId?,
    val presentation: DevicePresentation,
    val restriction: DeviceAccessRestriction,
    val capabilityAccess: CapabilityAccess,
    val mappedSwitchCount: Int,
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
        val classification = classify(device)
        val definition = classification.family
        val specs = definition?.let { matched ->
            runCatching {
                matched.capabilitySpecs(schema)
            }.getOrDefault(emptyList())
        }.orEmpty()
        val capabilityAccess = capabilityAccess(classification.restriction, specs)
        val capabilities = CapabilityResolver.resolve(
            specs = specs,
            schema = schema,
            observation = status.toObservation(lastDiscoveryAtEpochMillis),
            access = capabilityAccess,
        )
        val presentation = definition?.presentation ?: GENERIC_PRESENTATION

        return LocalDeviceProfile(
            familyId = definition?.id,
            presentation = presentation,
            restriction = classification.restriction,
            capabilityAccess = capabilityAccess,
            mappedSwitchCount = schema.definitions.count { definition ->
                definition.declaredType == DpDeclaredType.BOOLEAN &&
                    definition.code.orEmpty().isSwitchCode()
            }.coerceAtMost(MAX_BOOLEAN_CONTROLS),
            resolvedDevice = ResolvedDevice.create(device.id, presentation.layoutId, capabilities),
        )
    }

    fun canPollStatus(device: CloudImportedDevice): Boolean =
        ProtectedDevicePolicy.restrictionFor(device.toDeviceIdentity()) ==
            DeviceAccessRestriction.NONE

    private fun classify(device: CloudImportedDevice): DeviceClassification =
        deviceClassifier.classify(device.toDeviceIdentity())

    private fun capabilityAccess(
        restriction: DeviceAccessRestriction,
        specs: List<CapabilitySpec>,
    ): CapabilityAccess = when {
        restriction != DeviceAccessRestriction.NONE -> CapabilityAccess.DENIED
        specs.any(CapabilitySpec::writable) -> CapabilityAccess.READ_WRITE
        else -> CapabilityAccess.READ_ONLY
    }

    private const val MAX_BOOLEAN_CONTROLS = 16

    private val GENERIC_PRESENTATION = DevicePresentation(
        layoutId = StandardDeviceLayoutIds.GENERIC_CONTROLS,
        typeLabel = "Tuya device",
        symbol = "••",
    )
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
    isSubDevice = isSubDevice,
)

private fun String.isSwitchCode(): Boolean =
    this == "switch" || this == "switch_led" || SWITCH_NUMBER_CODE.matches(this)

private val SWITCH_NUMBER_CODE = Regex("switch_[1-9][0-9]?")
