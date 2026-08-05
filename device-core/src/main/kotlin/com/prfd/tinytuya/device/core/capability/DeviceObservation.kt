package com.prfd.tinytuya.device.core.capability

/** Primitive kinds accepted from a platform-specific local-status adapter. */
enum class ObservedDataPointKind {
  BOOLEAN,
  INTEGER,
  DECIMAL,
  STRING,
  STRUCTURED,
  NULL,
}

class ObservedDataPointInput(
  val id: String,
  val kind: ObservedDataPointKind,
  val value: String,
) {
  override fun toString(): String = "ObservedDataPointInput(id=$id, kind=$kind, value=[REDACTED])"
}

class ObservedDataPoint
internal constructor(
  val id: String,
  val kind: ObservedDataPointKind,
  val value: String,
) {
  override fun toString(): String = "ObservedDataPoint(id=$id, kind=$kind, value=[REDACTED])"
}

/**
 * A bounded, adapter-neutral DPS snapshot.
 *
 * Freshness is supplied by the application, which owns discovery and polling timestamps. Device
 * profiles never receive this object. Duplicate IDs invalidate that ID, and an oversized snapshot
 * is rejected as a whole so input ordering cannot affect authorization.
 */
class DeviceObservation
private constructor(
  dataPoints: List<ObservedDataPoint>,
  val isFresh: Boolean,
  val rejectedAsOversized: Boolean,
) {
  val dataPoints: List<ObservedDataPoint> = dataPoints.toList()
  private val dataPointsById = this.dataPoints.associateBy(ObservedDataPoint::id)

  operator fun get(id: String): ObservedDataPoint? = dataPointsById[id]

  companion object {
    const val MAX_DATA_POINT_COUNT = 256
    const val MAX_VALUE_LENGTH = 4_096
    private const val MAX_DATA_POINT_ID_LENGTH = 8

    private val DATA_POINT_ID = Regex("[0-9]+")
    private val INTEGER = Regex("-?[0-9]+")
    private val DECIMAL = Regex("-?[0-9]+(?:\\.[0-9]+)?")

    fun empty(isFresh: Boolean = false): DeviceObservation =
      DeviceObservation(
        dataPoints = emptyList(),
        isFresh = isFresh,
        rejectedAsOversized = false,
      )

    fun normalize(
      inputs: Iterable<ObservedDataPointInput>,
      isFresh: Boolean,
    ): DeviceObservation {
      val boundedInputs = inputs.take(MAX_DATA_POINT_COUNT + 1)
      if (boundedInputs.size > MAX_DATA_POINT_COUNT) {
        return DeviceObservation(
          dataPoints = emptyList(),
          isFresh = isFresh,
          rejectedAsOversized = true,
        )
      }

      val pointsById = mutableMapOf<String, ObservedDataPoint>()
      val duplicateIds = mutableSetOf<String>()
      boundedInputs.forEach { input ->
        if (!validId(input.id) || input.id in duplicateIds) return@forEach
        if (input.id in pointsById) {
          pointsById.remove(input.id)
          duplicateIds += input.id
          return@forEach
        }
        if (!validValue(input.kind, input.value)) return@forEach
        pointsById[input.id] = ObservedDataPoint(input.id, input.kind, input.value)
      }

      return DeviceObservation(
        dataPoints =
          pointsById.values.sortedWith(
            compareBy<ObservedDataPoint> { point -> point.id.toLongOrNull() ?: Long.MAX_VALUE }
              .thenBy(ObservedDataPoint::id)
          ),
        isFresh = isFresh,
        rejectedAsOversized = false,
      )
    }

    private fun validId(id: String): Boolean =
      id.length in 1..MAX_DATA_POINT_ID_LENGTH &&
        DATA_POINT_ID.matches(id) &&
        id.any { character -> character != '0' }

    private fun validValue(kind: ObservedDataPointKind, value: String): Boolean {
      if (value.length > MAX_VALUE_LENGTH) return false
      return when (kind) {
        ObservedDataPointKind.BOOLEAN -> value == "true" || value == "false"
        ObservedDataPointKind.INTEGER ->
          value.length in 1..MAX_NUMERIC_VALUE_LENGTH && INTEGER.matches(value)
        ObservedDataPointKind.DECIMAL ->
          value.length in 1..MAX_NUMERIC_VALUE_LENGTH && DECIMAL.matches(value)
        ObservedDataPointKind.STRING -> value.none(Char::isISOControl)
        ObservedDataPointKind.STRUCTURED -> true
        ObservedDataPointKind.NULL -> value.isEmpty()
      }
    }

    private const val MAX_NUMERIC_VALUE_LENGTH = 64
  }
}
