package com.prfd.tinytuya.data.python

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.prfd.tinytuya.data.lan.LanDiscoveredDevice
import com.prfd.tinytuya.data.lan.LanDiscoveryRequest
import com.prfd.tinytuya.data.lan.LanDiscoveryResult
import com.prfd.tinytuya.data.lan.LanKnownDevice
import com.prfd.tinytuya.data.lan.LanNetworkContext
import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.lan.LocalPollDevice
import com.prfd.tinytuya.data.lan.LocalPollDeviceState
import com.prfd.tinytuya.data.lan.LocalPollRequest
import com.prfd.tinytuya.data.lan.LocalPollResult
import com.prfd.tinytuya.data.lan.LocalPolledDevice
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

    override suspend fun health(): PythonRuntimeHealth = withContext(Dispatchers.IO) {
        val response = python(applicationContext)
            .getModule(BRIDGE_MODULE)
            .callAttr("health")
            .toString()

        parseHealth(response)
    }

    override suspend fun importCloud(
        credentials: CloudCredentials,
        previousDevices: List<CloudImportedDevice>,
    ): CloudImportResult = withContext(Dispatchers.IO) {
        bridgeOperationMutex.withLock {
            val response = python(applicationContext)
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
            bridgeOperationMutex.withLock {
                val response = python(applicationContext)
                    .getModule(BRIDGE_MODULE)
                    .callAttr(
                        "discover_lan",
                        request.network.toBridgeJson(request.timeoutSeconds).toString(),
                        request.knownDevices.toLanBridgeJson().toString(),
                    )
                    .toString()

                parseLanDiscovery(response)
            }
        }

    override suspend fun pollLocal(request: LocalPollRequest): LocalPollResult =
        withContext(Dispatchers.IO) {
            bridgeOperationMutex.withLock {
                val response = python(applicationContext)
                    .getModule(BRIDGE_MODULE)
                    .callAttr(
                        "poll_local",
                        request.network.toBridgeJson().toString(),
                        request.devices.toLocalPollBridgeJson().toString(),
                    )
                    .toString()

                parseLocalPoll(response)
            }
        }

    private fun parseHealth(response: String): PythonRuntimeHealth = parseResponse(response) { contractVersion, result ->
        val crypto = result.getJSONObject("crypto")
        val protocolsJson = result.getJSONArray("supported_protocols")

        PythonRuntimeHealth(
            contractVersion = contractVersion,
            pythonVersion = result.getString("python_version"),
            tinytuyaVersion = result.getString("tinytuya_version"),
            crypto = PythonCryptoHealth(
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
            val region = TuyaCloudRegion.entries.firstOrNull { it.apiCode == regionCode }
                ?: throw PythonBridgeException(
                    code = "BRIDGE_RESPONSE_INVALID",
                    message = "The Python bridge returned an unknown cloud region.",
                )
            val devicesJson = result.getJSONArray("devices")
            val devices = buildList(devicesJson.length()) {
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
            val devices = buildList(devicesJson.length()) {
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

    private fun parseLocalPoll(response: String): LocalPollResult =
        parseResponse(response) { contractVersion, result ->
            val devicesJson = result.getJSONArray("devices")
            val devices = buildList(devicesJson.length()) {
                for (index in 0 until devicesJson.length()) {
                    val device = devicesJson.getJSONObject(index)
                    val stateCode = device.getString("state")
                    val state = LocalPollDeviceState.entries.firstOrNull {
                        it.wireValue == stateCode
                    } ?: throw invalidLocalPollResponse()
                    val dataPointsJson = device.getJSONArray("data_points")
                    val dataPoints = buildList(dataPointsJson.length()) {
                        for (dataPointIndex in 0 until dataPointsJson.length()) {
                            val dataPoint = dataPointsJson.getJSONObject(dataPointIndex)
                            val kindCode = dataPoint.getString("kind")
                            val kind = LocalDataPointKind.entries.firstOrNull {
                                it.wireValue == kindCode
                            } ?: throw invalidLocalPollResponse()
                            add(
                                LocalDataPoint(
                                    id = dataPoint.getString("id"),
                                    kind = kind,
                                    value = dataPoint.getString("value"),
                                )
                            )
                        }
                    }
                    add(
                        LocalPolledDevice(
                            id = device.getString("id"),
                            state = state,
                            errorCode = device.getString("error_code"),
                            durationMillis = device.getLong("duration_ms"),
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

    private fun invalidPolledDevice(device: LocalPolledDevice): Boolean =
        device.id.isBlank() ||
            device.id.length > 128 ||
            device.durationMillis !in 0..MAX_SINGLE_POLL_DURATION_MILLIS ||
            (device.state == LocalPollDeviceState.RESPONDED && device.errorCode.isNotEmpty()) ||
            (device.state != LocalPollDeviceState.RESPONDED && device.errorCode.isBlank()) ||
            (device.state != LocalPollDeviceState.RESPONDED && device.dataPoints.isNotEmpty()) ||
            device.errorCode.length > 64 ||
            device.dataPoints.size > MAX_LOCAL_DATA_POINT_COUNT ||
            device.dataPoints.map { it.id }.toSet().size != device.dataPoints.size ||
            device.dataPoints.any { dataPoint ->
                dataPoint.id.isBlank() ||
                    dataPoint.id.length > 8 ||
                    dataPoint.id.any { !it.isDigit() } ||
                    dataPoint.value.length > MAX_LOCAL_DATA_POINT_VALUE_LENGTH
            }

    private fun invalidLocalPollResponse() = PythonBridgeException(
        code = "BRIDGE_RESPONSE_INVALID",
        message = "The Python bridge returned an invalid local status result.",
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

    private fun CloudCredentials.toBridgeJson(): JSONObject = JSONObject().apply {
        put("region", region.apiCode)
        put("client_id", clientId.trim())
        put("client_secret", clientSecret.reveal().trim())
        put("device_id", sampleDeviceId?.trim().orEmpty())
    }

    private fun List<CloudImportedDevice>.toCloudBridgeJson(): JSONArray = JSONArray().apply {
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
                    put("mapping", runCatching { JSONObject(device.mappingJson) }.getOrDefault(JSONObject()))
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

    private fun List<LocalPollDevice>.toLocalPollBridgeJson(): JSONArray = JSONArray().apply {
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

    private fun List<LanKnownDevice>.toLanBridgeJson(): JSONArray = JSONArray().apply {
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

    private fun JSONArray.toStringList(): List<String> = buildList(length()) {
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
        const val MAX_SINGLE_POLL_DURATION_MILLIS = 30_000L
        const val MAX_LOCAL_POLL_DURATION_MILLIS = 120_000L
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
