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
    fun pinsFirstResolvedAddressFamilyAndFiltersLaterLookups() {
        val ipv6 = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
        val ipv4 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))

        val first = StreamAddressFamily.enforce("cdn.example", listOf(ipv6, ipv4))
        val later = StreamAddressFamily.enforce("cdn.example", listOf(ipv4, ipv6))

        assertEquals(listOf(ipv6), first)
        assertEquals(listOf(ipv6), later)
    }

    @Test
    fun refusesToSilentlySwitchWhenPinnedFamilyDisappears() {
        val ipv6 = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
        val ipv4 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
        StreamAddressFamily.enforce("cdn.example", listOf(ipv6, ipv4))

        assertThrows(UnknownHostException::class.java) {
            StreamAddressFamily.enforce("cdn.example", listOf(ipv4))
        }
    }
}
