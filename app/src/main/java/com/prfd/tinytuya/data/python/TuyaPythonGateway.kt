package com.prfd.tinytuya.data.python

import android.content.Context
import android.os.SystemClock
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.prfd.tinytuya.data.lan.DiagnosticBridgeOperation
import com.prfd.tinytuya.data.lan.LanDiscoveredDevice
import com.prfd.tinytuya.data.lan.LanDiscoveryRequest
import com.prfd.tinytuya.data.lan.LanDiscoveryResult
import com.prfd.tinytuya.data.lan.LanKnownDevice
import com.prfd.tinytuya.data.lan.LanNetworkContext
import com.prfd.tinytuya.data.lan.LocalControlChange
import com.prfd.tinytuya.data.lan.LocalControlDevice
import com.prfd.tinytuya.data.lan.LocalControlRequest
import com.prfd.tinytuya.data.lan.LocalControlResult
import com.prfd.tinytuya.data.lan.LocalControlState
import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.lan.LocalPollDevice
import com.prfd.tinytuya.data.lan.LocalPollDeviceState
import com.prfd.tinytuya.data.lan.LocalPollRequest
import com.prfd.tinytuya.data.lan.LocalPollResult
import com.prfd.tinytuya.data.lan.LocalPolledDevice
import com.prfd.tinytuya.data.lan.LocalRefreshDiagnostics
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

interface TuyaPythonGateway {
  suspend fun health(): PythonRuntimeHealth

  suspend fun importCloud(
    credentials: CloudCredentials,
    previousDevices: List<CloudImportedDevice> = emptyList(),
  ): CloudImportResult

  suspend fun discoverLan(request: LanDiscoveryRequest): LanDiscoveryResult

  suspend fun pollLocal(request: LocalPollRequest): LocalPollResult

  suspend fun setLocalValues(request: LocalControlRequest): LocalControlResult
}

data class PythonRuntimeHealth(
  val contractVersion: Int,
  val pythonVersion: String,
  val tinytuyaVersion: String,
  val crypto: PythonCryptoHealth,
  val supportedProtocols: List<String>,
)

data class PythonCryptoHealth(
  val library: String,
  val version: String,
  val gcmAvailable: Boolean,
  val selfTestPassed: Boolean,
)

class PythonBridgeException(
  val code: String,
  message: String,
) : IllegalStateException(message)

class ChaquopyTuyaPythonGateway(context: Context) : TuyaPythonGateway {
  private val applicationContext = context.applicationContext

  override suspend fun health(): PythonRuntimeHealth =
    withContext(Dispatchers.IO) {
      val response =
        python(applicationContext).getModule(BRIDGE_MODULE).callAttr("health").toString()

      parseHealth(response)
    }

  override suspend fun importCloud(
    credentials: CloudCredentials,
    previousDevices: List<CloudImportedDevice>,
  ): CloudImportResult =
    withContext(Dispatchers.IO) {
      runBridgeOperation(
        operation = DiagnosticBridgeOperation.CLOUD_IMPORT,
        targetCount = previousDevices.size,
      ) {
        val response =
          python(applicationContext)
            .getModule(BRIDGE_MODULE)
            .callAttr(
              "import_cloud",
              credentials.toBridgeJson().toString(),
              previousDevices.toCloudBridgeJson().toString(),
            )
            .toString()

        parseCloudImport(response)
      }
    }

  override suspend fun discoverLan(request: LanDiscoveryRequest): LanDiscoveryResult =
    withContext(Dispatchers.IO) {
      runBridgeOperation(
        operation = DiagnosticBridgeOperation.DISCOVERY,
        targetCount = request.knownDevices.size,
      ) { operationId ->
        val response =
          python(applicationContext)
            .getModule(BRIDGE_MODULE)
            .callAttr(
              "discover_lan",
              request.network.toBridgeJson(request.timeoutSeconds).toString(),
              request.knownDevices.toLanBridgeJson().toString(),
            )
            .toString()

        parseLanDiscovery(response).also { result ->
          LocalRefreshDiagnostics.discoveryResult(operationId, result)
        }
      }
    }

