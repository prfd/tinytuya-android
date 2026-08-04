package com.prfd.tinytuya.ui.inventory

import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDeviceCapabilityRegistry
import com.prfd.tinytuya.data.lan.LocalSensorKind
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.device.core.capability.CapabilityTone
import com.prfd.tinytuya.device.core.capability.ResolvedBinaryState
import com.prfd.tinytuya.device.core.capability.ResolvedCapability
import com.prfd.tinytuya.device.core.capability.ResolvedDeviceCapabilities
import com.prfd.tinytuya.device.core.capability.ResolvedMeasurement
import com.prfd.tinytuya.device.profiles.BuiltinDeviceFamilyIds

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

/** Compatibility entry point used by fixtures; production passes its already-resolved profile. */
internal fun presentLocalSensor(
    device: CloudImportedDevice,
    sensorKind: LocalSensorKind,
    dataPoints: List<LocalDataPoint>,
): LocalSensorPresentation? = presentLocalSensor(
    sensorKind = sensorKind,
    capabilities = LocalDeviceCapabilityRegistry.resolveFreshReadOnly(
        device,
        dataPoints,
        requestedFamilyId = sensorKind.familyId,
    ),
)

/** Bridges UI-independent resolved primitives into the deliberately small sensor card model. */
internal fun presentLocalSensor(
    sensorKind: LocalSensorKind,
    capabilities: ResolvedDeviceCapabilities,
): LocalSensorPresentation? {
    val orderedIds = SENSOR_CAPABILITY_ORDER.getValue(sensorKind)
    val byId = capabilities.capabilities.associateBy { capability -> capability.id.value }
    val readings = orderedIds.mapNotNull { id -> byId[id]?.toSensorReading() }
    val primary = readings.firstOrNull() ?: return null
    return LocalSensorPresentation(
        primary = primary.reading,
        secondary = readings.drop(1).take(MAX_SECONDARY_SENSOR_READINGS).map { it.reading },
        tone = primary.tone,
    )
}

private fun ResolvedCapability.toSensorReading(): SensorReadingCandidate? = when (this) {
    is ResolvedMeasurement -> SensorReadingCandidate(
        reading = PresentedSensorReading(dataPointId, label, displayValue),
        tone = LocalSensorTone.NEUTRAL,
    )
    is ResolvedBinaryState -> SensorReadingCandidate(
        reading = PresentedSensorReading(dataPointId, label, value),
        tone = tone.toLocalTone(),
    )
    else -> null
}

private fun CapabilityTone.toLocalTone(): LocalSensorTone = when (this) {
    CapabilityTone.NEUTRAL -> LocalSensorTone.NEUTRAL
    CapabilityTone.NORMAL -> LocalSensorTone.NORMAL
    CapabilityTone.ACTIVE -> LocalSensorTone.ACTIVE
    CapabilityTone.ALERT -> LocalSensorTone.ALERT
}

private data class SensorReadingCandidate(
    val reading: PresentedSensorReading,
    val tone: LocalSensorTone,
)

private val SENSOR_CAPABILITY_ORDER = mapOf(
    LocalSensorKind.CLIMATE to listOf(
        "sensor.temperature",
        "sensor.humidity",
        "sensor.battery",
    ),
    LocalSensorKind.CONTACT to listOf(
        "sensor.contact",
        "sensor.battery",
        "sensor.signal",
    ),
    LocalSensorKind.MOTION to listOf("sensor.motion", "sensor.battery"),
    LocalSensorKind.PRESENCE to listOf(
        "sensor.presence",
        "sensor.closest_target",
        "sensor.battery",
    ),
    LocalSensorKind.WATER_LEAK to listOf("sensor.water", "sensor.battery"),
    LocalSensorKind.SMOKE to listOf(
        "sensor.smoke",
        "sensor.smoke_level",
        "sensor.battery",
    ),
    LocalSensorKind.GAS to listOf(
        "sensor.gas",
        "sensor.gas_level",
        "sensor.battery",
    ),
)

private val LocalSensorKind.familyId
    get() = when (this) {
        LocalSensorKind.CLIMATE -> BuiltinDeviceFamilyIds.CLIMATE_SENSOR
        LocalSensorKind.CONTACT -> BuiltinDeviceFamilyIds.CONTACT_SENSOR
        LocalSensorKind.MOTION -> BuiltinDeviceFamilyIds.MOTION_SENSOR
        LocalSensorKind.PRESENCE -> BuiltinDeviceFamilyIds.PRESENCE_SENSOR
        LocalSensorKind.WATER_LEAK -> BuiltinDeviceFamilyIds.WATER_LEAK_SENSOR
        LocalSensorKind.SMOKE -> BuiltinDeviceFamilyIds.SMOKE_SENSOR
        LocalSensorKind.GAS -> BuiltinDeviceFamilyIds.GAS_SENSOR
    }

private const val MAX_SECONDARY_SENSOR_READINGS = 2
