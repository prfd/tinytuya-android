package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.data.local.LocalStatusRecord
import com.prfd.tinytuya.data.python.CloudImportedDevice
import org.json.JSONObject

data class LocalBooleanControl(
    val dataPointId: String,
    val code: String,
    val label: String,
    val currentValue: Boolean,
)

enum class LocalDeviceProfileKind {
    SWITCH_OR_OUTLET,
    LIGHT,
    COVER,
    GENERIC,
}

data class LocalDeviceProfile(
    val kind: LocalDeviceProfileKind,
    val mappedSwitchCount: Int,
    val booleanControls: List<LocalBooleanControl>,
)

/**
 * Conservative registry for controls which are safe to expose locally.
 *
 * A writable Boolean switch must be declared by the cached Tuya mapping and
 * independently observed as a Boolean in a current local status response. A
 * numeric DP ID or device category alone is never enough to authorize a write.
 */
object LocalDeviceCapabilityRegistry {
    fun profile(
        device: CloudImportedDevice,
        status: LocalStatusRecord?,
        lastDiscoveryAtEpochMillis: Long?,
    ): LocalDeviceProfile {
        val mapping = parseMapping(device.mappingJson)
        val definitions = mappingDefinitions(mapping)
        val mappedSwitchCount = definitions.count { definition ->
            definition.type.equals("Boolean", ignoreCase = true) &&
                isSwitchCode(definition.code)
        }.coerceAtMost(MAX_BOOLEAN_CONTROLS)
        val codes = definitions.mapTo(mutableSetOf()) { it.code }
        val kind = when {
            device.category.lowercase() in LIGHT_CATEGORIES ||
                codes.any { it in LIGHT_PROFILE_CODES } -> LocalDeviceProfileKind.LIGHT
            device.category.lowercase() in COVER_CATEGORIES ||
                codes.any { it in COVER_PROFILE_CODES } -> LocalDeviceProfileKind.COVER
            device.category.lowercase() in SWITCH_CATEGORIES || mappedSwitchCount > 0 ->
                LocalDeviceProfileKind.SWITCH_OR_OUTLET
            else -> LocalDeviceProfileKind.GENERIC
        }
        return LocalDeviceProfile(
            kind = kind,
            mappedSwitchCount = mappedSwitchCount,
            booleanControls = booleanControls(
                device = device,
                status = status,
                lastDiscoveryAtEpochMillis = lastDiscoveryAtEpochMillis,
                mapping = mapping,
            ),
        )
    }

    fun booleanControls(
        device: CloudImportedDevice,
        status: LocalStatusRecord?,
        lastDiscoveryAtEpochMillis: Long?,
    ): List<LocalBooleanControl> = booleanControls(
        device = device,
        status = status,
        lastDiscoveryAtEpochMillis = lastDiscoveryAtEpochMillis,
        mapping = parseMapping(device.mappingJson),
    )

    private fun booleanControls(
        device: CloudImportedDevice,
        status: LocalStatusRecord?,
        lastDiscoveryAtEpochMillis: Long?,
        mapping: JSONObject?,
    ): List<LocalBooleanControl> {
        if (
            device.isSubDevice ||
            status == null ||
            status.state != LocalPollDeviceState.RESPONDED ||
            lastDiscoveryAtEpochMillis == null ||
            status.polledAtEpochMillis < lastDiscoveryAtEpochMillis ||
            mapping == null
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
            val definition = mapping.optJSONObject(dataPoint.id) ?: return@mapNotNull null
            val code = definition.optString("code").trim().lowercase()
            val type = definition.optString("type").trim()
            if (!type.equals("Boolean", ignoreCase = true) || !isSwitchCode(code)) {
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

    private fun parseMapping(mappingJson: String): JSONObject? =
        runCatching { JSONObject(mappingJson) }.getOrNull()

    private fun mappingDefinitions(mapping: JSONObject?): List<MappingDefinition> {
        if (mapping == null) return emptyList()
        return buildList {
            val keys = mapping.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val definition = mapping.optJSONObject(id) ?: continue
                add(
                    MappingDefinition(
                        code = definition.optString("code").trim().lowercase(),
                        type = definition.optString("type").trim(),
                    )
                )
            }
        }
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

    private data class MappingDefinition(
        val code: String,
        val type: String,
    )

    private val BOOLEAN_WIRE_VALUES = setOf("true", "false")
    private val SWITCH_NUMBER_CODE = Regex("switch_[1-9][0-9]?")
    private val SWITCH_CATEGORIES = setOf("kg", "cz", "pc")
    private val LIGHT_CATEGORIES = setOf("dj")
    private val COVER_CATEGORIES = setOf("cl", "clkg")
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
    private const val MAX_BOOLEAN_CONTROLS = 16
}