  override suspend fun pollLocal(request: LocalPollRequest): LocalPollResult =
    withContext(Dispatchers.IO) {
      runBridgeOperation(
        operation = DiagnosticBridgeOperation.STATUS,
        targetCount = request.devices.size,
      ) { operationId ->
        val response =
          python(applicationContext)
            .getModule(BRIDGE_MODULE)
            .callAttr(
              "poll_local",
              request.network.toBridgeJson().toString(),
              request.devices.toLocalPollBridgeJson().toString(),
            )
            .toString()

        parseLocalPoll(response).also { result ->
          LocalRefreshDiagnostics.pollResult(operationId, result)
        }
      }
    }

  override suspend fun setLocalValues(request: LocalControlRequest): LocalControlResult =
    withContext(Dispatchers.IO) {
      runBridgeOperation(
        operation = DiagnosticBridgeOperation.CONTROL,
        targetCount = 1,
      ) {
        val response =
          python(applicationContext)
            .getModule(BRIDGE_MODULE)
            .callAttr(
              "set_values",
              request.network.toBridgeJson().toString(),
              request.device.toLocalControlBridgeJson().toString(),
              request.changes.toLocalControlBridgeJson().toString(),
            )
            .toString()

        parseLocalControl(response, request)
      }
    }

  private suspend fun <T> runBridgeOperation(
    operation: DiagnosticBridgeOperation,
    targetCount: Int,
    block: (operationId: Long) -> T,
  ): T {
    val operationId = LocalRefreshDiagnostics.nextBridgeOperationId()
    val queuedAt = SystemClock.elapsedRealtime()
    var startedAt: Long? = null
    LocalRefreshDiagnostics.bridgeQueued(operationId, operation, targetCount)
    try {
      return bridgeOperationMutex.withLock {
        val currentStartedAt = SystemClock.elapsedRealtime()
        startedAt = currentStartedAt
        LocalRefreshDiagnostics.bridgeStarted(
          operationId = operationId,
          operation = operation,
          queueMillis = currentStartedAt - queuedAt,
        )
        block(operationId).also {
          LocalRefreshDiagnostics.bridgeCompleted(
            operationId = operationId,
            operation = operation,
            activeMillis = SystemClock.elapsedRealtime() - currentStartedAt,
          )
        }
      }
    } catch (error: CancellationException) {
      logBridgeFailure(operationId, operation, "CANCELLED", queuedAt, startedAt)
      throw error
    } catch (error: PythonBridgeException) {
      logBridgeFailure(operationId, operation, error.code, queuedAt, startedAt)
      throw error
    } catch (error: Exception) {
      logBridgeFailure(
        operationId,
        operation,
        "UNEXPECTED_EXCEPTION",
        queuedAt,
        startedAt,
      )
      throw error
    }
  }

  private fun logBridgeFailure(
    operationId: Long,
    operation: DiagnosticBridgeOperation,
    code: String,
    queuedAt: Long,
    startedAt: Long?,
  ) {
    val now = SystemClock.elapsedRealtime()
    LocalRefreshDiagnostics.bridgeFailed(
      operationId = operationId,
      operation = operation,
      code = code,
      queuedMillis = (startedAt ?: now) - queuedAt,
      activeMillis = startedAt?.let { now - it },
    )
  }

  private fun parseHealth(response: String): PythonRuntimeHealth =
    parseResponse(response) { contractVersion, result ->
      val crypto = result.getJSONObject("crypto")
      val protocolsJson = result.getJSONArray("supported_protocols")

      PythonRuntimeHealth(
        contractVersion = contractVersion,
        pythonVersion = result.getString("python_version"),
        tinytuyaVersion = result.getString("tinytuya_version"),
        crypto =
          PythonCryptoHealth(
            library = crypto.getString("library"),
            version = crypto.getString("version"),
            gcmAvailable = crypto.getBoolean("gcm_available"),
            selfTestPassed = crypto.getBoolean("self_test_passed"),
          ),
        supportedProtocols = protocolsJson.toStringList(),
      )
    }

