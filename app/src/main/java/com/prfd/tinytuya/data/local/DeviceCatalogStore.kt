package com.prfd.tinytuya.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import java.io.File
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
)

class DeviceCatalogStorageException(
    val code: String,
    message: String,
) : IllegalStateException(message)

interface DeviceCatalogStore {
    suspend fun load(): DeviceCatalog?

    suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog

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
        mutex.withLock {
            if (!catalogFile.exists()) return@withLock null
            try {
                if (catalogFile.length() !in 1..MAX_CATALOG_BYTES) {
                    throw storageError(
                        code = "CATALOG_INVALID",
                        message = "The saved device catalog has an invalid size.",
                    )
                }

                val encrypted = atomicFile.openRead().use { it.readBytes() }
                val plaintext = decrypt(encrypted)
                try {
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
    }

    override suspend fun replaceFromCloud(result: CloudImportResult): DeviceCatalog =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val catalog = DeviceCatalog(
                    schemaVersion = CATALOG_SCHEMA_VERSION,
                    importedAtEpochMillis = currentTimeMillis(),
                    region = result.region,
                    devices = result.devices,
                )
                val plaintext = encodeCatalog(catalog).toByteArray(StandardCharsets.UTF_8)
                try {
                    writeAtomically(encrypt(plaintext))
                    catalog
                } catch (error: DeviceCatalogStorageException) {
                    throw error
                } catch (_: Exception) {
                    throw storageError(
                        code = "CATALOG_WRITE_FAILED",
                        message = "The imported devices could not be stored safely.",
                    )
                } finally {
                    plaintext.fill(0)
                }
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
    }.toString()

    private fun decodeCatalog(json: String): DeviceCatalog {
        try {
            val root = JSONObject(json)
            val schemaVersion = root.getInt("schema_version")
            if (schemaVersion != CATALOG_SCHEMA_VERSION) {
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
            return DeviceCatalog(
                schemaVersion = schemaVersion,
                importedAtEpochMillis = root.getLong("imported_at_epoch_ms"),
                region = region,
                devices = devices,
            )
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

    private fun storageError(code: String, message: String) =
        DeviceCatalogStorageException(code = code, message = message)

    private companion object {
        const val CATALOG_DIRECTORY = "device_catalog"
        const val CATALOG_FILE = "catalog.v1.enc"
        const val CATALOG_SCHEMA_VERSION = 1
        const val ENVELOPE_VERSION = 1
        const val MAX_CATALOG_BYTES = 16L * 1024L * 1024L
        const val MAX_DEVICE_COUNT = 4_096
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
