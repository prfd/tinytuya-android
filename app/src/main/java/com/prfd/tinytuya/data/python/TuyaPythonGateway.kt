package com.prfd.tinytuya.data.python

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
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
        cloudImportMutex.withLock {
            val response = python(applicationContext)
                .getModule(BRIDGE_MODULE)
                .callAttr(
                    "import_cloud",
                    credentials.toBridgeJson().toString(),
                    previousDevices.toBridgeJson().toString(),
                )
                .toString()

            parseCloudImport(response)
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

    private fun List<CloudImportedDevice>.toBridgeJson(): JSONArray = JSONArray().apply {
        for (device in this@toBridgeJson) {
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

    private fun JSONArray.toStringList(): List<String> = buildList(length()) {
        for (index in 0 until length()) {
            add(getString(index))
        }
    }

    private companion object {
        const val BRIDGE_CONTRACT_VERSION = 1
        const val BRIDGE_MODULE = "tuya_bridge"
        val pythonStartLock = Any()
        val cloudImportMutex = Mutex()

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
