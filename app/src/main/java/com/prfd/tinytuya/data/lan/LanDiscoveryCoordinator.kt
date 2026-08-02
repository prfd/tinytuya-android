package com.prfd.tinytuya.data.lan

import android.content.Context
import android.net.wifi.WifiManager
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.DeviceCatalogStore
import com.prfd.tinytuya.data.python.PythonBridgeException
import com.prfd.tinytuya.data.python.TuyaPythonGateway
import java.util.concurrent.CancellationException

interface LanDiscoveryCoordinator {
    suspend fun discover(catalog: DeviceCatalog): LanDiscoveryOutcome
}

data class LanDiscoveryOutcome(
    val catalog: DeviceCatalog,
    val network: LanNetworkContext,
)

interface LanDiscoveryRadio {
    fun acquire()

    fun release()
}

class AndroidLanDiscoveryRadio(context: Context) : LanDiscoveryRadio {
    private val applicationContext = context.applicationContext
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun acquire() {
        val lock = multicastLock ?: applicationContext
            .getSystemService(WifiManager::class.java)
            ?.createMulticastLock("tinytuya-lan-discovery")
            ?.apply { setReferenceCounted(false) }
            ?.also { multicastLock = it }
            ?: throw LanDiscoveryException(
                code = "LAN_WIFI_UNAVAILABLE",
                message = "Wi-Fi discovery is not available on this device.",
            )
        if (!lock.isHeld) lock.acquire()
    }

    override fun release() {
        multicastLock?.takeIf { it.isHeld }?.release()
    }
}

class DefaultLanDiscoveryCoordinator(
    private val gateway: TuyaPythonGateway,
    private val catalogStore: DeviceCatalogStore,
    private val networkResolver: LanNetworkResolver,
    private val radio: LanDiscoveryRadio,
) : LanDiscoveryCoordinator {
    override suspend fun discover(catalog: DeviceCatalog): LanDiscoveryOutcome {
        if (catalog.devices.isEmpty()) {
            throw LanDiscoveryException(
                code = "LAN_KNOWN_DEVICES_INVALID",
                message = "Import at least one Tuya device before scanning the local network.",
            )
        }

        val network = networkResolver.resolve()
        val request = LanDiscoveryRequest(
            network = network,
            knownDevices = catalog.devices.map { device ->
                LanKnownDevice(id = device.id, name = device.name, mac = device.mac)
            },
            timeoutSeconds = DISCOVERY_TIMEOUT_SECONDS,
        )
        var radioAcquired = false
        try {
            try {
                radio.acquire()
                radioAcquired = true
            } catch (_: SecurityException) {
                throw LanDiscoveryException(
                    code = "LAN_PERMISSION_DENIED",
                    message = "Android blocked local network discovery.",
                )
            }

            val result = try {
                gateway.discoverLan(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: PythonBridgeException) {
                throw LanDiscoveryException(
                    code = error.code,
                    message = error.message ?: "Local Tuya discovery could not be completed.",
                )
            }
            return LanDiscoveryOutcome(
                catalog = catalogStore.mergeLanDiscovery(result, network),
                network = network,
            )
        } finally {
            if (radioAcquired) radio.release()
        }
    }

    private companion object {
        const val DISCOVERY_TIMEOUT_SECONDS = 12
    }
}
