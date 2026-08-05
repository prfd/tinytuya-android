package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.data.python.SensitiveString

enum class LocalControlState(val wireValue: String) {
  CONFIRMED("confirmed"),
  REJECTED("rejected"),
  OFFLINE("offline"),
  ERROR("error"),
}

class LocalControlDevice(
  val id: String,
  val ip: String,
  val localKey: SensitiveString,
  val protocolVersion: String,
) {
  override fun toString(): String =
    "LocalControlDevice(id=[REDACTED], ip=[REDACTED], " +
      "localKey=[REDACTED], protocolVersion=$protocolVersion)"
}

data class LocalControlChange(
  val id: String,
  val kind: LocalDataPointKind,
  val value: String,
)

data class LocalControlRequest(
  val network: LanNetworkContext,
  val device: LocalControlDevice,
  val changes: List<LocalControlChange>,
)

data class LocalControlResult(
  val contractVersion: Int,
  val id: String,
  val state: LocalControlState,
  val errorCode: String,
  val durationMillis: Long,
  val dataPoints: List<LocalDataPoint>,
) {
  override fun toString(): String =
    "LocalControlResult(id=[REDACTED], state=$state, errorCode=$errorCode, " +
      "durationMillis=$durationMillis, dataPointCount=${dataPoints.size})"
}

class LocalControlException(
  val code: String,
  message: String,
  val updatedCatalog: com.prfd.tinytuya.data.local.DeviceCatalog? = null,
) : IllegalStateException(message)
