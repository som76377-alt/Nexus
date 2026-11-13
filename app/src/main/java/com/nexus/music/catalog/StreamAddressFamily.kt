package com.nexus.music.catalog

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Pins a stream host to one IP family during its validation probe.
 * Media3 playback uses the same DNS policy and therefore cannot silently switch families.
 */
internal object StreamAddressFamily {
    private enum class Family {
        IPV4, IPV6;

        fun accepts(address: InetAddress): Boolean = when (this) {
            IPV4 -> address is Inet4Address
            IPV6 -> address is Inet6Address
        }

        override fun toString(): String = when (this) {
            IPV4 -> "IPv4"
            IPV6 -> "IPv6"
        }
    }

    private val pinnedFamilies = ConcurrentHashMap<String, Family>()

    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            selectAndPin(hostname, Dns.SYSTEM.lookup(hostname))
    }

    internal fun selectAndPin(hostname: String, addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.isEmpty()) throw UnknownHostException("No addresses available for $hostname")
        val key = hostname.lowercase()
        val selectedFamily = pinnedFamilies[key] ?: familyOf(addresses.first())?.also {
            pinnedFamilies.putIfAbsent(key, it)
        } ?: return addresses
        val matching = addresses.filter(selectedFamily::accepts)
        if (matching.isEmpty()) {
            throw UnknownHostException(
                "Host $hostname has no $selectedFamily address; refusing to switch address family."
            )
        }
        return matching
    }

    internal fun clear(hostname: String) {
        pinnedFamilies.remove(hostname.lowercase())
    }

    private fun familyOf(address: InetAddress): Family? = when (address) {
        is Inet4Address -> Family.IPV4
        is Inet6Address -> Family.IPV6
        else -> null
    }
}
