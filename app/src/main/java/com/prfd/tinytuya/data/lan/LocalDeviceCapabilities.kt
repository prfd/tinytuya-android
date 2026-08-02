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

data class LocalDeviceProfile(
    val kind: LocalDeviceProfileKind,
    val access: LocalDeviceAccessKind,
    val sensorKind: LocalSensorKind?,
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
        val sensorKind = sensorKind(device, definitions)
        val kind = profileKind(
            device = device,
            definitions = definitions,
            mappedSwitchCount = mappedSwitchCount,
            sensorKind = sensorKind,
        )
        val access = accessKind(device, kind)
        return LocalDeviceProfile(
            kind = kind,
            access = access,
            sensorKind = sensorKind.takeIf { kind == LocalDeviceProfileKind.SENSOR },
            mappedSwitchCount = mappedSwitchCount,
            booleanControls = if (access == LocalDeviceAccessKind.DIRECT_CONTROL) {
                booleanControls(
                    device = device,
                    status = status,
                    lastDiscoveryAtEpochMillis = lastDiscoveryAtEpochMillis,
                    mapping = mapping,
                )
            } else {
                emptyList()
            },
        )
    }

    fun canPollStatus(device: CloudImportedDevice): Boolean =
        restrictedAccessKind(device) == null

    fun booleanControls(
        device: CloudImportedDevice,
        status: LocalStatusRecord?,
        lastDiscoveryAtEpochMillis: Long?,
    ): List<LocalBooleanControl> {
        val mapping = parseMapping(device.mappingJson)
        val definitions = mappingDefinitions(mapping)
        val mappedSwitchCount = definitions.count { definition ->
            definition.type.equals("Boolean", ignoreCase = true) &&
                isSwitchCode(definition.code)
        }.coerceAtMost(MAX_BOOLEAN_CONTROLS)
        val sensorKind = sensorKind(device, definitions)
        val kind = profileKind(
            device = device,
            definitions = definitions,
            mappedSwitchCount = mappedSwitchCount,
            sensorKind = sensorKind,
        )
        if (accessKind(device, kind) != LocalDeviceAccessKind.DIRECT_CONTROL) {
            return emptyList()
        }
        return booleanControls(
            device = device,
            status = status,
            lastDiscoveryAtEpochMillis = lastDiscoveryAtEpochMillis,
            mapping = mapping,
        )
    }

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

    private fun profileKind(
        device: CloudImportedDevice,
        definitions: List<MappingDefinition>,
        mappedSwitchCount: Int,
        sensorKind: LocalSensorKind?,
    ): LocalDeviceProfileKind {
        val category = device.category.trim().lowercase()
        val codes = definitions.mapTo(mutableSetOf()) { it.code }
        return when {
            category in LIGHT_CATEGORIES ->
                LocalDeviceProfileKind.LIGHT
            category in COVER_CATEGORIES ->
                LocalDeviceProfileKind.COVER
            category in SWITCH_CATEGORIES ->
                LocalDeviceProfileKind.SWITCH_OR_OUTLET
            sensorKind != null -> LocalDeviceProfileKind.SENSOR
            codes.any { it in LIGHT_PROFILE_CODES } -> LocalDeviceProfileKind.LIGHT
            codes.any { it in COVER_PROFILE_CODES } -> LocalDeviceProfileKind.COVER
            mappedSwitchCount > 0 -> LocalDeviceProfileKind.SWITCH_OR_OUTLET
            else -> LocalDeviceProfileKind.GENERIC
        }
    }

    private fun sensorKind(
        device: CloudImportedDevice,
        definitions: List<MappingDefinition>,
    ): LocalSensorKind? {
        when (device.category.trim().lowercase()) {
            "wsdcg" -> return LocalSensorKind.CLIMATE
            "mcs" -> return LocalSensorKind.CONTACT
            "pir" -> return LocalSensorKind.MOTION
            "hps" -> return LocalSensorKind.PRESENCE
            "sj" -> return LocalSensorKind.WATER_LEAK
            "ywbj" -> return LocalSensorKind.SMOKE
            "rqbj" -> return LocalSensorKind.GAS
        }
        val codes = definitions.mapTo(mutableSetOf()) { definition -> definition.code }
        return when {
            codes.any { code -> code in WATER_SENSOR_CODES } -> LocalSensorKind.WATER_LEAK
            codes.any { code -> code in SMOKE_SENSOR_CODES } -> LocalSensorKind.SMOKE
            codes.any { code -> code in GAS_SENSOR_CODES } -> LocalSensorKind.GAS
            codes.any { code -> code in CONTACT_SENSOR_CODES } -> LocalSensorKind.CONTACT
            codes.any { code -> code in PRESENCE_SENSOR_CODES } -> LocalSensorKind.PRESENCE
            codes.any { code -> code in MOTION_SENSOR_CODES } -> LocalSensorKind.MOTION
            codes.any { code -> code in CLIMATE_SENSOR_CODES } -> LocalSensorKind.CLIMATE
            else -> null
        }
    }

    private fun accessKind(
        device: CloudImportedDevice,
        kind: LocalDeviceProfileKind,
    ): LocalDeviceAccessKind = restrictedAccessKind(device) ?: when (kind) {
        LocalDeviceProfileKind.SWITCH_OR_OUTLET,
        LocalDeviceProfileKind.LIGHT -> LocalDeviceAccessKind.DIRECT_CONTROL
        LocalDeviceProfileKind.COVER,
        LocalDeviceProfileKind.SENSOR,
        LocalDeviceProfileKind.GENERIC -> LocalDeviceAccessKind.STATUS_ONLY
    }

    private fun restrictedAccessKind(device: CloudImportedDevice): LocalDeviceAccessKind? {
        val category = device.category.trim().lowercase()
        return when {
            device.isSubDevice -> LocalDeviceAccessKind.GATEWAY_CHILD
            category in GATEWAY_CATEGORIES -> LocalDeviceAccessKind.GATEWAY
            category in CAMERA_CATEGORIES -> LocalDeviceAccessKind.CAMERA
            category in LOCK_CATEGORIES -> LocalDeviceAccessKind.LOCK
            else -> null
        }
    }

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
    private val LIGHT_CATEGORIES = setOf(
        "dj",
        "xdd",
        "fwd",
        "dc",
        "dd",
        "gyd",
        "fsd",
        "tyndj",
    )
    private val COVER_CATEGORIES = setOf("cl", "clkg")
    private val GATEWAY_CATEGORIES = setOf("wg2", "wfcon")
    private val CAMERA_CATEGORIES = setOf("sp")
    private val LOCK_CATEGORIES = setOf(
        "ms",
        "bxx",
        "gyms",
        "jtmspro",
        "hotelms",
        "ms_category",
        "jtmsbh",
        "mk",
        "videolock",
        "photolock",
    )
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
    private const val MAX_BOOLEAN_CONTROLS = 16
}
