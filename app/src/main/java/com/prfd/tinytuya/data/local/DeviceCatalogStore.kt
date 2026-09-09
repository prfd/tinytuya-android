package com.prfd.tinytuya.data.local

import android.content.Context
import android.util.AtomicFile
import com.prfd.tinytuya.data.lan.LanDiscoveryResult
import com.prfd.tinytuya.data.lan.LanNetworkContext
import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.lan.LocalPollDeviceState
import com.prfd.tinytuya.data.lan.LocalPollResult
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class DeviceCatalog(
  val schemaVersion: Int,
  val importedAtEpochMillis: Long,
  val region: TuyaCloudRegion,
  val devices: List<CloudImportedDevice>,
  val lastDiscoveryAtEpochMillis: Long? = null,
  val lastDiscoveryNetwork: LanNetworkContext? = null,
  val lanDevices: List<LanDeviceRecord> = emptyList(),
  val lastLocalPollAtEpochMillis: Long? = null,
  val localStatus: List<LocalStatusRecord> = emptyList(),
)

data class LanDeviceRecord(
  val id: String,
  val ip: String,
  val protocolVersion: String,
  val productKey: String,
  val mac: String,
  val origin: String,
  val lastSeenAtEpochMillis: Long,
)

data class LocalStatusRecord(
  val id: String,
  val state: LocalPollDeviceState,
  val errorCode: String,
  val durationMillis: Long,
  val dataPoints: List<LocalDataPoint>,
  val polledAtEpochMillis: Long,
) {
  override fun toString(): String =
    "LocalStatusRecord(id=[REDACTED], state=$state, errorCode=$errorCode, " +
      "durationMillis=$durationMillis, dataPointCount=${dataPoints.size}, " +
      "polledAtEpochMillis=$polledAtEpochMillis)"
}

class DeviceCatalogStorageException(
  val code: String,
  message: String,
) : IllegalStateException(message)

interface DeviceCatalogStore {
  suspend fun load(): DeviceCatalog?

  suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog

  suspend fun mergeLanDiscovery(
    result: LanDiscoveryResult,
    network: LanNetworkContext,
  ): DeviceCatalog

  suspend fun mergeLocalPoll(result: LocalPollResult): DeviceCatalog

  /**
   * Rebinds the saved discovery network identity without touching the generation marker, LAN
   * records, or status. Used when a verification poll re-proved reachability after Android changed
   * only the opaque network handle or the phone's own address on the same subnet.
   */
  suspend fun rebindDiscoveryNetwork(network: LanNetworkContext): DeviceCatalog

  suspend fun deleteAll()
}

/**
 * Stores the entire imported catalog as one plaintext JSON document.
 *
 * The file lives under [Context.getNoBackupFilesDir], which Android excludes from backup. App
 * sandboxing and the manifest backup rules protect it; it is deliberately not encrypted so the
 * store stays simple. No cloud credential is accepted by this API, so it cannot be retained
 * accidentally.
 */
