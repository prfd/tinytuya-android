package com.prfd.tinytuya.device.profiles

import com.prfd.tinytuya.device.core.capability.CapabilitySpec
import com.prfd.tinytuya.device.core.profile.DeviceFamilyDefinition
import com.prfd.tinytuya.device.core.profile.DeviceFamilyId
import com.prfd.tinytuya.device.core.profile.DeviceFamilyRegistry
import com.prfd.tinytuya.device.core.profile.DeviceIdentity
import com.prfd.tinytuya.device.core.profile.DeviceLayoutId
import com.prfd.tinytuya.device.core.profile.DeviceMatch
import com.prfd.tinytuya.device.core.profile.DeviceMatchStrength
import com.prfd.tinytuya.device.core.profile.DevicePresentation
import com.prfd.tinytuya.device.core.profile.DeviceSupport
import com.prfd.tinytuya.device.core.profile.DeviceSupportLevel
import com.prfd.tinytuya.device.core.schema.DpDeclaredType
import com.prfd.tinytuya.device.core.schema.DpDefinition
import com.prfd.tinytuya.device.core.schema.DpSchema

object BuiltinDeviceFamilyIds {
    val SWITCH_OR_OUTLET = DeviceFamilyId("switch_or_outlet")
    val LIGHT = DeviceFamilyId("light")
    val COVER = DeviceFamilyId("cover")
    val CLIMATE_SENSOR = DeviceFamilyId("sensor_climate")
    val CONTACT_SENSOR = DeviceFamilyId("sensor_contact")
    val MOTION_SENSOR = DeviceFamilyId("sensor_motion")
    val PRESENCE_SENSOR = DeviceFamilyId("sensor_presence")
    val WATER_LEAK_SENSOR = DeviceFamilyId("sensor_water_leak")
    val SMOKE_SENSOR = DeviceFamilyId("sensor_smoke")
    val GAS_SENSOR = DeviceFamilyId("sensor_gas")
}

object BuiltinDeviceLayoutIds {
    val SWITCH_OR_OUTLET = DeviceLayoutId("switch_or_outlet")
    val LIGHT = DeviceLayoutId("light")
    val COVER = DeviceLayoutId("cover")
    val CLIMATE_SENSOR = DeviceLayoutId("sensor.climate")
    val CONTACT_SENSOR = DeviceLayoutId("sensor.contact")
    val MOTION_SENSOR = DeviceLayoutId("sensor.motion")
    val PRESENCE_SENSOR = DeviceLayoutId("sensor.presence")
    val WATER_LEAK_SENSOR = DeviceLayoutId("sensor.water_leak")
    val SMOKE_SENSOR = DeviceLayoutId("sensor.smoke")
    val GAS_SENSOR = DeviceLayoutId("sensor.gas")
}

object BuiltinDeviceFamilies {
    val definitions: List<DeviceFamilyDefinition> = listOf(
        DeclarativeDeviceFamily(
            id = BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET,
            support = DeviceSupport(
                DeviceSupportLevel.REAL_HARDWARE,
                "Switch and outlet control validated on representative local hardware.",
            ),
            presentation = DevicePresentation(
                BuiltinDeviceLayoutIds.SWITCH_OR_OUTLET,
                typeLabel = "Switch or outlet",
                symbol = "⏻",
            ),
            categories = setOf("kg", "cz", "pc"),
            schemaStrength = DeviceMatchStrength.HEURISTIC_SCHEMA,
            schemaMatcher = { definition ->
                definition.declaredType == DpDeclaredType.BOOLEAN &&
                    definition.code.orEmpty().isSwitchCode()
            },
        ),
        DeclarativeDeviceFamily(
            id = BuiltinDeviceFamilyIds.LIGHT,
            support = DeviceSupport(
                DeviceSupportLevel.REAL_HARDWARE,
                "Power and first-release light controls validated on a category dj bulb.",
            ),
            presentation = DevicePresentation(
                BuiltinDeviceLayoutIds.LIGHT,
                typeLabel = "Smart light",
                symbol = "✦",
            ),
            categories = setOf("dj", "xdd", "fwd", "dc", "dd", "gyd", "fsd", "tyndj"),
            schemaMatcher = { definition -> definition.code in LIGHT_PROFILE_CODES },
        ),
        DeclarativeDeviceFamily(
            id = BuiltinDeviceFamilyIds.COVER,
            support = DeviceSupport(
                DeviceSupportLevel.SYNTHETIC_ONLY,
                "Cover recognition is synthetic-only until representative hardware is tested.",
            ),
            presentation = DevicePresentation(
                BuiltinDeviceLayoutIds.COVER,
                typeLabel = "Curtain or cover",
                symbol = "↕",
            ),
            categories = setOf("cl", "clkg"),
            schemaMatcher = { definition -> definition.code in COVER_PROFILE_CODES },
        ),
        sensorFamily(
            id = BuiltinDeviceFamilyIds.CLIMATE_SENSOR,
            layoutId = BuiltinDeviceLayoutIds.CLIMATE_SENSOR,
            label = "Temperature and humidity sensor",
            symbol = "°",
            categories = setOf("wsdcg"),
            codes = CLIMATE_SENSOR_CODES,
        ),
        sensorFamily(
            id = BuiltinDeviceFamilyIds.CONTACT_SENSOR,
            layoutId = BuiltinDeviceLayoutIds.CONTACT_SENSOR,
            label = "Contact sensor",
            symbol = "▯",
            categories = setOf("mcs"),
            codes = CONTACT_SENSOR_CODES,
        ),
        sensorFamily(
            id = BuiltinDeviceFamilyIds.MOTION_SENSOR,
            layoutId = BuiltinDeviceLayoutIds.MOTION_SENSOR,
            label = "Motion sensor",
            symbol = "⌁",
            categories = setOf("pir"),
            codes = MOTION_SENSOR_CODES,
        ),
        sensorFamily(
            id = BuiltinDeviceFamilyIds.PRESENCE_SENSOR,
            layoutId = BuiltinDeviceLayoutIds.PRESENCE_SENSOR,
            label = "Presence sensor",
            symbol = "◎",
            categories = setOf("hps"),
            codes = PRESENCE_SENSOR_CODES,
        ),
        sensorFamily(
            id = BuiltinDeviceFamilyIds.WATER_LEAK_SENSOR,
            layoutId = BuiltinDeviceLayoutIds.WATER_LEAK_SENSOR,
            label = "Water leak sensor",
            symbol = "≈",
            categories = setOf("sj"),
            codes = WATER_SENSOR_CODES,
        ),
        sensorFamily(
            id = BuiltinDeviceFamilyIds.SMOKE_SENSOR,
            layoutId = BuiltinDeviceLayoutIds.SMOKE_SENSOR,
            label = "Smoke alarm",
            symbol = "≋",
            categories = setOf("ywbj"),
            codes = SMOKE_SENSOR_CODES,
        ),
        sensorFamily(
            id = BuiltinDeviceFamilyIds.GAS_SENSOR,
            layoutId = BuiltinDeviceLayoutIds.GAS_SENSOR,
            label = "Gas alarm",
            symbol = "◇",
            categories = setOf("rqbj"),
            codes = GAS_SENSOR_CODES,
        ),
    )

