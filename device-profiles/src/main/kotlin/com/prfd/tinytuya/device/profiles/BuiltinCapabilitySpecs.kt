package com.prfd.tinytuya.device.profiles

import com.prfd.tinytuya.device.core.capability.ActionGroupCapabilitySpec
import com.prfd.tinytuya.device.core.capability.BinaryStateCapabilitySpec
import com.prfd.tinytuya.device.core.capability.CapabilityChoice
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.CapabilitySpec
import com.prfd.tinytuya.device.core.capability.CapabilityState
import com.prfd.tinytuya.device.core.capability.CapabilityTone
import com.prfd.tinytuya.device.core.capability.ChoiceCapabilitySpec
import com.prfd.tinytuya.device.core.capability.ColorCapabilitySpec
import com.prfd.tinytuya.device.core.capability.MeasurementCapabilitySpec
import com.prfd.tinytuya.device.core.capability.MeasurementDisplay
import com.prfd.tinytuya.device.core.capability.MeasurementUnitPolicy
import com.prfd.tinytuya.device.core.capability.RangeCapabilitySpec
import com.prfd.tinytuya.device.core.capability.ToggleCapabilitySpec
import com.prfd.tinytuya.device.core.profile.DeviceFamilyId
import com.prfd.tinytuya.device.core.schema.DpDeclaredType
import com.prfd.tinytuya.device.core.schema.DpSchema

internal object BuiltinCapabilitySpecs {
    fun forFamily(familyId: DeviceFamilyId, schema: DpSchema): List<CapabilitySpec> = when (familyId) {
        BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET -> switchSpecs(schema)
        BuiltinDeviceFamilyIds.LIGHT -> lightSpecs()
        BuiltinDeviceFamilyIds.COVER -> coverSpecs()
        BuiltinDeviceFamilyIds.CLIMATE_SENSOR -> climateSpecs()
        BuiltinDeviceFamilyIds.CONTACT_SENSOR -> contactSpecs()
        BuiltinDeviceFamilyIds.MOTION_SENSOR -> motionSpecs()
        BuiltinDeviceFamilyIds.PRESENCE_SENSOR -> presenceSpecs()
        BuiltinDeviceFamilyIds.WATER_LEAK_SENSOR -> waterSpecs()
        BuiltinDeviceFamilyIds.SMOKE_SENSOR -> smokeSpecs()
        BuiltinDeviceFamilyIds.GAS_SENSOR -> gasSpecs()
        else -> emptyList()
    }

    private fun switchSpecs(schema: DpSchema): List<CapabilitySpec> {
        val numberedSwitchCodes = schema.definitions
            .asSequence()
            .filter { definition -> definition.declaredType == DpDeclaredType.BOOLEAN }
            .mapNotNull { definition -> definition.code }
            .filter(SWITCH_NUMBER_CODE::matches)
            .distinct()
            .sortedBy { code -> code.removePrefix("switch_").toIntOrNull() ?: Int.MAX_VALUE }
            .take(MAX_NUMBERED_SWITCHES)
            .toList()
        val hasPrimaryPower = schema.definitions.any { definition ->
            definition.declaredType == DpDeclaredType.BOOLEAN &&
                definition.code in setOf("switch", "switch_led")
        }
        val toggles = buildList {
            if (hasPrimaryPower) {
                add(
                    ToggleCapabilitySpec(
                        id = CapabilityId("power"),
                        label = "Power",
                        codeCandidates = listOf("switch", "switch_led"),
                        writable = true,
                    )
                )
            }
            numberedSwitchCodes.forEach { code ->
                val suffix = code.removePrefix("switch_")
                add(
                    ToggleCapabilitySpec(
                        id = CapabilityId("switch.$suffix"),
                        label = if (!hasPrimaryPower && numberedSwitchCodes.size == 1) {
                            "Power"
                        } else {
                            "Switch $suffix"
                        },
                        codeCandidates = listOf(code),
                        writable = true,
                    )
                )
            }
        }
        return toggles + electricalMeasurements()
    }

