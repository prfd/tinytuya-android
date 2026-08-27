package com.prfd.tinytuya.device.core.capability

/**
 * The primitive value kinds a platform-specific local-status adapter may declare for one observed
 * DP value.
 */
enum class ObservedDataPointKind {
  BOOLEAN,
  INTEGER,
  DECIMAL,
  STRING,
  /** An opaque structured payload (for example JSON) passed through without structural parsing. */
  STRUCTURED,
  /** An explicitly absent value whose normalized string representation is empty. */
  NULL,
}

/**
 * Unvalidated raw input for one observed DP value, supplied by a platform-specific adapter.
 *
 * [DeviceObservation.normalize] is the only consumer and performs all ID, value, and size checks;
 * constructing this class validates nothing. [id] is the DP ID as a string, [kind] is the
 * adapter-declared primitive kind, and [value] is the raw text that must match that kind.
 */
class ObservedDataPointInput(
  val id: String,
  val kind: ObservedDataPointKind,
  val value: String,
) {
  override fun toString(): String = "ObservedDataPointInput(id=$id, kind=$kind, value=[REDACTED])"
}

/**
 * A validated, immutable observed DP value inside a [DeviceObservation].
 *
 * Instances are created only by [DeviceObservation.normalize].
 */
class ObservedDataPoint
internal constructor(
  val id: String,
  val kind: ObservedDataPointKind,
  val value: String,
) {
  override fun toString(): String = "ObservedDataPoint(id=$id, kind=$kind, value=[REDACTED])"
}

/**
 * An immutable, bounded, adapter-neutral snapshot of one device's local observed DP values.
 *
 * [normalize] is the fail-closed boundary between platform-specific status adapters and the pure
 * capability core. Freshness is not computed here: the application supplies [isFresh] from its own
 * discovery and polling timestamps, and [CapabilityResolver] refuses stale observations. Device
 * profiles never receive this object.
 *
 * A snapshot keeps at most [MAX_DATA_POINT_COUNT] points. More input rejects the whole snapshot via
 * [rejectedAsOversized], so a malformed payload cannot decide which subset becomes writable.
 * Duplicate IDs invalidate that ID, while individually malformed values are dropped; valid points
 * remain usable.
 */
class DeviceObservation
private constructor(
  dataPoints: List<ObservedDataPoint>,
  val isFresh: Boolean,
  val rejectedAsOversized: Boolean,
) {
  /** The accepted data points, sorted by numeric DP ID. */
  val dataPoints: List<ObservedDataPoint> = dataPoints.toList()

  private val dataPointsById = this.dataPoints.associateBy(ObservedDataPoint::id)

  /** Returns the validated point for [id], or `null` when it is absent, invalid, or duplicated. */
  operator fun get(id: String): ObservedDataPoint? = dataPointsById[id]

  companion object {
    /** Maximum number of data points accepted from a single adapter snapshot. */
    const val MAX_DATA_POINT_COUNT = 256

    /** Maximum raw string length accepted for any observed value. */
    const val MAX_VALUE_LENGTH = 4_096

    private const val MAX_DATA_POINT_ID_LENGTH = 8

    private val DATA_POINT_ID = Regex("[0-9]+")
    private val INTEGER = Regex("-?[0-9]+")
    private val DECIMAL = Regex("-?[0-9]+(?:\\.[0-9]+)?")

    /**
     * Returns an empty snapshot with no data points and the given freshness flag.
     *
     * The default [isFresh] is `false`, matching the common case of "no usable observation".
     */
    fun empty(isFresh: Boolean = false): DeviceObservation =
      DeviceObservation(
        dataPoints = emptyList(),
        isFresh = isFresh,
        rejectedAsOversized = false,
      )

    /** Validates raw adapter inputs into an immutable, sorted [DeviceObservation]. */
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
