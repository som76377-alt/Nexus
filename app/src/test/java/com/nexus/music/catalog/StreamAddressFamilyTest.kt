package com.nexus.music.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class StreamAddressFamilyTest {
    @Before
    fun resetPins() {
        StreamAddressFamily.clear("cdn.example")
    }

    @Test
    fun pinsTheFamilyOfTheAddressThatActuallyConnected() {
        val ipv6 = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
        val ipv4 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))

        val initial = StreamAddressFamily.filterPinned("cdn.example", listOf(ipv6, ipv4))
        StreamAddressFamily.recordConnectedAddress("cdn.example", ipv6)
        val later = StreamAddressFamily.filterPinned("cdn.example", listOf(ipv4, ipv6))

        assertEquals(listOf(ipv6, ipv4), initial)
        assertEquals(listOf(ipv6), later)
    }

    @Test
    fun refusesToSilentlySwitchWhenPinnedFamilyDisappears() {
        val ipv6 = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
        val ipv4 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
        StreamAddressFamily.recordConnectedAddress("cdn.example", ipv6)

        assertThrows(UnknownHostException::class.java) {
            StreamAddressFamily.filterPinned("cdn.example", listOf(ipv4))
        }
    }
}