  private fun parseCloudImport(response: String): CloudImportResult =
    parseResponse(response) { contractVersion, result ->
      val regionCode = result.getString("region")
      val region =
        TuyaCloudRegion.entries.firstOrNull { it.apiCode == regionCode }
          ?: throw PythonBridgeException(
            code = "BRIDGE_RESPONSE_INVALID",
            message = "The Python bridge returned an unknown cloud region.",
          )
      val devicesJson = result.getJSONArray("devices")
      val devices =
        buildList(devicesJson.length()) {
          for (index in 0 until devicesJson.length()) {
            val device = devicesJson.getJSONObject(index)
            add(
              CloudImportedDevice(
                id = device.getString("id"),
                name = device.getString("name"),
                localKey = SensitiveString.of(device.getString("local_key")),
                category = device.getString("category"),
                productId = device.getString("product_id"),
                productName = device.getString("product_name"),
                model = device.getString("model"),
                mac = device.getString("mac"),
                uuid = device.getString("uuid"),
                isSubDevice = device.getBoolean("sub_device"),
                gatewayId = device.getString("gateway_id"),
                nodeId = device.getString("node_id"),
                protocolVersion = device.getString("protocol_version"),
                lastIp = device.getString("last_ip"),
                mappingJson = device.getJSONObject("mapping").toString(),
              )
            )
          }
        }

      CloudImportResult(
        contractVersion = contractVersion,
        region = region,
        deviceCount = result.getInt("device_count"),
        missingLocalKeyCount = result.getInt("missing_local_key_count"),
        warnings = result.getJSONArray("warnings").toStringList(),
        devices = devices,
      )
    }

  private fun parseLanDiscovery(response: String): LanDiscoveryResult =
    parseResponse(response) { contractVersion, result ->
      val devicesJson = result.getJSONArray("devices")
      val devices =
        buildList(devicesJson.length()) {
          for (index in 0 until devicesJson.length()) {
            val device = devicesJson.getJSONObject(index)
            add(
              LanDiscoveredDevice(
                id = device.getString("id"),
                ip = device.getString("ip"),
                protocolVersion = device.getString("protocol_version"),
                productKey = device.getString("product_key"),
                mac = device.getString("mac"),
                origin = device.getString("origin"),
              )
            )
          }
        }
      val deviceCount = result.getInt("device_count")
      val matchedCount = result.getInt("matched_device_count")
      val unmatchedCount = result.getInt("unmatched_device_count")
      val durationMillis = result.getLong("duration_ms")
      if (
        deviceCount != devices.size ||
          matchedCount < 0 ||
          unmatchedCount < 0 ||
          matchedCount + unmatchedCount != deviceCount ||
          durationMillis < 0 ||
          devices.any { it.id.isBlank() || it.ip.isBlank() || it.origin != "broadcast" } ||
          devices.map { it.id }.toSet().size != devices.size
      ) {
        throw PythonBridgeException(
          code = "BRIDGE_RESPONSE_INVALID",
          message = "The Python bridge returned an invalid discovery result.",
        )
      }

      LanDiscoveryResult(
        contractVersion = contractVersion,
        deviceCount = deviceCount,
        matchedDeviceCount = matchedCount,
        unmatchedDeviceCount = unmatchedCount,
        durationMillis = durationMillis,
        warnings = result.getJSONArray("warnings").toStringList(),
        devices = devices,
      )
    }

