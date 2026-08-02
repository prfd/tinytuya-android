package com.prfd.tinytuya.data.lan

data class LanNetworkContext(
    val interfaceName: String,
    val localIpv4: String,
    val prefixLength: Int,
    val broadcastIpv4: String,
    /** Android identity for this exact network; deliberately omitted from the Python bridge. */
    val networkHandle: Long = UNKNOWN_NETWORK_HANDLE,
) {
    override fun toString(): String =
        "LanNetworkContext(interface=[REDACTED], localIpv4=[REDACTED], " +
            "prefixLength=$prefixLength, broadcastIpv4=[REDACTED], " +
            "networkHandle=[REDACTED])"

    companion object {
        const val UNKNOWN_NETWORK_HANDLE = 0L
    }
}

sealed interface LanNetworkObservation {
    data class Available(val network: LanNetworkContext) : LanNetworkObservation

    data object Unavailable : LanNetworkObservation
}

data class LanKnownDevice(
    val id: String,
    val name: String,
    val mac: String,
)

data class LanDiscoveryRequest(
    val network: LanNetworkContext,
    val knownDevices: List<LanKnownDevice>,
    val timeoutSeconds: Int,
)

data class LanDiscoveredDevice(
    val id: String,
    val ip: String,
    val protocolVersion: String,
    val productKey: String,
    val mac: String,
    val origin: String,
) {
    override fun toString(): String =
        "LanDiscoveredDevice(id=[REDACTED], ip=[REDACTED], " +
            "protocolVersion=$protocolVersion, origin=$origin)"
}

data class LanDiscoveryResult(
    val contractVersion: Int,
    val deviceCount: Int,
    val matchedDeviceCount: Int,
    val unmatchedDeviceCount: Int,
    val durationMillis: Long,
    val warnings: List<String>,
    val devices: List<LanDiscoveredDevice>,
)

class LanDiscoveryException(
    val code: String,
    message: String,
) : IllegalStateException(message)
