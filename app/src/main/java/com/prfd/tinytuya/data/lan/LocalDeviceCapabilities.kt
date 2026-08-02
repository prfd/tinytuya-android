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

/**
 * Conservative registry for controls which are safe to expose locally.
 *
 * A writable Boolean switch must be declared by the cached Tuya mapping and
 * independently observed as a Boolean in a current local status response. A
 * numeric DP ID or device category alone is never enough to authorize a write.
 */
object LocalDeviceCapabilityRegistry {
    fun booleanControls(
        device: CloudImportedDevice,
        status: LocalStatusRecord?,
        lastDiscoveryAtEpochMillis: Long?,
    ): List<LocalBooleanControl> {
        if (
            device.isSubDevice ||
            status == null ||
            status.state != LocalPollDeviceState.RESPONDED ||
            lastDiscoveryAtEpochMillis == null ||
            status.polledAtEpochMillis < lastDiscoveryAtEpochMillis
        ) {
            return emptyList()
        }

        val mapping = runCatching { JSONObject(device.mappingJson) }.getOrNull()
            ?: return emptyList()
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

    private val BOOLEAN_WIRE_VALUES = setOf("true", "false")
    private val SWITCH_NUMBER_CODE = Regex("switch_[1-9][0-9]?")
    private const val MAX_BOOLEAN_CONTROLS = 16
}