class JsonDeviceCatalogStore
internal constructor(
  context: Context,
  private val catalogDirectory: File = File(context.noBackupFilesDir, CATALOG_DIRECTORY),
  private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : DeviceCatalogStore {
  internal val catalogFile = File(catalogDirectory, CATALOG_FILE)

  private val atomicFile = AtomicFile(catalogFile)
  private val mutex = Mutex()

  override suspend fun load(): DeviceCatalog? =
    withContext(Dispatchers.IO) { mutex.withLock { loadLocked() } }

  override suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        validateDevices(result.devices)
        val previous = loadLocked()
        val importedIds = result.devices.mapTo(mutableSetOf()) { it.id }
        val catalog =
          DeviceCatalog(
            schemaVersion = CATALOG_SCHEMA_VERSION,
            importedAtEpochMillis = currentTimeMillis(),
            region = result.region,
            devices = result.devices,
            lastDiscoveryAtEpochMillis = previous?.lastDiscoveryAtEpochMillis,
            lastDiscoveryNetwork = previous?.lastDiscoveryNetwork,
            lanDevices = previous?.lanDevices.orEmpty(),
            lastLocalPollAtEpochMillis = previous?.lastLocalPollAtEpochMillis,
            localStatus = previous?.localStatus.orEmpty().filter { it.id in importedIds },
          )
        writeCatalogLocked(catalog)
        catalog
      }
    }

  override suspend fun mergeLocalPoll(result: LocalPollResult): DeviceCatalog =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        validateLocalPollResult(result)
        val previous =
          loadLocked()
            ?: throw storageError(
              code = "CATALOG_MISSING",
              message = "Import devices before saving local status.",
            )
        val lastDiscoveryAt =
          previous.lastDiscoveryAtEpochMillis
            ?: throw storageError(
              code = "CATALOG_INVALID",
              message = "Discover devices before saving local status.",
            )
        val knownIds = previous.devices.mapTo(mutableSetOf()) { it.id }
        val currentLanIds =
          previous.lanDevices
            .filter { it.lastSeenAtEpochMillis == lastDiscoveryAt && it.id in knownIds }
            .mapTo(mutableSetOf()) { it.id }
        if (result.devices.any { it.id !in currentLanIds }) {
          throw storageError(
            code = "CATALOG_INVALID",
            message = "Local status was returned for a stale device address.",
          )
        }

        val previousPoll = previous.lastLocalPollAtEpochMillis
        val polledAt =
          maxOf(
            currentTimeMillis(),
            lastDiscoveryAt,
            previousPoll?.plus(1) ?: 0L,
          )
        val polledRecords =
          result.devices.associate { device ->
            device.id to
              LocalStatusRecord(
                id = device.id,
                state = device.state,
                errorCode = device.errorCode,
                durationMillis = device.durationMillis,
                dataPoints = device.dataPoints,
                polledAtEpochMillis = polledAt,
              )
          }
        val retainedRecords =
          previous.localStatus.filter { record ->
            record.id in knownIds && record.id !in polledRecords
          }
        val catalog =
          previous.copy(
            schemaVersion = CATALOG_SCHEMA_VERSION,
            lastLocalPollAtEpochMillis = polledAt,
            localStatus = (polledRecords.values + retainedRecords).sortedBy { it.id },
          )
        writeCatalogLocked(catalog)
        catalog
      }
    }

  override suspend fun rebindDiscoveryNetwork(network: LanNetworkContext): DeviceCatalog =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        validateDiscoveryNetwork(network)
        val previous =
          loadLocked()
            ?: throw storageError(
              code = "CATALOG_MISSING",
              message = "Import devices before updating the saved network.",
            )
        if (previous.lastDiscoveryAtEpochMillis == null) {
          throw storageError(
            code = "CATALOG_INVALID",
            message = "Discover devices before updating the saved network.",
          )
        }
        val catalog = previous.copy(lastDiscoveryNetwork = network)
        writeCatalogLocked(catalog)
        catalog
      }
    }

  override suspend fun mergeLanDiscovery(
    result: LanDiscoveryResult,
    network: LanNetworkContext,
  ): DeviceCatalog =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        validateLanResult(result)
        validateDiscoveryNetwork(network)
        val previous =
          loadLocked()
            ?: throw storageError(
              code = "CATALOG_MISSING",
              message = "Import devices before saving local discovery results.",
            )
        val previousDiscovery = previous.lastDiscoveryAtEpochMillis
        val discoveredAt =
          previousDiscovery?.let { prior -> maxOf(currentTimeMillis(), prior + 1) }
            ?: currentTimeMillis()
        val discoveredRecords =
          result.devices.associate { device ->
            device.id to
              LanDeviceRecord(
                id = device.id,
                ip = device.ip,
                protocolVersion = device.protocolVersion,
                productKey = device.productKey,
                mac = device.mac,
                origin = device.origin,
                lastSeenAtEpochMillis = discoveredAt,
              )
          }
        val knownIds = previous.devices.mapTo(mutableSetOf()) { it.id }
        val retainedKnownRecords =
          previous.lanDevices.filter { record ->
            record.id in knownIds && record.id !in discoveredRecords
          }
        val catalog =
          previous.copy(
            schemaVersion = CATALOG_SCHEMA_VERSION,
            lastDiscoveryAtEpochMillis = discoveredAt,
            lastDiscoveryNetwork = network,
            lanDevices = (discoveredRecords.values + retainedKnownRecords).sortedBy { it.id },
          )
        writeCatalogLocked(catalog)
        catalog
      }
    }

  override suspend fun deleteAll(): Unit =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        try {
          atomicFile.delete()
          catalogDirectory.delete()
          Unit
        } catch (_: Exception) {
          throw storageError(
            code = "CATALOG_DELETE_FAILED",
            message = "The saved device catalog could not be deleted.",
          )
        }
      }
    }

  private fun loadLocked(): DeviceCatalog? {
    try {
      val input =
        try {
          atomicFile.openRead()
        } catch (_: FileNotFoundException) {
          return null
        }
      val json = input.use {
        if (it.channel.size() !in 1..MAX_CATALOG_BYTES) {
          throw storageError(
            code = "CATALOG_INVALID",
            message = "The saved device catalog has an invalid size.",
          )
        }
        String(it.readBytes(), StandardCharsets.UTF_8)
      }
      return decodeCatalog(json)
    } catch (error: DeviceCatalogStorageException) {
      throw error
    } catch (_: Exception) {
      throw storageError(
        code = "CATALOG_READ_FAILED",
        message = "The saved device catalog could not be read safely.",
      )
    }
  }

  private fun writeCatalogLocked(catalog: DeviceCatalog) {
    validateCatalog(catalog)
    val bytes = encodeCatalog(catalog).toByteArray(StandardCharsets.UTF_8)
    if (bytes.size.toLong() > MAX_CATALOG_BYTES) {
      throw storageError(
        code = "CATALOG_TOO_LARGE",
        message = "The device catalog is too large to store safely.",
      )
    }
    writeAtomically(bytes)
  }

  private fun writeAtomically(bytes: ByteArray) {
    if (!catalogDirectory.exists() && !catalogDirectory.mkdirs()) {
      throw storageError(
        code = "CATALOG_WRITE_FAILED",
        message = "The private device-storage directory could not be created.",
      )
    }

    var output = atomicFile.startWrite()
    try {
      output.write(bytes)
      output.flush()
      atomicFile.finishWrite(output)
    } catch (_: Exception) {
      atomicFile.failWrite(output)
      throw storageError(
        code = "CATALOG_WRITE_FAILED",
        message = "The imported devices could not be stored atomically.",
      )
    }
  }

  private fun encodeCatalog(catalog: DeviceCatalog): String =
    JSONObject()
      .apply {
        put("schema_version", catalog.schemaVersion)
        put("imported_at_epoch_ms", catalog.importedAtEpochMillis)
        put("region", catalog.region.apiCode)
        put(
          "devices",
          JSONArray().apply { catalog.devices.forEach { device -> put(device.toJson()) } },
        )
        catalog.lastDiscoveryAtEpochMillis?.let { put("last_discovery_at_epoch_ms", it) }
        catalog.lastDiscoveryNetwork?.let { put("last_discovery_network", it.toJson()) }
        put(
          "lan_devices",
          JSONArray().apply { catalog.lanDevices.forEach { device -> put(device.toJson()) } },
        )
        catalog.lastLocalPollAtEpochMillis?.let { put("last_local_poll_at_epoch_ms", it) }
        put(
          "local_status",
          JSONArray().apply { catalog.localStatus.forEach { status -> put(status.toJson()) } },
        )
      }
      .toString()

  private fun decodeCatalog(json: String): DeviceCatalog {
    try {
      val root = JSONObject(json)
      val schemaVersion = root.getInt("schema_version")
      if (schemaVersion !in MIN_SUPPORTED_CATALOG_SCHEMA_VERSION..CATALOG_SCHEMA_VERSION) {
        throw storageError(
          code = "CATALOG_SCHEMA_UNSUPPORTED",
          message = "The saved device catalog uses an unsupported data schema.",
        )
      }

      val regionCode = root.getString("region")
      val region =
        TuyaCloudRegion.entries.firstOrNull { it.apiCode == regionCode }
          ?: throw storageError(
            code = "CATALOG_INVALID",
            message = "The saved device catalog contains an unknown cloud region.",
          )
      val devicesJson = root.getJSONArray("devices")
      if (devicesJson.length() > MAX_DEVICE_COUNT) {
        throw storageError(
          code = "CATALOG_INVALID",
          message = "The saved device catalog contains too many devices.",
        )
      }

      val devices =
        buildList(devicesJson.length()) {
          for (index in 0 until devicesJson.length()) {
            add(devicesJson.getJSONObject(index).toDevice())
          }
        }
      val lastDiscoveryAt = root.optLong("last_discovery_at_epoch_ms", 0L).takeIf { it > 0L }
      val lastDiscoveryNetwork = root.optJSONObject("last_discovery_network")?.toLanNetworkContext()
      val lanDevicesJson = root.optJSONArray("lan_devices") ?: JSONArray()
      if (lanDevicesJson.length() > MAX_DEVICE_COUNT) {
        throw storageError(
          code = "CATALOG_INVALID",
          message = "The saved catalog contains too many local devices.",
        )
      }
      val lanDevices =
        buildList(lanDevicesJson.length()) {
          for (index in 0 until lanDevicesJson.length()) {
            add(lanDevicesJson.getJSONObject(index).toLanDevice())
          }
        }
      val lastLocalPollAt = root.optLong("last_local_poll_at_epoch_ms", 0L).takeIf { it > 0L }
      val localStatusJson = root.optJSONArray("local_status") ?: JSONArray()
      if (localStatusJson.length() > MAX_LOCAL_STATUS_COUNT) {
        throw storageError(
          code = "CATALOG_INVALID",
          message = "The saved catalog contains too many local status records.",
        )
      }
      val localStatus =
        buildList(localStatusJson.length()) {
          for (index in 0 until localStatusJson.length()) {
            add(localStatusJson.getJSONObject(index).toLocalStatus())
          }
        }
      val catalog =
        DeviceCatalog(
          schemaVersion = schemaVersion,
          importedAtEpochMillis = root.getLong("imported_at_epoch_ms"),
          region = region,
          devices = devices,
          lastDiscoveryAtEpochMillis = lastDiscoveryAt,
          lastDiscoveryNetwork = lastDiscoveryNetwork,
          lanDevices = lanDevices,
          lastLocalPollAtEpochMillis = lastLocalPollAt,
          localStatus = localStatus,
        )
      validateCatalog(catalog)
      return catalog
    } catch (error: DeviceCatalogStorageException) {
      throw error
    } catch (_: Exception) {
      throw storageError(
        code = "CATALOG_INVALID",
        message = "The saved device catalog contains invalid data.",
      )
    }
  }

  private fun CloudImportedDevice.toJson(): JSONObject =
    JSONObject().apply {
      put("id", id)
      put("name", name)
      put("local_key", localKey.reveal())
      put("category", category)
      put("product_id", productId)
      put("product_name", productName)
      put("model", model)
      put("mac", mac)
      put("uuid", uuid)
      put("sub_device", isSubDevice)
      put("gateway_id", gatewayId)
      put("node_id", nodeId)
      put("protocol_version", protocolVersion)
      put("last_ip", lastIp)
      put("mapping_json", mappingJson)
    }

  private fun LanDeviceRecord.toJson(): JSONObject =
    JSONObject().apply {
      put("id", id)
      put("ip", ip)
      put("protocol_version", protocolVersion)
      put("product_key", productKey)
      put("mac", mac)
      put("origin", origin)
      put("last_seen_at_epoch_ms", lastSeenAtEpochMillis)
    }

  private fun LanNetworkContext.toJson(): JSONObject =
    JSONObject().apply {
      put("interface_name", interfaceName)
      put("local_ipv4", localIpv4)
      put("prefix_length", prefixLength)
      put("broadcast_ipv4", broadcastIpv4)
      put("network_handle", networkHandle)
    }

  private fun LocalStatusRecord.toJson(): JSONObject =
    JSONObject().apply {
      put("id", id)
      put("state", state.wireValue)
      put("error_code", errorCode)
      put("duration_ms", durationMillis)
      put("polled_at_epoch_ms", polledAtEpochMillis)
      put(
        "data_points",
        JSONArray().apply {
          dataPoints.forEach { dataPoint ->
            put(
              JSONObject().apply {
                put("id", dataPoint.id)
                put("kind", dataPoint.kind.wireValue)
                put("value", dataPoint.value)
              }
            )
          }
        },
      )
    }

  private fun JSONObject.toDevice(): CloudImportedDevice =
    CloudImportedDevice(
      id = getString("id"),
      name = getString("name"),
      localKey = SensitiveString.of(getString("local_key")),
      category = getString("category"),
      productId = getString("product_id"),
      productName = getString("product_name"),
      model = getString("model"),
      mac = getString("mac"),
      uuid = getString("uuid"),
      isSubDevice = getBoolean("sub_device"),
      gatewayId = getString("gateway_id"),
      nodeId = getString("node_id"),
      protocolVersion = getString("protocol_version"),
      lastIp = getString("last_ip"),
      mappingJson = getString("mapping_json"),
    )

  private fun JSONObject.toLanDevice(): LanDeviceRecord =
    LanDeviceRecord(
      id = getString("id"),
      ip = getString("ip"),
      protocolVersion = getString("protocol_version"),
      productKey = getString("product_key"),
      mac = getString("mac"),
      origin = getString("origin"),
      lastSeenAtEpochMillis = getLong("last_seen_at_epoch_ms"),
    )

  private fun JSONObject.toLanNetworkContext(): LanNetworkContext =
    LanNetworkContext(
      interfaceName = getString("interface_name"),
      localIpv4 = getString("local_ipv4"),
      prefixLength = getInt("prefix_length"),
      broadcastIpv4 = getString("broadcast_ipv4"),
      networkHandle = getLong("network_handle"),
    )

  private fun JSONObject.toLocalStatus(): LocalStatusRecord {
    val stateCode = getString("state")
    val state =
      LocalPollDeviceState.entries.firstOrNull { it.wireValue == stateCode }
        ?: throw storageError(
          code = "CATALOG_INVALID",
          message = "The saved catalog contains an unknown local status state.",
        )
    val dataPointsJson = getJSONArray("data_points")
    if (dataPointsJson.length() > MAX_LOCAL_DATA_POINT_COUNT) {
      throw storageError(
        code = "CATALOG_INVALID",
        message = "The saved catalog contains too many local data points.",
      )
    }
    val dataPoints =
      buildList(dataPointsJson.length()) {
        for (index in 0 until dataPointsJson.length()) {
          val dataPoint = dataPointsJson.getJSONObject(index)
          val kindCode = dataPoint.getString("kind")
          val kind =
            LocalDataPointKind.entries.firstOrNull { it.wireValue == kindCode }
              ?: throw storageError(
                code = "CATALOG_INVALID",
                message = "The saved catalog contains an unknown data point type.",
              )
          add(
            LocalDataPoint(
              id = dataPoint.getString("id"),
              kind = kind,
              value = dataPoint.getString("value"),
            )
          )
        }
      }
    return LocalStatusRecord(
      id = getString("id"),
      state = state,
      errorCode = getString("error_code"),
      durationMillis = getLong("duration_ms"),
      dataPoints = dataPoints,
      polledAtEpochMillis = getLong("polled_at_epoch_ms"),
    )
  }

  private fun validateDevices(devices: List<CloudImportedDevice>) {
    if (
      devices.size > MAX_DEVICE_COUNT ||
        devices.any { it.id.isBlank() } ||
        devices.map { it.id }.toSet().size != devices.size
    ) {
      throw storageError(
        code = "CATALOG_INVALID",
        message = "The device catalog contains invalid or duplicate device IDs.",
      )
    }
  }

  private fun validateCatalog(catalog: DeviceCatalog) {
    validateDevices(catalog.devices)
    val knownIds = catalog.devices.mapTo(mutableSetOf()) { it.id }
    if (
      catalog.schemaVersion !in MIN_SUPPORTED_CATALOG_SCHEMA_VERSION..CATALOG_SCHEMA_VERSION ||
        catalog.importedAtEpochMillis <= 0L ||
        catalog.lanDevices.size > MAX_DEVICE_COUNT ||
        catalog.lanDevices.map { it.id }.toSet().size != catalog.lanDevices.size ||
        (catalog.lastDiscoveryAtEpochMillis == null && catalog.lanDevices.isNotEmpty()) ||
        (catalog.lastDiscoveryAtEpochMillis == null && catalog.lastDiscoveryNetwork != null) ||
        catalog.lastDiscoveryAtEpochMillis?.let { it <= 0L || it == Long.MAX_VALUE } == true ||
        catalog.lastDiscoveryNetwork?.let(::invalidDiscoveryNetwork) == true ||
        catalog.lanDevices.any { record ->
          record.id.isBlank() ||
            record.id.length > 128 ||
            !record.ip.isIpv4Address() ||
            record.protocolVersion.length > 128 ||
            record.productKey.length > 128 ||
            record.mac.length > 128 ||
            record.origin != "broadcast" ||
            record.lastSeenAtEpochMillis <= 0L ||
            record.lastSeenAtEpochMillis > (catalog.lastDiscoveryAtEpochMillis ?: 0L)
        } ||
        catalog.localStatus.size > MAX_LOCAL_STATUS_COUNT ||
        catalog.localStatus.map { it.id }.toSet().size != catalog.localStatus.size ||
        (catalog.lastLocalPollAtEpochMillis == null && catalog.localStatus.isNotEmpty()) ||
        catalog.lastLocalPollAtEpochMillis?.let { it <= 0L || it == Long.MAX_VALUE } == true ||
        catalog.localStatus.any { record ->
          record.id !in knownIds ||
            invalidLocalStatusRecord(
              record = record,
              lastLocalPollAtEpochMillis = catalog.lastLocalPollAtEpochMillis ?: 0L,
            )
        }
    ) {
      throw storageError(
        code = "CATALOG_INVALID",
        message = "The device catalog contains invalid local data.",
      )
    }
  }

  private fun validateLocalPollResult(result: LocalPollResult) {
    if (
      result.deviceCount != result.devices.size ||
        result.deviceCount !in 1..MAX_LOCAL_STATUS_COUNT ||
        minOf(
          result.respondedDeviceCount,
          result.offlineDeviceCount,
          result.errorDeviceCount,
        ) < 0 ||
        result.respondedDeviceCount + result.offlineDeviceCount + result.errorDeviceCount !=
          result.deviceCount ||
        result.respondedDeviceCount !=
          result.devices.count { it.state == LocalPollDeviceState.RESPONDED } ||
        result.offlineDeviceCount !=
          result.devices.count { it.state == LocalPollDeviceState.OFFLINE } ||
        result.errorDeviceCount !=
          result.devices.count { it.state == LocalPollDeviceState.ERROR } ||
        result.durationMillis !in 0..MAX_LOCAL_POLL_DURATION_MILLIS ||
        result.devices.map { it.id }.toSet().size != result.devices.size ||
        result.devices.any { device ->
          invalidLocalStatusRecord(
            record =
              LocalStatusRecord(
                id = device.id,
                state = device.state,
                errorCode = device.errorCode,
                durationMillis = device.durationMillis,
                dataPoints = device.dataPoints,
                polledAtEpochMillis = 1L,
              ),
            lastLocalPollAtEpochMillis = 1L,
          )
        }
    ) {
      throw storageError(
        code = "CATALOG_INVALID",
        message = "The local status result contains invalid devices.",
      )
    }
  }

  private fun invalidLocalStatusRecord(
    record: LocalStatusRecord,
    lastLocalPollAtEpochMillis: Long,
  ): Boolean =
    record.id.isBlank() ||
      record.id.length > 128 ||
      record.errorCode.length > 64 ||
      record.durationMillis !in 0..MAX_SINGLE_LOCAL_POLL_DURATION_MILLIS ||
      record.polledAtEpochMillis <= 0L ||
      record.polledAtEpochMillis > lastLocalPollAtEpochMillis ||
      (record.state == LocalPollDeviceState.RESPONDED && record.errorCode.isNotEmpty()) ||
      (record.state != LocalPollDeviceState.RESPONDED && record.errorCode.isBlank()) ||
      (record.state != LocalPollDeviceState.RESPONDED && record.dataPoints.isNotEmpty()) ||
      record.dataPoints.size > MAX_LOCAL_DATA_POINT_COUNT ||
      record.dataPoints.map { it.id }.toSet().size != record.dataPoints.size ||
      record.dataPoints.any { dataPoint ->
        dataPoint.id.isBlank() ||
          dataPoint.id.length > 8 ||
          dataPoint.id.any { !it.isDigit() } ||
          dataPoint.value.length > MAX_LOCAL_DATA_POINT_VALUE_LENGTH
      }

  private fun validateLanResult(result: LanDiscoveryResult) {
    if (
      result.deviceCount != result.devices.size ||
        result.deviceCount > MAX_DEVICE_COUNT ||
        result.matchedDeviceCount < 0 ||
        result.unmatchedDeviceCount < 0 ||
        result.matchedDeviceCount + result.unmatchedDeviceCount != result.deviceCount ||
        result.devices.map { it.id }.toSet().size != result.devices.size ||
        result.devices.any { device ->
          device.id.isBlank() ||
            device.id.length > 128 ||
            !device.ip.isIpv4Address() ||
            device.protocolVersion.length > 128 ||
            device.productKey.length > 128 ||
            device.mac.length > 128 ||
            device.origin != "broadcast"
        }
    ) {
      throw storageError(
        code = "CATALOG_INVALID",
        message = "The local discovery result contains invalid devices.",
      )
    }
  }

  private fun validateDiscoveryNetwork(network: LanNetworkContext) {
    if (invalidDiscoveryNetwork(network)) {
      throw storageError(
        code = "CATALOG_INVALID",
        message = "The local discovery result contains an invalid network identity.",
      )
    }
  }

  private fun invalidDiscoveryNetwork(network: LanNetworkContext): Boolean =
    network.interfaceName.isBlank() ||
      network.interfaceName.length > 128 ||
      !network.localIpv4.isIpv4Address() ||
      network.prefixLength !in 1..30 ||
      !network.broadcastIpv4.isIpv4Address() ||
      network.networkHandle <= LanNetworkContext.UNKNOWN_NETWORK_HANDLE

  private fun String.isIpv4Address(): Boolean {
    val parts = split('.')
    return parts.size == 4 &&
      parts.all { part ->
        part.isNotEmpty() &&
          part.length <= 3 &&
          part.all(Char::isDigit) &&
          part.toIntOrNull() in 0..255
      }
  }

  private fun storageError(code: String, message: String) =
    DeviceCatalogStorageException(code = code, message = message)

  private companion object {
    const val CATALOG_DIRECTORY = "device_catalog"
    const val CATALOG_FILE = "catalog.json"
    const val MIN_SUPPORTED_CATALOG_SCHEMA_VERSION = 1
    const val CATALOG_SCHEMA_VERSION = 4
    const val MAX_CATALOG_BYTES = 16L * 1024L * 1024L
    const val MAX_DEVICE_COUNT = 1_000
    const val MAX_LOCAL_STATUS_COUNT = 32
    const val MAX_LOCAL_DATA_POINT_COUNT = 256
    const val MAX_LOCAL_DATA_POINT_VALUE_LENGTH = 8192
    const val MAX_SINGLE_LOCAL_POLL_DURATION_MILLIS = 30_000L
    const val MAX_LOCAL_POLL_DURATION_MILLIS = 120_000L
  }
}
