package com.prfd.tinytuya.data.lan

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LanNetworkResolverTest {
  @Test
  fun calculatesNonOctetAlignedBroadcast() {
    assertEquals("10.42.15.255", broadcast("10.42.5.2", 20))
  }

  @Test
  fun rejectsPrefixWithoutUsableBroadcastHosts() {
    try {
      broadcast("192.168.50.23", 31)
      fail("Expected a /31 prefix to be rejected")
    } catch (_: IllegalArgumentException) {
      // Expected.
    }
  }

  private fun broadcast(address: String, prefixLength: Int): String =
    ipv4BroadcastAddress(
      address = InetAddress.getByName(address) as Inet4Address,
      prefixLength = prefixLength,
    )
}
