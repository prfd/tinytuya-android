package com.prfd.tinytuya.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.prfd.tinytuya.data.lan.LanDiscoveryResult
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
    val lanDevices: List<LanDeviceRecord> = emptyList(),
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

class DeviceCatalogStorageException(
    val code: String,
    message: String,
) : IllegalStateException(message)

interface DeviceCatalogStore {
    suspend fun load(): DeviceCatalog?

    suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog

    suspend fun mergeLanDiscovery(result: LanDiscoveryResult): DeviceCatalog

    suspend fun deleteAll()
}

/**
 * Stores the entire imported catalog as one authenticated ciphertext.
 *
 * The ciphertext lives under [Context.getNoBackupFilesDir], while the AES key
 * is non-exportable and generated inside Android Keystore. No cloud credential
 * is accepted by this API, so it cannot be retained accidentally.
 */
class EncryptedDeviceCatalogStore internal constructor(
    context: Context,
    private val catalogDirectory: File = File(context.noBackupFilesDir, CATALOG_DIRECTORY),
    internal val keyAlias: String = KEY_ALIAS,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : DeviceCatalogStore {
    internal val catalogFile = File(catalogDirectory, CATALOG_FILE)

    private val atomicFile = AtomicFile(catalogFile)
    private val mutex = Mutex()

    override suspend fun load(): DeviceCatalog? = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    override suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                validateDevices(result.devices)
                val previous = loadLocked()
                val catalog = DeviceCatalog(
                    schemaVersion = CATALOG_SCHEMA_VERSION,
                    importedAtEpochMillis = currentTimeMillis(),
                    region = result.region,
                    devices = result.devices,
                    lastDiscoveryAtEpochMillis = previous?.lastDiscoveryAtEpochMillis,
                    lanDevices = previous?.lanDevices.orEmpty(),
                )
                writeCatalogLocked(catalog)
                catalog
            }
        }

    override suspend fun mergeLanDiscovery(result: LanDiscoveryResult): DeviceCatalog =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                validateLanResult(result)
                val previous = loadLocked() ?: throw storageError(
                    code = "CATALOG_MISSING",
                    message = "Import devices before saving local discovery results.",
                )
                val previousDiscovery = previous.lastDiscoveryAtEpochMillis
                val discoveredAt = previousDiscovery?.let { prior ->
                    maxOf(currentTimeMillis(), prior + 1)
                } ?: currentTimeMillis()
                val discoveredRecords = result.devices.associate { device ->
                    device.id to LanDeviceRecord(
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
                val retainedKnownRecords = previous.lanDevices.filter { record ->
                    record.id in knownIds && record.id !in discoveredRecords
                }
                val catalog = previous.copy(
                    schemaVersion = CATALOG_SCHEMA_VERSION,
                    lastDiscoveryAtEpochMillis = discoveredAt,
                    lanDevices = (discoveredRecords.values + retainedKnownRecords)
                        .sortedBy { it.id },
                )
                writeCatalogLocked(catalog)
                catalog
            }
        }

    override suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                atomicFile.delete()
                keyStore().let { store ->
                    if (store.containsAlias(keyAlias)) {
                        store.deleteEntry(keyAlias)
                    }
                }
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
            val input = try {
                atomicFile.openRead()
            } catch (_: FileNotFoundException) {
                return null
            }
            val encrypted = input.use {
                if (it.channel.size() !in 1..MAX_CATALOG_BYTES) {
                    throw storageError(
                        code = "CATALOG_INVALID",
                        message = "The saved device catalog has an invalid size.",
                    )
                }
                it.readBytes()
            }
            val plaintext = decrypt(encrypted)
            return try {
                decodeCatalog(String(plaintext, StandardCharsets.UTF_8))
            } finally {
                plaintext.fill(0)
            }
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
        val plaintext = encodeCatalog(catalog).toByteArray(StandardCharsets.UTF_8)
        try {
            val encrypted = encrypt(plaintext)
            if (encrypted.size.toLong() > MAX_CATALOG_BYTES) {
                throw storageError(
                    code = "CATALOG_TOO_LARGE",
                    message = "The device catalog is too large to store safely.",
                )
            }
            writeAtomically(encrypted)
        } catch (error: DeviceCatalogStorageException) {
            throw error
        } catch (_: Exception) {
            throw storageError(
                code = "CATALOG_WRITE_FAILED",
                message = "The device catalog could not be stored safely.",
            )
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyForEncryption())
            cipher.updateAAD(ASSOCIATED_DATA)
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            if (iv.size !in MIN_IV_BYTES..MAX_IV_BYTES) {
                throw storageError(
                    code = "CATALOG_ENCRYPT_FAILED",
                    message = "Android Keystore returned an invalid encryption nonce.",
                )
            }

            return ByteBuffer.allocate(
                MAGIC.size + 1 + 1 + iv.size + ciphertext.size
            ).apply {
                put(MAGIC)
                put(ENVELOPE_VERSION.toByte())
                put(iv.size.toByte())
                put(iv)
                put(ciphertext)
            }.array()
        } catch (error: DeviceCatalogStorageException) {
            throw error
        } catch (_: Exception) {
            throw storageError(
                code = "CATALOG_ENCRYPT_FAILED",
                message = "Android Keystore could not encrypt the device catalog.",
            )
        }
    }

    private fun decrypt(envelope: ByteArray): ByteArray {
        try {
            val minimumSize = MAGIC.size + 1 + 1 + MIN_IV_BYTES + GCM_TAG_BYTES
            if (envelope.size < minimumSize) {
                throw storageError(
                    code = "CATALOG_INVALID",
                    message = "The saved device catalog is incomplete.",
                )
            }

            val buffer = ByteBuffer.wrap(envelope)
            val magic = ByteArray(MAGIC.size).also(buffer::get)
            if (!magic.contentEquals(MAGIC)) {
                throw storageError(
                    code = "CATALOG_INVALID",
                    message = "The saved device catalog uses an unknown format.",
                )
            }

            val envelopeVersion = buffer.get().toInt() and 0xff
            if (envelopeVersion != ENVELOPE_VERSION) {
                throw storageError(
                    code = "CATALOG_VERSION_UNSUPPORTED",
                    message = "The saved device catalog uses an unsupported encryption format.",
                )
            }

            val ivSize = buffer.get().toInt() and 0xff
            if (ivSize !in MIN_IV_BYTES..MAX_IV_BYTES || buffer.remaining() < ivSize + GCM_TAG_BYTES) {
                throw storageError(
                    code = "CATALOG_INVALID",
                    message = "The saved device catalog contains an invalid encryption nonce.",
                )
            }

            val iv = ByteArray(ivSize).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val key = existingKey() ?: throw storageError(
                code = "CATALOG_KEY_UNAVAILABLE",
                message = "The key for the saved device catalog is no longer available.",
            )
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(ASSOCIATED_DATA)
            return cipher.doFinal(ciphertext)
        } catch (error: DeviceCatalogStorageException) {
            throw error
        } catch (_: Exception) {
            throw storageError(
                code = "CATALOG_DECRYPT_FAILED",
                message = "The saved device catalog failed its integrity check.",
            )
        }
    }

    private fun writeAtomically(encrypted: ByteArray) {
        if (!catalogDirectory.exists() && !catalogDirectory.mkdirs()) {
            throw storageError(
                code = "CATALOG_WRITE_FAILED",
                message = "The private device-storage directory could not be created.",
            )
        }

        var output = atomicFile.startWrite()
        try {
            output.write(encrypted)
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

    private fun keyForEncryption(): SecretKey = existingKey() ?: try {
        KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    } catch (_: Exception) {
        throw storageError(
            code = "CATALOG_KEY_CREATE_FAILED",
            message = "Android Keystore could not create the device-catalog key.",
        )
    }

    private fun existingKey(): SecretKey? = try {
        keyStore().getKey(keyAlias, null) as? SecretKey
    } catch (_: Exception) {
        throw storageError(
            code = "CATALOG_KEY_UNAVAILABLE",
            message = "Android Keystore could not open the device-catalog key.",
        )
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private fun encodeCatalog(catalog: DeviceCatalog): String = JSONObject().apply {
        put("schema_version", catalog.schemaVersion)
        put("imported_at_epoch_ms", catalog.importedAtEpochMillis)
        put("region", catalog.region.apiCode)
        put(
            "devices",
            JSONArray().apply {
                catalog.devices.forEach { device -> put(device.toJson()) }
            }
        )
        catalog.lastDiscoveryAtEpochMillis?.let { put("last_discovery_at_epoch_ms", it) }
        put(
            "lan_devices",
            JSONArray().apply {
                catalog.lanDevices.forEach { device -> put(device.toJson()) }
            }
        )
    }.toString()

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
            val region = TuyaCloudRegion.entries.firstOrNull { it.apiCode == regionCode }
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

            val devices = buildList(devicesJson.length()) {
                for (index in 0 until devicesJson.length()) {
                    add(devicesJson.getJSONObject(index).toDevice())
                }
            }
            val lastDiscoveryAt = root.optLong("last_discovery_at_epoch_ms", 0L)
                .takeIf { it > 0L }
            val lanDevicesJson = root.optJSONArray("lan_devices") ?: JSONArray()
            if (lanDevicesJson.length() > MAX_DEVICE_COUNT) {
                throw storageError(
                    code = "CATALOG_INVALID",
                    message = "The saved catalog contains too many local devices.",
                )
            }
            val lanDevices = buildList(lanDevicesJson.length()) {
                for (index in 0 until lanDevicesJson.length()) {
                    add(lanDevicesJson.getJSONObject(index).toLanDevice())
                }
            }
            val catalog = DeviceCatalog(
                schemaVersion = schemaVersion,
                importedAtEpochMillis = root.getLong("imported_at_epoch_ms"),
                region = region,
                devices = devices,
                lastDiscoveryAtEpochMillis = lastDiscoveryAt,
                lanDevices = lanDevices,
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

    private fun CloudImportedDevice.toJson(): JSONObject = JSONObject().apply {
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

    private fun LanDeviceRecord.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("ip", ip)
        put("protocol_version", protocolVersion)
        put("product_key", productKey)
        put("mac", mac)
        put("origin", origin)
        put("last_seen_at_epoch_ms", lastSeenAtEpochMillis)
    }

    private fun JSONObject.toDevice(): CloudImportedDevice = CloudImportedDevice(
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

    private fun JSONObject.toLanDevice(): LanDeviceRecord = LanDeviceRecord(
        id = getString("id"),
        ip = getString("ip"),
        protocolVersion = getString("protocol_version"),
        productKey = getString("product_key"),
        mac = getString("mac"),
        origin = getString("origin"),
        lastSeenAtEpochMillis = getLong("last_seen_at_epoch_ms"),
    )

    private fun validateDevices(devices: List<CloudImportedDevice>) {
        if (devices.size > MAX_DEVICE_COUNT ||
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
        if (
            catalog.schemaVersion !in MIN_SUPPORTED_CATALOG_SCHEMA_VERSION..CATALOG_SCHEMA_VERSION ||
            catalog.importedAtEpochMillis <= 0L ||
            catalog.lanDevices.size > MAX_DEVICE_COUNT ||
            catalog.lanDevices.map { it.id }.toSet().size != catalog.lanDevices.size ||
            (catalog.lastDiscoveryAtEpochMillis == null && catalog.lanDevices.isNotEmpty()) ||
            catalog.lastDiscoveryAtEpochMillis?.let { it <= 0L || it == Long.MAX_VALUE } == true ||
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
            }
        ) {
            throw storageError(
                code = "CATALOG_INVALID",
                message = "The device catalog contains invalid local discovery data.",
            )
        }
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

    private fun String.isIpv4Address(): Boolean {
        val parts = split('.')
        return parts.size == 4 && parts.all { part ->
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
        const val CATALOG_FILE = "catalog.v1.enc"
        const val MIN_SUPPORTED_CATALOG_SCHEMA_VERSION = 1
        const val CATALOG_SCHEMA_VERSION = 2
        const val ENVELOPE_VERSION = 1
        const val MAX_CATALOG_BYTES = 16L * 1024L * 1024L
        const val MAX_DEVICE_COUNT = 1_000
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 32
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val KEY_SIZE_BITS = 256
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "com.prfd.tinytuya.device_catalog.v1"
        val MAGIC = byteArrayOf(0x54, 0x54, 0x59, 0x41)
        val ASSOCIATED_DATA =
            "TinyTuyaAndroid:device-catalog:v1".toByteArray(StandardCharsets.UTF_8)
    }
}
