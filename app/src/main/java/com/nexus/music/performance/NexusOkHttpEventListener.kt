package com.nexus.music.performance

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Protocol
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Proxy

/** Debug-only DNS and socket timing. IP addresses and request paths are deliberately omitted. */
internal class NexusOkHttpEventListener : EventListener() {
    private var dnsStartedAtMs = 0L
    private var connectStartedAtMs = 0L
    private var connectFailures = 0

    override fun dnsStart(call: Call, domainName: String) {
        if (!NexusPerformance.isEnabled()) return
        dnsStartedAtMs = NexusPerformance.nowMs()
        NexusPerformance.logEvent("dns_start", "host=" + domainName)
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
        if (!NexusPerformance.isEnabled()) return
        val families = inetAddressList.mapNotNull {
            when (it) {
                is Inet4Address -> "IPv4"
                is Inet6Address -> "IPv6"
                else -> null
            }
        }.distinct().joinToString(",")
        NexusPerformance.logEvent(
            "dns_complete",
            "host=" + domainName +
                " duration_ms=" + (NexusPerformance.nowMs() - dnsStartedAtMs) +
                " address_count=" + inetAddressList.size +
                " families=" + families
        )
    }

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy
    ) {
        if (!NexusPerformance.isEnabled()) return
        connectStartedAtMs = NexusPerformance.nowMs()
        val family = addressFamily(inetSocketAddress)
        val event = if (connectFailures > 0) "connect_fallback_start" else "connect_start"
        NexusPerformance.logEvent(
            event,
            "host=" + call.request().url.host +
                " family=" + family +
                " attempt=" + (connectFailures + 1)
        )
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) {
        if (!NexusPerformance.isEnabled()) return
        NexusPerformance.logEvent(
            "connect_complete",
            "host=" + call.request().url.host +
                " family=" + addressFamily(inetSocketAddress) +
                " duration_ms=" + (NexusPerformance.nowMs() - connectStartedAtMs) +
                " protocol=" + (protocol?.toString() ?: "unknown")
        )
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) {
        if (!NexusPerformance.isEnabled()) return
        connectFailures += 1
        NexusPerformance.logEvent(
            "connect_failed",
            "host=" + call.request().url.host +
                " family=" + addressFamily(inetSocketAddress) +
                " duration_ms=" + (NexusPerformance.nowMs() - connectStartedAtMs) +
                " error=" + ioe.javaClass.simpleName
        )
    }

    override fun callFailed(call: Call, ioe: IOException) {
        if (!NexusPerformance.isEnabled()) return
        NexusPerformance.logEvent(
            "http_call_failed",
            "host=" + call.request().url.host +
                " error=" + ioe.javaClass.simpleName
        )
    }

    private fun addressFamily(address: InetSocketAddress): String =
        when (address.address) {
            is Inet4Address -> "IPv4"
            is Inet6Address -> "IPv6"
            else -> "unknown"
        }
}