    private fun lightSpecs(): List<CapabilitySpec> = listOf(
        ToggleCapabilitySpec(
            id = CapabilityId("power"),
            label = "Power",
            codeCandidates = listOf("switch_led", "switch"),
            writable = true,
        ),
        ChoiceCapabilitySpec(
            id = CapabilityId("light.mode"),
            label = "Mode",
            codeCandidates = listOf("work_mode"),
            writable = true,
            choices = listOf(
                CapabilityChoice("white", "White"),
                CapabilityChoice("colour", "Colour"),
            ),
        ),
        RangeCapabilitySpec(
            id = CapabilityId("light.brightness"),
            label = "Brightness",
            codeCandidates = listOf("bright_value_v2", "bright_value"),
            writable = true,
            display = MeasurementDisplay.PERCENTAGE,
        ),
        RangeCapabilitySpec(
            id = CapabilityId("light.temperature"),
            label = "Color temperature",
            codeCandidates = listOf("temp_value_v2", "temp_value"),
            writable = true,
            display = MeasurementDisplay.PERCENTAGE,
        ),
        ColorCapabilitySpec(
            id = CapabilityId("light.color"),
            label = "Color",
            codeCandidates = listOf("colour_data_v2"),
            writable = true,
        ),
        percentageMeasurement(
            id = "light.brightness.reading",
            label = "Brightness",
            codes = listOf("bright_value_v2", "bright_value"),
        ),
        percentageMeasurement(
            id = "light.temperature.reading",
            label = "Color temperature",
            codes = listOf("temp_value_v2", "temp_value"),
        ),
    ) + electricalMeasurements()

    private fun coverSpecs(): List<CapabilitySpec> = listOf(
        ActionGroupCapabilitySpec(
            id = CapabilityId("cover.actions"),
            label = "Cover",
            codeCandidates = listOf("control_2", "control"),
            writable = false,
            actions = listOf(
                CapabilityChoice("open", "Open"),
                CapabilityChoice("stop", "Stop"),
                CapabilityChoice("close", "Close"),
            ),
        ),
        percentageMeasurement(
            id = "cover.position",
            label = "Position",
            codes = listOf("percent_state_2", "percent_state"),
        ),
    )

    private fun climateSpecs(): List<CapabilitySpec> = listOf(
        measurement(
            "sensor.temperature",
            "Temperature",
            listOf("temp_current", "va_temperature"),
            MeasurementUnitPolicy.TEMPERATURE,
        ),
        measurement(
            "sensor.humidity",
            "Humidity",
            listOf("humidity_value", "va_humidity"),
            MeasurementUnitPolicy.PERCENTAGE,
        ),
        battery(),
    )

    private fun contactSpecs(): List<CapabilitySpec> = listOf(
        state(
            "sensor.contact",
            "Contact",
            listOf("doorcontact_state"),
            CapabilityState("false", "Closed", CapabilityTone.NORMAL),
            CapabilityState("true", "Open", CapabilityTone.ACTIVE),
        ),
        battery(),
        measurement(
            "sensor.signal",
            "Signal",
            listOf("signal_strength"),
            MeasurementUnitPolicy.SIGNAL,
        ),
    )

    private fun motionSpecs(): List<CapabilitySpec> = listOf(
        state(
            "sensor.motion",
            "Motion",
            listOf("pir"),
            CapabilityState("none", "No motion", CapabilityTone.NORMAL),
            CapabilityState("pir", "Motion detected", CapabilityTone.ACTIVE),
        ),
        battery(),
    )

    private fun presenceSpecs(): List<CapabilitySpec> = listOf(
        state(
            "sensor.presence",
            "Presence",
            listOf("presence_state"),
            CapabilityState("none", "Room clear", CapabilityTone.NORMAL),
            CapabilityState("presence", "Presence detected", CapabilityTone.ACTIVE),
            CapabilityState("peaceful", "Still presence", CapabilityTone.ACTIVE),
            CapabilityState("small_move", "Small movement", CapabilityTone.ACTIVE),
            CapabilityState("large_move", "Large movement", CapabilityTone.ACTIVE),
        ),
        measurement(
            "sensor.closest_target",
            "Closest target",
            listOf("target_dis_closest"),
            MeasurementUnitPolicy.DISTANCE,
        ),
        battery(),
    )