  internal fun parseLocalPoll(response: String): LocalPollResult =
    parseResponse(response) { contractVersion, result ->
      val devicesJson = result.getJSONArray("devices")
      val devices =
        buildList(devicesJson.length()) {
          for (index in 0 until devicesJson.length()) {
            val device = devicesJson.getJSONObject(index)
            val stateCode = device.getString("state")
            val state =
              LocalPollDeviceState.entries.firstOrNull { it.wireValue == stateCode }
                ?: throw invalidLocalPollResponse()
            val dataPoints = parseLocalDataPoints(device.getJSONArray("data_points"))
            add(
              LocalPolledDevice(
                id = device.getString("id"),
                state = state,
                errorCode = device.getString("error_code"),
                durationMillis = device.getLong("duration_ms"),
                attemptCount = device.getInt("attempt_count"),
                dataPoints = dataPoints,
              )
            )
          }
        }
      val deviceCount = result.getInt("device_count")
      val respondedCount = result.getInt("responded_device_count")
      val offlineCount = result.getInt("offline_device_count")
      val errorCount = result.getInt("error_device_count")
      val durationMillis = result.getLong("duration_ms")
      if (
        deviceCount != devices.size ||
          deviceCount !in 1..MAX_LOCAL_POLL_DEVICE_COUNT ||
          minOf(respondedCount, offlineCount, errorCount) < 0 ||
          respondedCount + offlineCount + errorCount != deviceCount ||
          respondedCount != devices.count { it.state == LocalPollDeviceState.RESPONDED } ||
          offlineCount != devices.count { it.state == LocalPollDeviceState.OFFLINE } ||
          errorCount != devices.count { it.state == LocalPollDeviceState.ERROR } ||
          durationMillis !in 0..MAX_LOCAL_POLL_DURATION_MILLIS ||
          devices.map { it.id }.toSet().size != devices.size ||
          devices.any(::invalidPolledDevice)
      ) {
        throw invalidLocalPollResponse()
      }

      LocalPollResult(
        contractVersion = contractVersion,
        deviceCount = deviceCount,
        respondedDeviceCount = respondedCount,
        offlineDeviceCount = offlineCount,
        errorDeviceCount = errorCount,
        durationMillis = durationMillis,
        warnings = result.getJSONArray("warnings").toStringList(),
        devices = devices,
      )
    }

  private fun parseLocalControl(
    response: String,
    request: LocalControlRequest,
  ): LocalControlResult =
    parseResponse(response) { contractVersion, result ->
      val stateCode = result.getString("state")
      val state =
        LocalControlState.entries.firstOrNull { it.wireValue == stateCode }
          ?: throw invalidLocalControlResponse()
      val dataPoints = parseLocalDataPoints(result.getJSONArray("data_points"))
      val controlResult =
        LocalControlResult(
          contractVersion = contractVersion,
          id = result.getString("id"),
          state = state,
          errorCode = result.getString("error_code"),
          durationMillis = result.getLong("duration_ms"),
          dataPoints = dataPoints,
        )
      val actualById = dataPoints.associateBy { it.id }
      val requestedValuesConfirmed =
        request.changes.all { change ->
          actualById[change.id]?.let { actual ->
            actual.kind == change.kind && actual.value == change.value
          } == true
        }
      if (
        controlResult.id != request.device.id ||
          controlResult.id.isBlank() ||
          controlResult.id.length > 128 ||
          controlResult.durationMillis !in 0..MAX_LOCAL_CONTROL_DURATION_MILLIS ||
          controlResult.errorCode.length > 64 ||
          dataPoints.size > MAX_LOCAL_DATA_POINT_COUNT ||
          dataPoints.map { it.id }.toSet().size != dataPoints.size ||
          dataPoints.any(::invalidLocalDataPoint) ||
          request.changes.size !in 1..MAX_LOCAL_CONTROL_CHANGE_COUNT ||
          (state == LocalControlState.CONFIRMED &&
            (controlResult.errorCode.isNotEmpty() || !requestedValuesConfirmed)) ||
          (state != LocalControlState.CONFIRMED && controlResult.errorCode.isBlank()) ||
          (state in setOf(LocalControlState.OFFLINE, LocalControlState.ERROR) &&
            dataPoints.isNotEmpty())
      ) {
        throw invalidLocalControlResponse()
      }
      controlResult
    }

