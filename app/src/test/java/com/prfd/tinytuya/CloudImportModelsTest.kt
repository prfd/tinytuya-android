package com.prfd.tinytuya

import com.prfd.tinytuya.data.lan.LocalPollDevice
import com.prfd.tinytuya.data.python.CloudCredentials
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudImportModelsTest {
  @Test
  fun credentialsToStringRedactsUserSuppliedValues() {
    val credentials =
      CloudCredentials(
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

  @Test
  fun localPollDeviceToStringRedactsAddressIdentityAndKey() {
    val device =
      LocalPollDevice(
        id = "device-id-must-not-be-logged",
        ip = "192.168.10.42",
        localKey = SensitiveString.of("local-key-secret"),
        protocolVersion = "3.5",
      )

    val rendered = device.toString()

    assertFalse(rendered.contains("device-id-must-not-be-logged"))
    assertFalse(rendered.contains("192.168.10.42"))
    assertFalse(rendered.contains("local-key-secret"))
    assertTrue(rendered.contains("[REDACTED]"))
  }
}
