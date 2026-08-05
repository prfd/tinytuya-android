package com.prfd.tinytuya.device.core.capability

enum class PrimitiveWriteKind {
  BOOLEAN,
  INTEGER,
  STRING,
}

data class PrimitiveCapabilityWrite
internal constructor(
  val dataPointId: String,
  val kind: PrimitiveWriteKind,
  val value: String,
)

class AuthorizedCapabilityCommand internal constructor(writes: List<PrimitiveCapabilityWrite>) {
  val writes: List<PrimitiveCapabilityWrite> = writes.toList()
}

/** Reauthorizes a semantic request against a newly resolved capability set. */
object CapabilityCommandAuthorizer {
  fun authorize(
    capabilities: ResolvedDeviceCapabilities,
    intent: DeviceIntent,
  ): AuthorizedCapabilityCommand? {
    val capability =
      capabilities.capabilities
        .singleOrNull { candidate -> candidate.id == intent.capabilityId }
        ?.takeIf(ResolvedCapability::writable) ?: return null
    val write =
      when {
        capability is ResolvedToggle && intent is DeviceIntent.SetToggle ->
          PrimitiveCapabilityWrite(
            capability.dataPointId,
            PrimitiveWriteKind.BOOLEAN,
            BooleanCapabilityCodec.encode(intent.value),
          )
        capability is ResolvedRange && intent is DeviceIntent.SetRange ->
          IntegerCapabilityCodec.encode(
              intent.value,
              capability.minimum,
              capability.maximum,
              capability.step,
            )
            ?.let { encoded ->
              PrimitiveCapabilityWrite(
                capability.dataPointId,
                PrimitiveWriteKind.INTEGER,
                encoded,
              )
            }
        capability is ResolvedChoice && intent is DeviceIntent.SetChoice ->
          EnumCapabilityCodec.encode(
              intent.wireValue,
              capability.choices.map(CapabilityChoice::wireValue),
            )
            ?.let { encoded ->
              PrimitiveCapabilityWrite(
                capability.dataPointId,
                PrimitiveWriteKind.STRING,
                encoded,
              )
            }
        capability is ResolvedActionGroup && intent is DeviceIntent.InvokeAction ->
          EnumCapabilityCodec.encode(
              intent.wireValue,
              capability.actions.map(CapabilityChoice::wireValue),
            )
            ?.let { encoded ->
              PrimitiveCapabilityWrite(
                capability.dataPointId,
                PrimitiveWriteKind.STRING,
                encoded,
              )
            }
        capability is ResolvedColor && intent is DeviceIntent.SetColor ->
          TuyaHsvV2CapabilityCodec.encode(intent.color)?.let { encoded ->
            PrimitiveCapabilityWrite(
              capability.dataPointId,
              PrimitiveWriteKind.STRING,
              encoded,
            )
          }
        else -> null
      } ?: return null
    return AuthorizedCapabilityCommand(listOf(write))
  }
}
