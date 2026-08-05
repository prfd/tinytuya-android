package com.prfd.tinytuya.data.lan

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

internal enum class LocalRefreshTrigger(val logValue: String) {
  MANUAL("manual"),
  FOREGROUND("foreground"),
  FOREGROUND_FALLBACK("foreground_fallback"),
}

internal enum class LocalRefreshMode(val logValue: String) {
  STATUS("status"),
  DISCOVERY("discovery"),
}

internal enum class DiagnosticBridgeOperation(val logValue: String) {
  CLOUD_IMPORT("cloud_import"),
  DISCOVERY("discovery"),
  STATUS("status"),
  CONTROL("control"),
}

/**
 * Redacted timing diagnostics for user-initiated and foreground local refreshes.
 *
 * Keep this API deliberately narrow. Device IDs, addresses, keys, network handles, data-point
 * values, raw bridge responses, and exception messages must never reach Logcat.
 */
internal object LocalRefreshDiagnostics {
  const val TAG = "TinyTuyaRefresh"

  private val nextOperationId = AtomicLong(1L)
  private val stableCodePattern = Regex("[A-Z][A-Z0-9_]{0,63}")

  fun nextBridgeOperationId(): Long = nextOperationId.getAndIncrement()

  fun refreshStarted(
    trigger: LocalRefreshTrigger,
    mode: LocalRefreshMode,
  ): RefreshTrace {
    Log.i(
      TAG,
      "event=refresh_started trigger=${trigger.logValue} mode=${mode.logValue}",
    )
    return RefreshTrace(trigger, mode, SystemClock.elapsedRealtime())
  }

  fun refreshSkipped(
    trigger: LocalRefreshTrigger,
    mode: LocalRefreshMode,
    reason: String,
    remainingMillis: Long? = null,
  ) {
    val remaining = remainingMillis?.let { " remaining_ms=${it.coerceAtLeast(0L)}" }.orEmpty()
    Log.i(
      TAG,
      "event=refresh_skipped trigger=${trigger.logValue} mode=${mode.logValue} " +
        "reason=${safeReason(reason)}$remaining",
    )
  }

  fun bridgeQueued(
    operationId: Long,
    operation: DiagnosticBridgeOperation,
    targetCount: Int,
  ) {
    Log.i(
      TAG,
      "event=bridge_queued op_id=$operationId operation=${operation.logValue} " +
        "targets=${targetCount.coerceAtLeast(0)}",
    )
  }

  fun bridgeStarted(
    operationId: Long,
    operation: DiagnosticBridgeOperation,
    queueMillis: Long,
  ) {
    Log.i(
      TAG,
      "event=bridge_started op_id=$operationId operation=${operation.logValue} " +
        "queue_ms=${queueMillis.coerceAtLeast(0L)}",
    )
  }

  fun bridgeCompleted(
    operationId: Long,
    operation: DiagnosticBridgeOperation,
    activeMillis: Long,
  ) {
    Log.i(
      TAG,
      "event=bridge_completed op_id=$operationId operation=${operation.logValue} " +
        "active_ms=${activeMillis.coerceAtLeast(0L)}",
    )
  }

  fun bridgeFailed(
    operationId: Long,
    operation: DiagnosticBridgeOperation,
    code: String,
    queuedMillis: Long,
    activeMillis: Long?,
  ) {
    Log.w(
      TAG,
      "event=bridge_failed op_id=$operationId operation=${operation.logValue} " +
        "code=${safeCode(code)} queued_ms=${queuedMillis.coerceAtLeast(0L)} " +
        "active_ms=${activeMillis?.coerceAtLeast(0L) ?: -1L}",
    )
  }

  fun discoveryResult(operationId: Long, result: LanDiscoveryResult) {
    Log.i(
      TAG,
      "event=discovery_result op_id=$operationId bridge_ms=${result.durationMillis} " +
        "devices=${result.deviceCount} matched=${result.matchedDeviceCount} " +
        "unmatched=${result.unmatchedDeviceCount} warnings=${result.warnings.size}",
    )
  }

  fun pollResult(operationId: Long, result: LocalPollResult) {
    Log.i(
      TAG,
      "event=poll_result op_id=$operationId bridge_ms=${result.durationMillis} " +
        "targets=${result.deviceCount} responded=${result.respondedDeviceCount} " +
        "offline=${result.offlineDeviceCount} errors=${result.errorDeviceCount} " +
        "warnings=${result.warnings.size}",
    )
    result.devices.forEachIndexed { index, device ->
      Log.i(
        TAG,
        "event=poll_target op_id=$operationId target_index=${index + 1} " +
          "state=${device.state.wireValue} code=${safeCode(device.errorCode)} " +
          "attempts=${device.attemptCount} duration_ms=${device.durationMillis} " +
          "data_points=${device.dataPoints.size}",
      )
    }
  }

  internal class RefreshTrace(
    private val trigger: LocalRefreshTrigger,
    private val mode: LocalRefreshMode,
    private val startedAt: Long,
  ) {
    fun completed() {
      Log.i(
        TAG,
        "event=refresh_completed trigger=${trigger.logValue} mode=${mode.logValue} " +
          "total_ms=${elapsedMillis()}",
      )
    }

    fun failed(code: String, phase: LocalRefreshMode = mode) {
      Log.w(
        TAG,
        "event=refresh_failed trigger=${trigger.logValue} mode=${phase.logValue} " +
          "code=${safeCode(code)} total_ms=${elapsedMillis()}",
      )
    }

    fun fallback(to: LocalRefreshMode, code: String) {
      Log.i(
        TAG,
        "event=refresh_fallback trigger=${trigger.logValue} from=${mode.logValue} " +
          "to=${to.logValue} code=${safeCode(code)} total_ms=${elapsedMillis()}",
      )
    }

    private fun elapsedMillis(): Long =
      (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
  }

  private fun safeCode(code: String): String =
    when {
      code.isEmpty() -> "none"
      stableCodePattern.matches(code) -> code
      else -> "REDACTED_INVALID_CODE"
    }

  private fun safeReason(reason: String): String =
    when (reason) {
      "busy",
      "cooldown",
      "disabled",
      "invalid_destination",
      "network_changed",
      "no_current_targets",
      "no_previously_matched_targets",
      "snapshot_not_current" -> reason
      else -> "unspecified"
    }
}
