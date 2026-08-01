package com.prfd.tinytuya.data.python

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prfd.tinytuya.data.lan.LanDiscoveryRequest
import com.prfd.tinytuya.data.lan.LanKnownDevice
import com.prfd.tinytuya.data.lan.LanNetworkContext
import com.prfd.tinytuya.data.lan.LocalPollDevice
import com.prfd.tinytuya.data.lan.LocalPollRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TuyaPythonGatewayInstrumentedTest {
    @Test
    fun healthReportsPinnedRuntimeAndWorkingCrypto() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val health = ChaquopyTuyaPythonGateway(context).health()

        assertEquals(1, health.contractVersion)
        assertTrue(health.pythonVersion.startsWith("3.11."))
        assertEquals("1.20.0", health.tinytuyaVersion)
        assertTrue(health.crypto.gcmAvailable)
        assertTrue(health.crypto.selfTestPassed)
        assertTrue("3.5" in health.supportedProtocols)
    }

    @Test
    fun cloudImportRejectsBlankCredentialsBeforeNetworkAccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gateway = ChaquopyTuyaPythonGateway(context)

        try {
            gateway.importCloud(
                CloudCredentials(
                    region = TuyaCloudRegion.WESTERN_AMERICA,
                    clientId = "",
                    clientSecret = SensitiveString.of("not-a-real-secret"),
                )
            )
            fail("Expected blank cloud credentials to be rejected")
        } catch (error: PythonBridgeException) {
            assertEquals("CLOUD_CREDENTIALS_REQUIRED", error.code)
        }
    }

    @Test
    fun lanDiscoveryRejectsInconsistentBroadcastBeforeOpeningSockets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gateway = ChaquopyTuyaPythonGateway(context)

        try {
            gateway.discoverLan(
                LanDiscoveryRequest(
                    network = LanNetworkContext(
                        interfaceName = "wlan0",
                        localIpv4 = "192.168.10.25",
                        prefixLength = 24,
                        broadcastIpv4 = "192.168.11.255",
                    ),
                    knownDevices = listOf(
                        LanKnownDevice(id = "known-device", name = "Lamp", mac = "")
                    ),
                    timeoutSeconds = 6,
                )
            )
            fail("Expected an inconsistent broadcast address to be rejected")
        } catch (error: PythonBridgeException) {
            assertEquals("LAN_NETWORK_INVALID", error.code)
        }
    }

    @Test
    fun localPollRejectsAddressOutsideSelectedWifiBeforeOpeningSocket() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gateway = ChaquopyTuyaPythonGateway(context)

        try {
            gateway.pollLocal(
                LocalPollRequest(
                    network = LanNetworkContext(
                        interfaceName = "wlan0",
                        localIpv4 = "192.168.10.25",
                        prefixLength = 24,
                        broadcastIpv4 = "192.168.10.255",
                    ),
                    devices = listOf(
                        LocalPollDevice(
                            id = "known-device",
                            ip = "192.168.11.42",
                            localKey = SensitiveString.of("0123456789abcdef"),
                            protocolVersion = "3.5",
                        )
                    ),
                )
            )
            fail("Expected an out-of-subnet poll address to be rejected")
        } catch (error: PythonBridgeException) {
            assertEquals("LOCAL_POLL_DEVICES_INVALID", error.code)
        }
    }
}