    private fun waterSpecs(): List<CapabilitySpec> = listOf(
        state(
            "sensor.water",
            "Water",
            listOf("watersensor_state"),
            CapabilityState("normal", "No leak reported", CapabilityTone.NORMAL),
            CapabilityState("alarm", "Leak detected", CapabilityTone.ALERT),
        ),
        battery(),
    )

    private fun smokeSpecs(): List<CapabilitySpec> = listOf(
        state(
            "sensor.smoke",
            "Smoke",
            listOf("smoke_sensor_status", "smoke_sensor_state"),
            CapabilityState("normal", "No smoke alarm", CapabilityTone.NORMAL),
            CapabilityState("alarm", "Smoke alarm", CapabilityTone.ALERT),
            CapabilityState("2", "No smoke alarm", CapabilityTone.NORMAL),
            CapabilityState("1", "Smoke alarm", CapabilityTone.ALERT),
        ),
        measurement(
            "sensor.smoke_level",
            "Smoke level",
            listOf("smoke_sensor_value"),
            MeasurementUnitPolicy.SAFE_MAPPED,
        ),
        battery(),
    )

    private fun gasSpecs(): List<CapabilitySpec> = listOf(
        state(
            "sensor.gas",
            "Gas",
            listOf("gas_sensor_status", "gas_sensor_state"),
            CapabilityState("normal", "No gas alarm", CapabilityTone.NORMAL),
            CapabilityState("alarm", "Gas alarm", CapabilityTone.ALERT),
            CapabilityState("2", "No gas alarm", CapabilityTone.NORMAL),
            CapabilityState("1", "Gas alarm", CapabilityTone.ALERT),
        ),
        measurement(
            "sensor.gas_level",
            "Gas level",
            listOf("gas_sensor_value"),
            MeasurementUnitPolicy.SAFE_MAPPED,
        ),
        battery(),
    )

    private fun electricalMeasurements(): List<CapabilitySpec> = listOf(
        measurement("electrical.power", "Power draw", listOf("cur_power")),
        measurement("electrical.voltage", "Voltage", listOf("cur_voltage")),
        measurement("electrical.current", "Current", listOf("cur_current")),
        measurement("electrical.energy", "Energy", listOf("add_ele")),
    )

    private fun battery(): CapabilitySpec = measurement(
        "sensor.battery",
        "Battery",
        listOf("battery_percentage"),
        MeasurementUnitPolicy.PERCENTAGE,
    )

    private fun measurement(
        id: String,
        label: String,
        codes: List<String>,
        unitPolicy: MeasurementUnitPolicy = MeasurementUnitPolicy.SAFE_MAPPED,
    ): CapabilitySpec = MeasurementCapabilitySpec(
        id = CapabilityId(id),
        label = label,
        codeCandidates = codes,
        unitPolicy = unitPolicy,
    )

    private fun percentageMeasurement(
        id: String,
        label: String,
        codes: List<String>,
    ): CapabilitySpec = MeasurementCapabilitySpec(
        id = CapabilityId(id),
        label = label,
        codeCandidates = codes,
        display = MeasurementDisplay.PERCENTAGE,
    )

    private fun state(
        id: String,
        label: String,
        codes: List<String>,
        vararg states: CapabilityState,
    ): CapabilitySpec = BinaryStateCapabilitySpec(
        id = CapabilityId(id),
        label = label,
        codeCandidates = codes,
        states = states.toList(),
    )

    private val SWITCH_NUMBER_CODE = Regex("switch_[1-9][0-9]?")
    private const val MAX_NUMBERED_SWITCHES = 16
}
