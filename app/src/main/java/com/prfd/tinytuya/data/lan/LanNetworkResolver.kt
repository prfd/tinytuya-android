package com.prfd.tinytuya.data.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

interface LanNetworkResolver {
  suspend fun resolve(): LanNetworkContext
}

fun interface LanNetworkObserver {
  fun observe(): Flow<LanNetworkObservation>
}

object NoOpLanNetworkObserver : LanNetworkObserver {
  override fun observe(): Flow<LanNetworkObservation> = emptyFlow()
}

class AndroidLanNetworkResolver(context: Context) : LanNetworkResolver, LanNetworkObserver {
  private val connectivityManager =
    context.applicationContext.getSystemService(ConnectivityManager::class.java)

  override suspend fun resolve(): LanNetworkContext {
    try {
      val activeNetwork = connectivityManager.activeNetwork
      activeNetwork?.let(::resolveWifiNetwork)?.let {
        return it
      }

      val discovered = withTimeoutOrNull(NETWORK_CALLBACK_TIMEOUT_MILLIS) { awaitWifiNetwork() }
      return discovered
        ?: throw LanDiscoveryException(
          code = "LAN_WIFI_UNAVAILABLE",
          message = "Connect this phone to the same Wi-Fi network as your Tuya devices.",
        )
    } catch (_: SecurityException) {
      throw LanDiscoveryException(
        code = "LAN_PERMISSION_DENIED",
        message = "Android blocked local network discovery.",
      )
    }
  }

  /**
   * Observes Android's default network rather than every visible Wi-Fi network. A new Android
   * network handle is emitted even when two Wi-Fi networks happen to use the same private subnet.
   */
  override fun observe(): Flow<LanNetworkObservation> = callbackFlow {
    var callbackNetwork: Network? = null
    var callbackCapabilities: NetworkCapabilities? = null
    var callbackLinkProperties: LinkProperties? = null

    fun publishCallbackSnapshot() {
      val network = callbackNetwork ?: return
      val capabilities = callbackCapabilities ?: return
      val linkProperties = callbackLinkProperties ?: return
      val resolved = resolveWifiNetwork(network, capabilities, linkProperties)
      trySend(resolved?.let(LanNetworkObservation::Available) ?: LanNetworkObservation.Unavailable)
    }

    val callback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          callbackNetwork = network
          callbackCapabilities = null
          callbackLinkProperties = null
        }

        override fun onCapabilitiesChanged(
          network: Network,
          networkCapabilities: NetworkCapabilities,
        ) {
          if (callbackNetwork != network) return
          callbackCapabilities = networkCapabilities
          publishCallbackSnapshot()
        }

        override fun onLinkPropertiesChanged(
          network: Network,
          linkProperties: LinkProperties,
        ) {
          if (callbackNetwork != network) return
          callbackLinkProperties = linkProperties
          publishCallbackSnapshot()
        }

        override fun onLost(network: Network) {
          if (callbackNetwork != network) return
          callbackNetwork = null
          callbackCapabilities = null
          callbackLinkProperties = null
          trySend(LanNetworkObservation.Unavailable)
        }

        override fun onUnavailable() {
          callbackNetwork = null
          callbackCapabilities = null
          callbackLinkProperties = null
          trySend(LanNetworkObservation.Unavailable)
        }
      }
    var registered = false
    try {
      val initialNetwork = connectivityManager.activeNetwork
      val initialObservation =
        initialNetwork?.let { network ->
          resolveWifiNetwork(network)?.let(LanNetworkObservation::Available)
        } ?: LanNetworkObservation.Unavailable
      trySend(initialObservation)
      connectivityManager.registerDefaultNetworkCallback(callback)
      registered = true
    } catch (_: SecurityException) {
      trySend(LanNetworkObservation.Unavailable)
      close()
    } catch (_: RuntimeException) {
      trySend(LanNetworkObservation.Unavailable)
      close()
    }

    awaitClose {
      if (registered) {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
      }
    }
  }
    .distinctUntilChanged()

  private suspend fun awaitWifiNetwork(): LanNetworkContext =
    suspendCancellableCoroutine { continuation ->
      val completed = AtomicBoolean(false)
      lateinit var callback: ConnectivityManager.NetworkCallback

      fun finish(result: Result<LanNetworkContext>) {
        if (!completed.compareAndSet(false, true)) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        result.fold(continuation::resume, continuation::resumeWithException)
      }

      callback =
        object : ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: Network) {
            resolveWifiNetwork(network)?.let { finish(Result.success(it)) }
          }

          override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: android.net.LinkProperties,
          ) {
            resolveWifiNetwork(network)?.let { finish(Result.success(it)) }
          }
        }
      continuation.invokeOnCancellation {
        if (completed.compareAndSet(false, true)) {
          runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
      }

      try {
        val request =
          NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivityManager.registerNetworkCallback(request, callback)
      } catch (error: Exception) {
        finish(Result.failure(error))
      }
    }

  private fun resolveWifiNetwork(network: Network): LanNetworkContext? {
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
    val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
    return resolveWifiNetwork(network, capabilities, linkProperties)
  }

  private fun resolveWifiNetwork(
    network: Network,
    capabilities: NetworkCapabilities,
    linkProperties: LinkProperties,
  ): LanNetworkContext? {
    if (
      !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    ) {
      return null
    }
    val interfaceName = linkProperties.interfaceName?.takeIf { it.isNotBlank() } ?: return null
    val linkAddress =
      linkProperties.linkAddresses.firstOrNull {
        it.address is Inet4Address &&
          !it.address.isAnyLocalAddress &&
          !it.address.isLoopbackAddress &&
          !it.address.isMulticastAddress &&
          it.prefixLength in 1..30
      } ?: return null
    val ipv4 = linkAddress.address as Inet4Address

    return LanNetworkContext(
      interfaceName = interfaceName,
      localIpv4 = ipv4.hostAddress ?: return null,
      prefixLength = linkAddress.prefixLength,
      broadcastIpv4 = ipv4BroadcastAddress(ipv4, linkAddress.prefixLength),
      networkHandle = network.networkHandle,
    )
  }
}

internal fun ipv4BroadcastAddress(address: Inet4Address, prefixLength: Int): String {
  require(prefixLength in 1..30) { "IPv4 prefix must be broadcast-capable." }
  val broadcastBytes =
    address.address
      .mapIndexed { index, byte ->
        val networkBits = (prefixLength - index * 8).coerceIn(0, 8)
        val mask =
          if (networkBits == 0) {
            0
          } else {
            (0xff shl (8 - networkBits)) and 0xff
          }
        ((byte.toInt() and 0xff) or (mask xor 0xff)).toByte()
      }
      .toByteArray()
  return requireNotNull(InetAddress.getByAddress(broadcastBytes).hostAddress)
}

private const val NETWORK_CALLBACK_TIMEOUT_MILLIS = 1_500L
