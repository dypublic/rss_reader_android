package com.codex.rssreader.update

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.net.UnknownHostException

internal class FallbackDns(
    private val primary: Dns,
    private val fallback: Dns,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val primaryAddresses = try {
            primary.lookup(hostname)
        } catch (_: UnknownHostException) {
            emptyList()
        }
        return primaryAddresses.ifEmpty { fallback.lookup(hostname) }
    }
}

internal object UpdateDns {
    fun create(primary: Dns = Dns.SYSTEM): Dns {
        val bootstrapClient = OkHttpClient.Builder().build()
        val encryptedFallback = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.alidns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("223.5.5.5"),
                InetAddress.getByName("223.6.6.6"),
            )
            .includeIPv6(false)
            .build()
        return FallbackDns(primary, encryptedFallback)
    }
}