  private fun parseLocalDataPoints(dataPointsJson: JSONArray): List<LocalDataPoint> =
    buildList(dataPointsJson.length()) {
      for (dataPointIndex in 0 until dataPointsJson.length()) {
        val dataPoint = dataPointsJson.getJSONObject(dataPointIndex)
        val kindCode = dataPoint.getString("kind")
        val kind =
          LocalDataPointKind.entries.firstOrNull { it.wireValue == kindCode }
            ?: throw invalidLocalPollResponse()
        add(
          LocalDataPoint(
            id = dataPoint.getString("id"),
            kind = kind,
            value = dataPoint.getString("value"),
          )
        )
      }
    }

  private fun invalidPolledDevice(device: LocalPolledDevice): Boolean =
    device.id.isBlank() ||
      device.id.length > 128 ||
      device.durationMillis !in 0..MAX_SINGLE_POLL_DURATION_MILLIS ||
      device.attemptCount !in 1..MAX_LOCAL_POLL_ATTEMPT_COUNT ||
      (device.state == LocalPollDeviceState.RESPONDED && device.errorCode.isNotEmpty()) ||
      (device.state != LocalPollDeviceState.RESPONDED && device.errorCode.isBlank()) ||
      (device.state != LocalPollDeviceState.RESPONDED && device.dataPoints.isNotEmpty()) ||
      device.errorCode.length > 64 ||
      device.dataPoints.size > MAX_LOCAL_DATA_POINT_COUNT ||
      device.dataPoints.map { it.id }.toSet().size != device.dataPoints.size ||
      device.dataPoints.any(::invalidLocalDataPoint)

  private fun invalidLocalDataPoint(dataPoint: LocalDataPoint): Boolean =
    dataPoint.id.isBlank() ||
      dataPoint.id.length > 8 ||
      dataPoint.id.any { !it.isDigit() } ||
      dataPoint.value.length > MAX_LOCAL_DATA_POINT_VALUE_LENGTH

  private fun invalidLocalPollResponse() =
    PythonBridgeException(
      code = "BRIDGE_RESPONSE_INVALID",
      message = "The Python bridge returned an invalid local status result.",
    )

  private fun invalidLocalControlResponse() =
    PythonBridgeException(
      code = "BRIDGE_RESPONSE_INVALID",
      message = "The Python bridge returned an invalid local control result.",
    )

  private inline fun <T> parseResponse(
    response: String,
    transform: (contractVersion: Int, result: JSONObject) -> T,
  ): T {
    try {
      val envelope = JSONObject(response)
      val contractVersion = envelope.getInt("contract_version")
      if (contractVersion != BRIDGE_CONTRACT_VERSION) {
        throw PythonBridgeException(
          code = "BRIDGE_CONTRACT_UNSUPPORTED",
          message = "The embedded Python bridge uses an unsupported contract version.",
        )
      }

      if (!envelope.getBoolean("ok")) {
        val error = envelope.getJSONObject("error")
        throw PythonBridgeException(
          code = error.getString("code"),
          message = error.getString("message"),
        )
      }

      return transform(contractVersion, envelope.getJSONObject("result"))
    } catch (error: PythonBridgeException) {
      throw error
    } catch (_: Exception) {
      throw PythonBridgeException(
        code = "BRIDGE_RESPONSE_INVALID",
        message = "The embedded Python bridge returned an invalid response.",
      )
    }
  }

  private fun CloudCredentials.toBridgeJson(): JSONObject =
    JSONObject().apply {
      put("region", region.apiCode)
      put("client_id", clientId.trim())
      put("client_secret", clientSecret.reveal().trim())
      put("device_id", sampleDeviceId?.trim().orEmpty())
    }

