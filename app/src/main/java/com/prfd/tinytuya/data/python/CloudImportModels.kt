package com.prfd.tinytuya.data.python

/** A secret which is deliberately redacted from logs and generated model strings. */
class SensitiveString private constructor(private val value: String) {
  internal fun reveal(): String = value

  val isBlank: Boolean
    get() = value.isBlank()

  override fun toString(): String = "[REDACTED]"

  companion object {
    fun of(value: String): SensitiveString = SensitiveString(value)
  }
}

enum class TuyaCloudRegion(val apiCode: String) {
  CHINA("cn"),
  WESTERN_AMERICA("us"),
  EASTERN_AMERICA("us-e"),
  CENTRAL_EUROPE("eu"),
  WESTERN_EUROPE("eu-w"),
  INDIA("in"),
  SINGAPORE("sg"),
}

class CloudCredentials(
  val region: TuyaCloudRegion,
  val clientId: String,
  val clientSecret: SensitiveString,
  val sampleDeviceId: String? = null,
) {
  override fun toString(): String =
    "CloudCredentials(region=$region, clientId=[REDACTED], " +
      "clientSecret=[REDACTED], sampleDeviceId=${if (sampleDeviceId.isNullOrBlank()) "absent" else "present"})"
}

data class CloudImportedDevice(
  val id: String,
  val name: String,
  val localKey: SensitiveString,
  val category: String,
  val productId: String,
  val productName: String,
  val model: String,
  val mac: String,
  val uuid: String,
  val isSubDevice: Boolean,
  val gatewayId: String,
  val nodeId: String,
  val protocolVersion: String,
  val lastIp: String,
  val mappingJson: String,
)

data class CloudImportResult(
  val contractVersion: Int,
  val region: TuyaCloudRegion,
  val deviceCount: Int,
  val missingLocalKeyCount: Int,
  val warnings: List<String>,
  val devices: List<CloudImportedDevice>,
)
