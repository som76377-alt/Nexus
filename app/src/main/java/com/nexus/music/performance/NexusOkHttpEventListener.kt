package com.nexus.music.performance

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only DNS and socket timing. IP addresses and request paths are deliberately omitted. */
internal class NexusOkHttpEventListener : EventListener() {
    private val dnsStartTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val connectStartTimes = ConcurrentHashMap<InetSocketAddress, ConcurrentLinkedQueue<Long>>()
    private val connectFailures = AtomicInteger()

    override fun dnsStart(call: Call, domainName: String) {
        if (!NexusPerformance.isEnabled()) return
        dnsStartTimes.computeIfAbsent(domainName) { ConcurrentLinkedQueue() }
            .add(NexusPerformance.nowMs())
        NexusPerformance.logEvent("dns_start", "host=" + domainName)
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        if (!NexusPerformance.isEnabled()) return
        val startedAt = dnsStartTimes[domainName]?.poll()
        if (dnsStartTimes[domainName]?.isEmpty() == true) dnsStartTimes.remove(domainName)
        val families = inetAddressList.mapNotNull(::addressFamily).distinct().joinToString(",")
        NexusPerformance.logEvent(
            "dns_complete",
            "host=" + domainName +
                " duration_ms=" + (startedAt?.let { NexusPerformance.nowMs() - it } ?: -1L) +
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
        connectStartTimes.computeIfAbsent(inetSocketAddress) { ConcurrentLinkedQueue() }
            .add(NexusPerformance.nowMs())
        val failures = connectFailures.get()
        val event = if (failures > 0) "connect_fallback_start" else "connect_start"
        NexusPerformance.logEvent(
            event,
            "host=" + call.request().url.host +
                " family=" + addressFamily(inetSocketAddress.address) +
                " attempt=" + (failures + 1)
        )
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) {
        if (!NexusPerformance.isEnabled()) return
        val duration = takeConnectDuration(inetSocketAddress)
        NexusPerformance.logEvent(
            "connect_complete",
            "host=" + call.request().url.host +
                " family=" + addressFamily(inetSocketAddress.address) +
                " duration_ms=" + duration +
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
        val failures = connectFailures.incrementAndGet()
        val duration = takeConnectDuration(inetSocketAddress)
        NexusPerformance.logEvent(
            "connect_failed",
            "host=" + call.request().url.host +
                " family=" + addressFamily(inetSocketAddress.address) +
                " attempt=" + failures +
                " duration_ms=" + duration +
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

    private fun takeConnectDuration(address: InetSocketAddress): Long {
        val queue = connectStartTimes[address] ?: return -1L
        val startedAt = queue.poll() ?: return -1L
        if (queue.isEmpty()) connectStartTimes.remove(address, queue)
        return NexusPerformance.nowMs() - startedAt
    }

    private fun addressFamily(address: InetAddress?): String =
        when (address) {
            is Inet4Address -> "IPv4"
            is Inet6Address -> "IPv6"
            else -> "unknown"
        }
}
