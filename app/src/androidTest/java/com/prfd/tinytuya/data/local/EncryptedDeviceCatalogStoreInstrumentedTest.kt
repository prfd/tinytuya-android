package com.prfd.tinytuya.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedDeviceCatalogStoreInstrumentedTest {
    private lateinit var catalogDirectory: File
    private lateinit var keyAlias: String
    private lateinit var store: EncryptedDeviceCatalogStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testId = UUID.randomUUID().toString()
        catalogDirectory = File(context.noBackupFilesDir, "catalog-test-$testId")
        keyAlias = "com.prfd.tinytuya.test.catalog.$testId"
        store = EncryptedDeviceCatalogStore(
            context = context,
            catalogDirectory = catalogDirectory,
            keyAlias = keyAlias,
            currentTimeMillis = { FIXED_IMPORT_TIME },
        )
    }

    @After
    fun tearDown() = runBlocking {
        store.deleteAll()
    }

    @Test
    fun roundTripKeepsAllDeviceDataInsideCiphertext() = runBlocking {
        val result = sampleImport()

        val saved = store.replaceFromCloud(result)
        val rawBytes = store.catalogFile.readBytes()
        val loaded = store.load()

        assertEquals(FIXED_IMPORT_TIME, saved.importedAtEpochMillis)
        assertTrue(
            store.catalogFile.canonicalPath.startsWith(
                InstrumentationRegistry.getInstrumentation()
                    .targetContext.noBackupFilesDir.canonicalPath
            )
        )
        assertFalse(rawBytes.containsSequence(DEVICE_ID.toByteArray()))
        assertFalse(rawBytes.containsSequence(DEVICE_NAME.toByteArray()))
        assertFalse(rawBytes.containsSequence(LOCAL_KEY.toByteArray()))
        assertNotNull(loaded)
        assertEquals(TuyaCloudRegion.CENTRAL_EUROPE, loaded?.region)
        assertEquals(1, loaded?.devices?.size)
        assertEquals(DEVICE_ID, loaded?.devices?.single()?.id)
        assertEquals(LOCAL_KEY, loaded?.devices?.single()?.localKey?.reveal())
        assertEquals("3.5", loaded?.devices?.single()?.protocolVersion)
    }

    @Test
    fun authenticatedEncryptionRejectsModifiedCiphertext() = runBlocking {
        store.replaceFromCloud(sampleImport())
        val bytes = store.catalogFile.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        store.catalogFile.writeBytes(bytes)

        try {
            store.load()
            throw AssertionError("Expected modified ciphertext to be rejected")
        } catch (error: DeviceCatalogStorageException) {
            assertEquals("CATALOG_DECRYPT_FAILED", error.code)
        }
    }

    @Test
    fun deleteAllRemovesCiphertextAndKeystoreEntry() = runBlocking {
        store.replaceFromCloud(sampleImport())
        assertTrue(store.catalogFile.exists())
        assertTrue(androidKeyStore().containsAlias(keyAlias))

        store.deleteAll()

        assertFalse(store.catalogFile.exists())
        assertFalse(androidKeyStore().containsAlias(keyAlias))
        assertNull(store.load())
    }

    private fun sampleImport() = CloudImportResult(
        contractVersion = 1,
        region = TuyaCloudRegion.CENTRAL_EUROPE,
        deviceCount = 1,
        missingLocalKeyCount = 0,
        warnings = emptyList(),
        devices = listOf(
            CloudImportedDevice(
                id = DEVICE_ID,
                name = DEVICE_NAME,
                localKey = SensitiveString.of(LOCAL_KEY),
                category = "dj",
                productId = "product-id",
                productName = "Table lamp",
                model = "TL-1",
                mac = "AA:BB:CC:DD:EE:FF",
                uuid = "device-uuid",
                isSubDevice = false,
                gatewayId = "",
                nodeId = "",
                protocolVersion = "3.5",
                lastIp = "192.0.2.14",
                mappingJson = "{\"1\":{\"code\":\"switch_led\"}}",
            )
        ),
    )

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return (0..size - needle.size).any { offset ->
            needle.indices.all { index -> this[offset + index] == needle[index] }
        }
    }

    private companion object {
        const val FIXED_IMPORT_TIME = 1_753_981_200_000L
        const val DEVICE_ID = "encrypted-device-id"
        const val DEVICE_NAME = "Encrypted bedroom lamp"
        const val LOCAL_KEY = "private-local-key-value"
    }
}