  private fun List<CloudImportedDevice>.toCloudBridgeJson(): JSONArray =
    JSONArray().apply {
      for (device in this@toCloudBridgeJson) {
        put(
          JSONObject().apply {
            put("id", device.id)
            put("name", device.name)
            put("local_key", device.localKey.reveal())
            put("category", device.category)
            put("product_id", device.productId)
            put("product_name", device.productName)
            put("model", device.model)
            put("mac", device.mac)
            put("uuid", device.uuid)
            put("sub", device.isSubDevice)
            put("gateway_id", device.gatewayId)
            put("node_id", device.nodeId)
            put("version", device.protocolVersion)
            put("last_ip", device.lastIp)
            put(
              "mapping",
              runCatching { JSONObject(device.mappingJson) }.getOrDefault(JSONObject()),
            )
          }
        )
      }
    }

  private fun LanNetworkContext.toBridgeJson(timeoutSeconds: Int? = null): JSONObject =
    JSONObject().apply {
      put("interface_name", interfaceName)
      put("local_ipv4", localIpv4)
      put("prefix_length", prefixLength)
      put("broadcast_ipv4", broadcastIpv4)
      timeoutSeconds?.let { put("timeout_seconds", it) }
    }

  private fun List<LocalPollDevice>.toLocalPollBridgeJson(): JSONArray =
    JSONArray().apply {
      for (device in this@toLocalPollBridgeJson) {
        put(
          JSONObject().apply {
            put("id", device.id)
            put("ip", device.ip)
            put("local_key", device.localKey.reveal())
            put("protocol_version", device.protocolVersion)
          }
        )
      }
    }

  private fun LocalControlDevice.toLocalControlBridgeJson(): JSONObject =
    JSONObject().apply {
      put("id", id)
      put("ip", ip)
      put("local_key", localKey.reveal())
      put("protocol_version", protocolVersion)
    }

  private fun List<LocalControlChange>.toLocalControlBridgeJson(): JSONArray =
    JSONArray().apply {
      for (change in this@toLocalControlBridgeJson) {
        put(
          JSONObject().apply {
            put("id", change.id)
            put("kind", change.kind.wireValue)
            put("value", change.value)
          }
        )
      }
    }

  private fun List<LanKnownDevice>.toLanBridgeJson(): JSONArray =
    JSONArray().apply {
      for (device in this@toLanBridgeJson) {
        put(
          JSONObject().apply {
            put("id", device.id)
            put("name", device.name)
            put("mac", device.mac)
          }
        )
      }
    }

  private fun JSONArray.toStringList(): List<String> =
    buildList(length()) {
      for (index in 0 until length()) {
        add(getString(index))
      }
    }

  private companion object {
    const val BRIDGE_CONTRACT_VERSION = 1
    const val BRIDGE_MODULE = "tuya_bridge"
    const val MAX_LOCAL_POLL_DEVICE_COUNT = 32
    const val MAX_LOCAL_DATA_POINT_COUNT = 256
    const val MAX_LOCAL_DATA_POINT_VALUE_LENGTH = 8192
    const val MAX_LOCAL_POLL_ATTEMPT_COUNT = 3
    const val MAX_SINGLE_POLL_DURATION_MILLIS = 30_000L
    const val MAX_LOCAL_POLL_DURATION_MILLIS = 120_000L
    const val MAX_LOCAL_CONTROL_CHANGE_COUNT = 8
    const val MAX_LOCAL_CONTROL_DURATION_MILLIS = 30_000L
    val pythonStartLock = Any()
    val bridgeOperationMutex = Mutex()

    fun python(context: Context): Python {
      if (!Python.isStarted()) {
        synchronized(pythonStartLock) {
          if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
          }
        }
      }
      return Python.getInstance()
    }
  }
}
