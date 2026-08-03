package com.prfd.tinytuya.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
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
import org.json.JSONObject

class StoredCloudCredentials(
    val region: TuyaCloudRegion,
    val clientId: SensitiveString,
    val clientSecret: SensitiveString,
    val savedAtEpochMillis: Long,
) {
    override fun toString(): String =
        "StoredCloudCredentials(region=$region, clientId=[REDACTED], " +
            "clientSecret=[REDACTED], savedAtEpochMillis=$savedAtEpochMillis)"
}

data class CloudCredentialSummary(
    val region: TuyaCloudRegion,
    val maskedClientId: String,
    val savedAtEpochMillis: Long,
)

class CloudCredentialStorageException(
    val code: String,
    message: String,
) : IllegalStateException(message)

interface CloudCredentialStore {
    suspend fun load(): StoredCloudCredentials?

    suspend fun loadSummary(): CloudCredentialSummary?

    suspend fun save(
        region: TuyaCloudRegion,
        clientId: SensitiveString,
        clientSecret: SensitiveString,
    ): CloudCredentialSummary

    suspend fun deleteAll()
}

/**
 * Stores Tuya Cloud credentials separately from the device catalog.
 *
 * The authenticated ciphertext is excluded from backup by living under
 * [Context.getNoBackupFilesDir]. Its non-exportable Android Keystore key has a
 * dedicated alias, allowing credentials to be forgotten without deleting the
 * locally usable device catalog.
 */
