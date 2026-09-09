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
  /**
   * Optional per-device attempt-count override (1..3). Absent keeps the bridge default of the full
   * retry ladder; verification probes pass 1 because cross-device redundancy substitutes for
   * per-device retries.
   */
  val maxAttempts: Int? = null,
)

/**
 * One normalized Tuya data point (DP) reported by a device over the local network.
 *
 * A device's `dps` dict arrives from TinyTuya as raw DP id/value pairs and is normalized before
 * crossing the bridge: [id] is the numeric DP id as a string.
 */
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
  val attemptCount: Int = 1,
  val dataPoints: List<LocalDataPoint>,
) {
  override fun toString(): String =
    "LocalPolledDevice(id=[REDACTED], state=$state, errorCode=$errorCode, " +
      "durationMillis=$durationMillis, attemptCount=$attemptCount, " +
      "dataPointCount=${dataPoints.size})"
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
