package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.data.python.SensitiveString

enum class LocalPollDeviceState(val wireValue: String) {
    RESPONDED("responded"),
    OFFLINE("offline"),
    ERROR("error"),
}

enum class LocalDataPointKind(val wireValue: String) {
    BOOLEAN("boolean"),
    INTEGER("integer"),
    DECIMAL("decimal"),
    STRING("string"),
    JSON("json"),
    NULL("null"),
}

class LocalPollDevice(
    val id: String,
    val ip: String,
    val localKey: SensitiveString,
    val protocolVersion: String,
) {
    override fun toString(): String =
        "LocalPollDevice(id=[REDACTED], ip=[REDACTED], " +
            "localKey=[REDACTED], protocolVersion=$protocolVersion)"
}

data class LocalPollRequest(
    val network: LanNetworkContext,
    val devices: List<LocalPollDevice>,
)

data class LocalDataPoint(
    val id: String,
    val kind: LocalDataPointKind,
    val value: String,
)

data class LocalPolledDevice(
    val id: String,
    val state: LocalPollDeviceState,
    val errorCode: String,
    val durationMillis: Long,
    val dataPoints: List<LocalDataPoint>,
) {
    override fun toString(): String =
        "LocalPolledDevice(id=[REDACTED], state=$state, errorCode=$errorCode, " +
            "durationMillis=$durationMillis, dataPointCount=${dataPoints.size})"
}

data class LocalPollResult(
    val contractVersion: Int,
    val deviceCount: Int,
    val respondedDeviceCount: Int,
    val offlineDeviceCount: Int,
    val errorDeviceCount: Int,
    val durationMillis: Long,
    val warnings: List<String>,
    val devices: List<LocalPolledDevice>,
)

class LocalStatusException(
    val code: String,
    message: String,
) : IllegalStateException(message)
