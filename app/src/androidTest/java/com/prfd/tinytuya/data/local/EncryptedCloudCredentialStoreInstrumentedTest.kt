package com.prfd.tinytuya.data.local

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedCloudCredentialStoreInstrumentedTest {
    private lateinit var vaultDirectory: File
    private lateinit var keyAlias: String
    private lateinit var store: EncryptedCloudCredentialStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testId = UUID.randomUUID().toString()
        vaultDirectory = File(context.noBackupFilesDir, "credential-test-$testId")
        keyAlias = "com.prfd.tinytuya.test.credentials.$testId"
        store = EncryptedCloudCredentialStore(
            context = context,
            vaultDirectory = vaultDirectory,
            keyAlias = keyAlias,
            currentTimeMillis = { FIXED_TIME },
        )
    }

    @After
    fun tearDown() = runBlocking {
        store.deleteAll()
    }

    @Test
    fun roundTripKeepsCredentialsInsideNoBackupCiphertext() = runBlocking {
        val summary = store.save(
            region = TuyaCloudRegion.CENTRAL_EUROPE,
            clientId = SensitiveString.of(CLIENT_ID),
            clientSecret = SensitiveString.of(CLIENT_SECRET),
        )

        val rawBytes = store.vaultFile.readBytes()
        val loaded = requireNotNull(store.load())

        assertTrue(
            store.vaultFile.canonicalPath.startsWith(
                InstrumentationRegistry.getInstrumentation()
                    .targetContext.noBackupFilesDir.canonicalPath
            )
        )
        assertFalse(rawBytes.containsSequence(CLIENT_ID.toByteArray()))
        assertFalse(rawBytes.containsSequence(CLIENT_SECRET.toByteArray()))
        assertEquals(TuyaCloudRegion.CENTRAL_EUROPE, loaded.region)
        assertEquals(CLIENT_ID, loaded.clientId.reveal())
        assertEquals(CLIENT_SECRET, loaded.clientSecret.reveal())
        assertEquals("clie••••pted", summary.maskedClientId)
        assertFalse(loaded.toString().contains(CLIENT_ID))
        assertFalse(loaded.toString().contains(CLIENT_SECRET))
        assertTrue(androidKeyStore().containsAlias(keyAlias))
    }

    @Test
    fun authenticatedEncryptionRejectsModifiedCiphertext() = runBlocking {
        saveCredentials(CLIENT_ID, CLIENT_SECRET)
        val bytes = store.vaultFile.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        store.vaultFile.writeBytes(bytes)

        try {
            store.load()
            throw AssertionError("Expected modified credential ciphertext to be rejected")
        } catch (error: CloudCredentialStorageException) {
            assertEquals("CREDENTIAL_VAULT_DECRYPT_FAILED", error.code)
        }
    }

    @Test
    fun interruptedReplacementRestoresLastCompleteCredentials() = runBlocking {
        saveCredentials(CLIENT_ID, CLIENT_SECRET)
        AtomicFile(store.vaultFile).startWrite().close()

        val loaded = requireNotNull(store.load())

        assertEquals(CLIENT_ID, loaded.clientId.reveal())
        assertEquals(CLIENT_SECRET, loaded.clientSecret.reveal())
    }

    @Test
    fun deleteAllRemovesCiphertextAndDedicatedKeystoreEntry() = runBlocking {
        saveCredentials(CLIENT_ID, CLIENT_SECRET)
        assertTrue(store.vaultFile.exists())
        assertTrue(androidKeyStore().containsAlias(keyAlias))

        store.deleteAll()

        assertFalse(store.vaultFile.exists())
        assertFalse(androidKeyStore().containsAlias(keyAlias))
        assertNull(store.load())
    }

    private suspend fun saveCredentials(clientId: String, clientSecret: String) {
        store.save(
            region = TuyaCloudRegion.WESTERN_AMERICA,
            clientId = SensitiveString.of(clientId),
            clientSecret = SensitiveString.of(clientSecret),
        )
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty() || sequence.size > size) return false
        return (0..size - sequence.size).any { start ->
            sequence.indices.all { offset -> this[start + offset] == sequence[offset] }
        }
    }

    private companion object {
        const val FIXED_TIME = 1_900_000_000_000L
        const val CLIENT_ID = "client-id-must-stay-encrypted"
        const val CLIENT_SECRET = "client-secret-must-stay-encrypted"
    }
}
