package com.prfd.tinytuya

import com.prfd.tinytuya.data.python.CloudCredentials
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudImportModelsTest {
    @Test
    fun credentialsToStringRedactsUserSuppliedValues() {
        val credentials = CloudCredentials(
            region = TuyaCloudRegion.CENTRAL_EUROPE,
            clientId = "client-id-must-not-be-logged",
            clientSecret = SensitiveString.of("secret-must-not-be-logged"),
            sampleDeviceId = "device-id-must-not-be-logged",
        )

        val rendered = credentials.toString()

        assertFalse(rendered.contains("client-id-must-not-be-logged"))
        assertFalse(rendered.contains("secret-must-not-be-logged"))
        assertFalse(rendered.contains("device-id-must-not-be-logged"))
        assertTrue(rendered.contains("[REDACTED]"))
    }
}