    val registry = DeviceFamilyRegistry(definitions)
}

private class DeclarativeDeviceFamily(
    override val id: DeviceFamilyId,
    override val support: DeviceSupport,
    override val presentation: DevicePresentation,
    private val categories: Set<String>,
    private val schemaStrength: DeviceMatchStrength = DeviceMatchStrength.DISTINCTIVE_SCHEMA,
    private val schemaMatcher: (DpDefinition) -> Boolean,
) : DeviceFamilyDefinition {
    override fun match(identity: DeviceIdentity, schema: DpSchema): DeviceMatch = when {
        identity.category in categories -> DeviceMatch(DeviceMatchStrength.CATEGORY)
        schema.definitions.any(schemaMatcher) -> DeviceMatch(schemaStrength)
        else -> DeviceMatch.NONE
    }

    override fun capabilitySpecs(
        identity: DeviceIdentity,
        schema: DpSchema,
    ): List<CapabilitySpec> = BuiltinCapabilitySpecs.forFamily(id, schema)
}

private fun sensorFamily(
    id: DeviceFamilyId,
    layoutId: DeviceLayoutId,
    label: String,
    symbol: String,
    categories: Set<String>,
    codes: Set<String>,
): DeviceFamilyDefinition = DeclarativeDeviceFamily(
    id = id,
    support = DeviceSupport(
        DeviceSupportLevel.SYNTHETIC_ONLY,
        "$label presentation has synthetic coverage without a real-hardware compatibility claim.",
    ),
    presentation = DevicePresentation(layoutId, typeLabel = label, symbol = symbol),
    categories = categories,
    schemaMatcher = { definition -> definition.code in codes },
)

private fun String.isSwitchCode(): Boolean =
    this == "switch" || this == "switch_led" || SWITCH_NUMBER_CODE.matches(this)

private val SWITCH_NUMBER_CODE = Regex("switch_[1-9][0-9]?")
private val LIGHT_PROFILE_CODES = setOf(
    "switch_led",
    "bright_value",
    "bright_value_v2",
    "temp_value",
    "temp_value_v2",
    "colour_data",
    "colour_data_v2",
    "work_mode",
)
private val COVER_PROFILE_CODES = setOf(
    "control",
    "control_2",
    "percent_control",
    "percent_control_2",
    "percent_state",
    "percent_state_2",
)
private val CLIMATE_SENSOR_CODES = setOf(
    "temp_current",
    "va_temperature",
    "humidity_value",
    "va_humidity",
)
private val CONTACT_SENSOR_CODES = setOf("doorcontact_state")
private val MOTION_SENSOR_CODES = setOf("pir")
private val PRESENCE_SENSOR_CODES = setOf("presence_state")
private val WATER_SENSOR_CODES = setOf("watersensor_state")
private val SMOKE_SENSOR_CODES = setOf(
    "smoke_sensor_status",
    "smoke_sensor_state",
    "smoke_sensor_value",
)
private val GAS_SENSOR_CODES = setOf(
    "gas_sensor_status",
    "gas_sensor_state",
    "gas_sensor_value",
)
