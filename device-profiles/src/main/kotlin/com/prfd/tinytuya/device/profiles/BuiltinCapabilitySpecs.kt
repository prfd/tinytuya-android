package com.prfd.tinytuya.device.profiles

import com.prfd.tinytuya.device.core.capability.ActionGroupCapabilitySpec
import com.prfd.tinytuya.device.core.capability.CapabilityChoice
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.CapabilitySpec
import com.prfd.tinytuya.device.core.capability.ChoiceCapabilitySpec
import com.prfd.tinytuya.device.core.capability.ColorCapabilitySpec
import com.prfd.tinytuya.device.core.capability.MeasurementCapabilitySpec
import com.prfd.tinytuya.device.core.capability.MeasurementDisplay
import com.prfd.tinytuya.device.core.capability.MeasurementUnitPolicy
import com.prfd.tinytuya.device.core.capability.RangeCapabilitySpec
import com.prfd.tinytuya.device.core.capability.ToggleCapabilitySpec
import com.prfd.tinytuya.device.core.profile.DeviceFamilyId
import com.prfd.tinytuya.device.core.schema.DpDeclaredType
import com.prfd.tinytuya.device.core.schema.DpDefinition
import com.prfd.tinytuya.device.core.schema.DpSchema

internal object BuiltinCapabilitySpecs {
  fun forFamily(familyId: DeviceFamilyId, schema: DpSchema): List<CapabilitySpec> =
    when (familyId) {
      BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET -> switchSpecs(schema)
      BuiltinDeviceFamilyIds.LIGHT -> lightSpecs()
      BuiltinDeviceFamilyIds.COVER -> coverSpecs(schema)
      else -> emptyList()
    }

  private fun switchSpecs(schema: DpSchema): List<CapabilitySpec> {
    val numberedSwitchCodes =
      schema.definitions
        .asSequence()
        .filter { definition -> definition.declaredType == DpDeclaredType.BOOLEAN }
        .mapNotNull { definition -> definition.code }
        .filter(SWITCH_NUMBER_CODE::matches)
        .distinct()
        .sortedBy { code -> code.removePrefix("switch_").toIntOrNull() ?: Int.MAX_VALUE }
        .take(MAX_NUMBERED_SWITCHES)
        .toList()
    val hasPrimaryPower =
      schema.definitions.any { definition ->
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
            label =
              if (!hasPrimaryPower && numberedSwitchCodes.size == 1) {
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

  private fun lightSpecs(): List<CapabilitySpec> =
    listOf(
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
        choices =
          listOf(
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

  private fun coverSpecs(schema: DpSchema): List<CapabilitySpec> = buildList {
    val controlDefinition = schema.selectUniqueDefinition(COVER_CONTROL_CODES)
    controlDefinition?.coverActions()?.let { actions ->
      add(
        ActionGroupCapabilitySpec(
          id = CapabilityId("cover.actions"),
          label = "Cover",
          codeCandidates = listOf(requireNotNull(controlDefinition.code)),
          writable = true,
          actions = actions,
        )
      )
    }
    add(
      RangeCapabilitySpec(
        id = CapabilityId("cover.position"),
        label = "Target position",
        codeCandidates = COVER_POSITION_CONTROL_CODES,
        writable = true,
        display = MeasurementDisplay.PERCENTAGE,
      )
    )
    add(
      percentageMeasurement(
        id = "cover.position.reading",
        label = "Current position",
        codes = COVER_POSITION_STATE_CODES,
      )
    )
  }

  /**
   * TinyTuya 1.20.0's CoverDevice documents these command vocabularies. Unlike its runtime
   * detector, profiles accept one only when the imported Enum schema explicitly declares every
   * open/stop/close value; there is no default vocabulary or DPS ID.
   */
  private fun DpDefinition.coverActions(): List<CapabilityChoice>? {
    if (declaredType != DpDeclaredType.ENUM) return null
    val declared = constraints.enumValues.toSet()
    val vocabulary =
      COVER_ACTION_VOCABULARIES.firstOrNull { candidate ->
        candidate.all { action -> action.wireValue in declared }
      } ?: return null
    return vocabulary
  }

  private fun DpSchema.selectUniqueDefinition(codes: List<String>): DpDefinition? {
    codes.forEach { code ->
      val matches = definitions.filter { definition -> definition.code == code }
      if (matches.size > 1) return null
      if (matches.size == 1) return matches.single()
    }
    return null
  }

  private fun electricalMeasurements(): List<CapabilitySpec> =
    listOf(
      measurement("electrical.power", "Power draw", listOf("cur_power")),
      measurement("electrical.voltage", "Voltage", listOf("cur_voltage")),
      measurement("electrical.current", "Current", listOf("cur_current")),
      measurement("electrical.energy", "Energy", listOf("add_ele")),
    )

  private fun measurement(
    id: String,
    label: String,
    codes: List<String>,
    unitPolicy: MeasurementUnitPolicy = MeasurementUnitPolicy.SAFE_MAPPED,
  ): CapabilitySpec =
    MeasurementCapabilitySpec(
      id = CapabilityId(id),
      label = label,
      codeCandidates = codes,
      unitPolicy = unitPolicy,
    )

  private fun percentageMeasurement(
    id: String,
    label: String,
    codes: List<String>,
  ): CapabilitySpec =
    MeasurementCapabilitySpec(
      id = CapabilityId(id),
      label = label,
      codeCandidates = codes,
      display = MeasurementDisplay.PERCENTAGE,
    )

  private val SWITCH_NUMBER_CODE = Regex("switch_[1-9][0-9]?")
  private const val MAX_NUMBERED_SWITCHES = 16
}

private val COVER_CONTROL_CODES = listOf("control_2", "control")
private val COVER_POSITION_CONTROL_CODES = listOf("percent_control_2", "percent_control")
private val COVER_POSITION_STATE_CODES = listOf("percent_state_2", "percent_state")
private val COVER_ACTION_VOCABULARIES =
  listOf(
    listOf(
      CapabilityChoice("open", "Open"),
      CapabilityChoice("stop", "Stop"),
      CapabilityChoice("close", "Close"),
    ),
    listOf(
      CapabilityChoice("1", "Open"),
      CapabilityChoice("0", "Stop"),
      CapabilityChoice("2", "Close"),
    ),
    listOf(
      CapabilityChoice("01", "Open"),
      CapabilityChoice("00", "Stop"),
      CapabilityChoice("02", "Close"),
    ),
    listOf(
      CapabilityChoice("on", "Open"),
      CapabilityChoice("stop", "Stop"),
      CapabilityChoice("off", "Close"),
    ),
    listOf(
      CapabilityChoice("up", "Open"),
      CapabilityChoice("stop", "Stop"),
      CapabilityChoice("down", "Close"),
    ),
    listOf(
      CapabilityChoice("ZZ", "Open"),
      CapabilityChoice("STOP", "Stop"),
      CapabilityChoice("FZ", "Close"),
    ),
  )