class EncryptedCloudCredentialStore internal constructor(
    context: Context,
    private val vaultDirectory: File = File(context.noBackupFilesDir, VAULT_DIRECTORY),
    internal val keyAlias: String = KEY_ALIAS,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : CloudCredentialStore {
    internal val vaultFile = File(vaultDirectory, VAULT_FILE)

    private val atomicFile = AtomicFile(vaultFile)
    private val mutex = Mutex()

    override suspend fun load(): StoredCloudCredentials? = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    override suspend fun loadSummary(): CloudCredentialSummary? = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked()?.toSummary() }
    }

    override suspend fun save(
        region: TuyaCloudRegion,
        clientId: SensitiveString,
        clientSecret: SensitiveString,
    ): CloudCredentialSummary = withContext(Dispatchers.IO) {
        mutex.withLock {
            val credentials = normalizeAndValidate(
                StoredCloudCredentials(
                    region = region,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    savedAtEpochMillis = currentTimeMillis(),
                )
            )
            writeLocked(credentials)
            credentials.toSummary()
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
                vaultDirectory.delete()
                Unit
            } catch (_: Exception) {
                throw vaultError(
                    code = "CREDENTIAL_VAULT_DELETE_FAILED",
                    message = "The saved Tuya Cloud credentials could not be deleted.",
                )
            }
        }
    }

    private fun loadLocked(): StoredCloudCredentials? {
        try {
            val input = try {
                atomicFile.openRead()
            } catch (_: FileNotFoundException) {
                return null
            }
            val envelope = input.use {
                if (it.channel.size() !in 1..MAX_VAULT_BYTES) {
                    throw vaultError(
                        code = "CREDENTIAL_VAULT_INVALID",
                        message = "The saved credential vault has an invalid size.",
                    )
                }
                it.readBytes()
            }
            val plaintext = decrypt(envelope)
            return try {
                decode(String(plaintext, StandardCharsets.UTF_8))
            } finally {
                plaintext.fill(0)
            }
        } catch (error: CloudCredentialStorageException) {
            throw error
        } catch (_: Exception) {
            throw vaultError(
                code = "CREDENTIAL_VAULT_READ_FAILED",
                message = "The saved Tuya Cloud credentials could not be read safely.",
            )
        }
    }

    private fun writeLocked(credentials: StoredCloudCredentials) {
        val plaintext = encode(credentials).toByteArray(StandardCharsets.UTF_8)
        try {
            val envelope = encrypt(plaintext)
            if (envelope.size.toLong() > MAX_VAULT_BYTES) {
                throw vaultError(
                    code = "CREDENTIAL_VAULT_TOO_LARGE",
                    message = "The Tuya Cloud credentials are too large to store safely.",
                )
            }
            writeAtomically(envelope)
        } catch (error: CloudCredentialStorageException) {
            throw error
        } catch (_: Exception) {
            throw vaultError(
                code = "CREDENTIAL_VAULT_WRITE_FAILED",
                message = "The Tuya Cloud credentials could not be stored safely.",
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
                throw vaultError(
                    code = "CREDENTIAL_VAULT_ENCRYPT_FAILED",
                    message = "Android Keystore returned an invalid credential-vault nonce.",
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
        } catch (error: CloudCredentialStorageException) {
            throw error
        } catch (_: Exception) {
            throw vaultError(
                code = "CREDENTIAL_VAULT_ENCRYPT_FAILED",
                message = "Android Keystore could not encrypt the Tuya Cloud credentials.",
            )
        }
    }

    private fun decrypt(envelope: ByteArray): ByteArray {
        try {
            val minimumSize = MAGIC.size + 1 + 1 + MIN_IV_BYTES + GCM_TAG_BYTES
            if (envelope.size < minimumSize) {
                throw vaultError(
                    code = "CREDENTIAL_VAULT_INVALID",
                    message = "The saved credential vault is incomplete.",
                )
            }
            val buffer = ByteBuffer.wrap(envelope)
            val magic = ByteArray(MAGIC.size).also(buffer::get)
            if (!magic.contentEquals(MAGIC)) {
                throw vaultError(
                    code = "CREDENTIAL_VAULT_INVALID",
                    message = "The saved credential vault uses an unknown format.",
                )
            }
            if ((buffer.get().toInt() and 0xff) != ENVELOPE_VERSION) {
                throw vaultError(
                    code = "CREDENTIAL_VAULT_VERSION_UNSUPPORTED",
                    message = "The saved credential vault uses an unsupported format.",
                )
            }
            val ivSize = buffer.get().toInt() and 0xff
            if (ivSize !in MIN_IV_BYTES..MAX_IV_BYTES || buffer.remaining() < ivSize + GCM_TAG_BYTES) {
                throw vaultError(
                    code = "CREDENTIAL_VAULT_INVALID",
                    message = "The saved credential vault contains an invalid nonce.",
                )
            }
            val iv = ByteArray(ivSize).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val key = existingKey() ?: throw vaultError(
                code = "CREDENTIAL_VAULT_KEY_UNAVAILABLE",
                message = "The key for the saved Tuya Cloud credentials is unavailable.",
            )
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(ASSOCIATED_DATA)
            return cipher.doFinal(ciphertext)
        } catch (error: CloudCredentialStorageException) {
            throw error
        } catch (_: Exception) {
            throw vaultError(
                code = "CREDENTIAL_VAULT_DECRYPT_FAILED",
                message = "The saved credential vault failed its integrity check.",
            )
        }
    }

    private fun writeAtomically(envelope: ByteArray) {
        if (!vaultDirectory.exists() && !vaultDirectory.mkdirs()) {
            throw vaultError(
                code = "CREDENTIAL_VAULT_WRITE_FAILED",
                message = "The private credential-storage directory could not be created.",
            )
        }
        val output = atomicFile.startWrite()
        try {
            output.write(envelope)
            output.flush()
            atomicFile.finishWrite(output)
        } catch (_: Exception) {
            atomicFile.failWrite(output)
            throw vaultError(
                code = "CREDENTIAL_VAULT_WRITE_FAILED",
                message = "The Tuya Cloud credentials could not be stored atomically.",
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
        throw vaultError(
            code = "CREDENTIAL_VAULT_KEY_CREATE_FAILED",
            message = "Android Keystore could not create the credential-vault key.",
        )
    }

    private fun existingKey(): SecretKey? = try {
        keyStore().getKey(keyAlias, null) as? SecretKey
    } catch (_: Exception) {
        throw vaultError(
            code = "CREDENTIAL_VAULT_KEY_UNAVAILABLE",
            message = "Android Keystore could not open the credential-vault key.",
        )
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private fun encode(credentials: StoredCloudCredentials): String = JSONObject().apply {
        put("schema_version", VAULT_SCHEMA_VERSION)
        put("region", credentials.region.apiCode)
        put("client_id", credentials.clientId.reveal())
        put("client_secret", credentials.clientSecret.reveal())
        put("saved_at_epoch_ms", credentials.savedAtEpochMillis)
    }.toString()

    private fun decode(json: String): StoredCloudCredentials {
        try {
            val root = JSONObject(json)
            if (root.getInt("schema_version") != VAULT_SCHEMA_VERSION) {
                throw vaultError(
                    code = "CREDENTIAL_VAULT_SCHEMA_UNSUPPORTED",
                    message = "The saved credentials use an unsupported data schema.",
                )
            }
            val regionCode = root.getString("region")
            val region = TuyaCloudRegion.entries.firstOrNull { it.apiCode == regionCode }
                ?: throw vaultError(
                    code = "CREDENTIAL_VAULT_INVALID",
                    message = "The saved credentials contain an unknown cloud region.",
                )
            return normalizeAndValidate(
                StoredCloudCredentials(
                    region = region,
                    clientId = SensitiveString.of(root.getString("client_id")),
                    clientSecret = SensitiveString.of(root.getString("client_secret")),
                    savedAtEpochMillis = root.getLong("saved_at_epoch_ms"),
                )
            )
        } catch (error: CloudCredentialStorageException) {
            throw error
        } catch (_: Exception) {
            throw vaultError(
                code = "CREDENTIAL_VAULT_INVALID",
                message = "The saved credential vault contains invalid data.",
            )
        }
    }

    private fun normalizeAndValidate(
        credentials: StoredCloudCredentials,
    ): StoredCloudCredentials {
        val clientId = credentials.clientId.reveal().trim()
        val clientSecret = credentials.clientSecret.reveal().trim()
        if (
            clientId.isEmpty() || clientId.length > MAX_CLIENT_ID_CHARS ||
            clientSecret.isEmpty() || clientSecret.length > MAX_CLIENT_SECRET_CHARS ||
            credentials.savedAtEpochMillis <= 0L
        ) {
            throw vaultError(
                code = "CREDENTIAL_VAULT_INVALID",
                message = "The Tuya Cloud credentials are incomplete or invalid.",
            )
        }
        return StoredCloudCredentials(
            region = credentials.region,
            clientId = SensitiveString.of(clientId),
            clientSecret = SensitiveString.of(clientSecret),
            savedAtEpochMillis = credentials.savedAtEpochMillis,
        )
    }

    private fun StoredCloudCredentials.toSummary() = CloudCredentialSummary(
        region = region,
        maskedClientId = maskClientId(clientId.reveal()),
        savedAtEpochMillis = savedAtEpochMillis,
    )

    private fun vaultError(code: String, message: String) =
        CloudCredentialStorageException(code = code, message = message)

    private companion object {
        const val VAULT_DIRECTORY = "cloud-credential-vault"
        const val VAULT_FILE = "credentials.enc"
        const val KEY_ALIAS = "com.prfd.tinytuya.cloud_credentials.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 32
        const val ENVELOPE_VERSION = 1
        const val VAULT_SCHEMA_VERSION = 1
        const val MAX_CLIENT_ID_CHARS = 256
        const val MAX_CLIENT_SECRET_CHARS = 512
        const val MAX_VAULT_BYTES = 16L * 1024L
        val MAGIC = byteArrayOf(0x54, 0x54, 0x43, 0x56)
        val ASSOCIATED_DATA = "tinytuya-cloud-credential-vault-v1"
            .toByteArray(StandardCharsets.UTF_8)
    }
}

class InMemoryCloudCredentialStore(
    initial: StoredCloudCredentials? = null,
) : CloudCredentialStore {
    private var credentials = initial

    override suspend fun load(): StoredCloudCredentials? = credentials

    override suspend fun loadSummary(): CloudCredentialSummary? = credentials?.let {
        CloudCredentialSummary(
            region = it.region,
            maskedClientId = maskClientId(it.clientId.reveal()),
            savedAtEpochMillis = it.savedAtEpochMillis,
        )
    }

    override suspend fun save(
        region: TuyaCloudRegion,
        clientId: SensitiveString,
        clientSecret: SensitiveString,
    ): CloudCredentialSummary {
        val saved = StoredCloudCredentials(
            region = region,
            clientId = SensitiveString.of(clientId.reveal().trim()),
            clientSecret = SensitiveString.of(clientSecret.reveal().trim()),
            savedAtEpochMillis = System.currentTimeMillis().coerceAtLeast(1L),
        )
        credentials = saved
        return requireNotNull(loadSummary())
    }

    override suspend fun deleteAll() {
        credentials = null
    }
}

internal fun maskClientId(clientId: String): String {
    val normalized = clientId.trim()
    return when {
        normalized.length >= 12 -> normalized.take(4) + "••••" + normalized.takeLast(4)
        normalized.length >= 6 -> normalized.take(2) + "••••" + normalized.takeLast(2)
        else -> "••••"
    }
}
